package com.wlygon.navicraft.gametest;

import com.wlygon.navicraft.client.ClientNavState;
import com.wlygon.navicraft.nav.NavLeg;
import com.wlygon.navicraft.nav.NavTarget;
import com.wlygon.navicraft.waypoint.Waypoint;
import com.wlygon.navicraft.waypoint.WaypointStore;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * End-to-end test of marker persistence, portal-aware cross-dimension routing,
 * and nav-target persistence across world close/reopen (the singleplayer
 * equivalent of logout/login).
 */
public class NaviCraftGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        TestWorldSave save;
        UUID playerId;

        // ===== Session 1 =====
        try (TestSingleplayerContext sp = context.worldBuilder().create()) {
            save = sp.getWorldSave();
            sp.getConnection().waitForChunksRender();
            playerId = sp.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().get(0).getUUID());

            // Creative so teleports into terrain can't suffocate/kill the player.
            sp.getServer().runCommand("gamemode creative @a");

            // --- Markers persist write-through, keyed by the player UUID ---
            runPlayerCommand(sp, "gps marker 100 64 100 home");
            runPlayerCommand(sp, "gps marker 300 64 300 basecamp");
            context.waitTicks(2);

            int markerCount = sp.getServer().computeOnServer(server ->
                    WaypointStore.get().list(server, playerId).size());
            assertThat(markerCount == 2, "expected 2 markers after saving, got " + markerCount);

            Path waypointFile = save.getSaveDirectory()
                    .resolve("data/navicraft/waypoints/" + playerId + ".json");
            assertThat(Files.exists(waypointFile), "waypoint file missing on disk: " + waypointFile);

            // --- Direct navigation ---
            runPlayerCommand(sp, "gps goto home");
            waitForClientMode(context, NavLeg.MODE_DIRECT, "after /gps goto home in same dimension");

            NavTarget persisted = sp.getServer().computeOnServer(server ->
                    WaypointStore.get().getActiveNav(server, playerId));
            assertThat(persisted != null && persisted.label().equals("home"),
                    "active nav should be persisted right after goto");

            // --- Cross-dimension with no portal marker -> MODE_NO_PORTAL ---
            sp.getServer().runCommand("execute in minecraft:the_nether run tp @a 8 70 8");
            waitForClientMode(context, NavLeg.MODE_NO_PORTAL, "in nether with no portal marker");

            // --- Adding a portal marker re-routes immediately -> MODE_PORTAL ---
            runPlayerCommand(sp, "gps marker 20 70 20 nether_exit --portal");
            waitForClientMode(context, NavLeg.MODE_PORTAL, "after adding a nether portal marker");
            assertClientLegLabel(context, "nether_exit", "portal leg should target the new marker");

            // --- Nearest portal wins: a far second portal must not steal the leg ---
            runPlayerCommand(sp, "gps marker 5000 70 5000 far_portal --portal");
            context.waitTicks(5);
            assertClientLegLabel(context, "nether_exit", "nearest portal must keep the leg");

            // World closes here with nav active, player in the nether, mid-portal-route.
        }

        // ===== Session 2: reopen the same world =====
        try (TestSingleplayerContext sp = save.open()) {
            sp.getConnection().waitForChunksRender();
            UUID reopenedId = sp.getServer().computeOnServer(server ->
                    server.getPlayerList().getPlayers().get(0).getUUID());
            assertThat(reopenedId.equals(playerId),
                    "reopened session must be the same player (got " + reopenedId + ")");

            // --- Markers survived the restart ---
            List<Waypoint> markers = sp.getServer().computeOnServer(server ->
                    WaypointStore.get().list(server, playerId));
            assertThat(markers.size() == 4, "expected 4 markers after reopen, got " + markers.size());

            // --- Nav re-hydrated at the correct STAGE: still in the nether, so the
            // leg must resolve straight back to the portal marker, not restart. ---
            waitForClientMode(context, NavLeg.MODE_PORTAL, "re-hydrated mid-portal-route after reopen");
            assertClientLegLabel(context, "nether_exit", "re-hydrated leg should still be the nearest portal");

            String finalLabel = sp.getServer().computeOnServer(server -> {
                NavTarget target = WaypointStore.get().getActiveNav(server, playerId);
                return target == null ? null : target.label();
            });
            assertThat("home".equals(finalLabel), "persisted final target should still be home");

            // --- Going through to the overworld advances the route to DIRECT ---
            sp.getServer().runCommand("execute in minecraft:overworld run tp @a 150 70 150");
            waitForClientMode(context, NavLeg.MODE_DIRECT, "back in the target dimension");

            // --- Arrival clears both session and persisted state ---
            sp.getServer().runCommand("execute in minecraft:overworld run tp @a 100 65 100");
            context.waitFor(client -> ClientNavState.getTarget() == null,
                    ClientGameTestContext.DEFAULT_TIMEOUT);
            NavTarget afterArrival = sp.getServer().computeOnServer(server ->
                    WaypointStore.get().getActiveNav(server, playerId));
            assertThat(afterArrival == null, "arrival must clear the persisted nav target");

            // --- /gps stop clears persisted state too ---
            runPlayerCommand(sp, "gps goto basecamp");
            waitForClientMode(context, NavLeg.MODE_DIRECT, "after goto basecamp");
            runPlayerCommand(sp, "gps stop");
            context.waitFor(client -> ClientNavState.getTarget() == null,
                    ClientGameTestContext.DEFAULT_TIMEOUT);
            NavTarget afterStop = sp.getServer().computeOnServer(server ->
                    WaypointStore.get().getActiveNav(server, playerId));
            assertThat(afterStop == null, "/gps stop must clear the persisted nav target");

            // --- v1 legacy file (bare JSON array) still loads ---
            UUID legacyId = UUID.randomUUID();
            Path legacyFile = save.getSaveDirectory()
                    .resolve("data/navicraft/waypoints/" + legacyId + ".json");
            Files.writeString(legacyFile,
                    "[{\"name\":\"old\",\"dimension\":\"minecraft:overworld\",\"x\":1,\"y\":2,\"z\":3}]");
            List<Waypoint> legacy = sp.getServer().computeOnServer(server ->
                    WaypointStore.get().list(server, legacyId));
            assertThat(legacy.size() == 1 && legacy.get(0).name().equals("old") && !legacy.get(0).portal(),
                    "legacy v1 waypoint file should migrate cleanly");
        } catch (Exception e) {
            throw new AssertionError("session 2 failed", e);
        }
    }

    /** Runs a /gps command as the (only) connected player, on the server thread. */
    private static void runPlayerCommand(TestSingleplayerContext sp, String command) {
        sp.getServer().runOnServer(server -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            server.getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
        });
    }

    /**
     * Waits until the client-side nav state reports the expected leg mode. Covers
     * the up-to-20-tick server refresh cadence plus payload delivery.
     */
    private static void waitForClientMode(ClientGameTestContext context, byte expectedMode, String situation) {
        try {
            context.waitFor(client -> {
                ClientNavState.Target target = ClientNavState.getTarget();
                return target != null && target.mode() == expectedMode;
            }, ClientGameTestContext.DEFAULT_TIMEOUT);
        } catch (Throwable t) {
            ClientNavState.Target target = ClientNavState.getTarget();
            throw new AssertionError("expected client mode " + expectedMode + " " + situation
                    + " but state is " + target, t);
        }
    }

    private static void assertClientLegLabel(ClientGameTestContext context, String expected, String message) {
        String actual = context.computeOnClient(client -> {
            ClientNavState.Target target = ClientNavState.getTarget();
            return target == null ? null : target.label();
        });
        assertThat(expected.equals(actual), message + " (expected \"" + expected + "\", got \"" + actual + "\")");
    }

    private static void assertThat(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
