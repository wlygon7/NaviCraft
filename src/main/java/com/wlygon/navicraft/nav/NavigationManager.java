package com.wlygon.navicraft.nav;

import com.wlygon.navicraft.net.NavTargetPayload;
import com.wlygon.navicraft.waypoint.Waypoint;
import com.wlygon.navicraft.waypoint.WaypointStore;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative registry of active navigation targets.
 *
 * <p>Targets persist: they are written to the player's {@link WaypointStore} file
 * on every change, re-hydrated on login, and only removed by {@code /gps stop} or
 * arrival. The in-memory map is just the session view.
 *
 * <p>Cross-dimension routing works by deriving a {@link NavLeg} from the player's
 * <em>current</em> dimension each time it might have changed (target set, login,
 * dimension change, portal markers edited). Because legs are derived rather than
 * stored, a player who logs out mid-route resumes at the correct stage
 * automatically - whatever dimension they log back into determines the leg.
 */
public final class NavigationManager {
    /** Horizontal distance at which navigation completes and clears itself. */
    private static final double ARRIVAL_RADIUS = 2.5;
    /**
     * Vertical slack for arrival. Arrival is essentially a horizontal concept
     * (the marker's Y is wherever the player stood when saving it - possibly
     * flying, or on a peak the arriving player stands below); this band only
     * prevents "arriving" at a surface marker from deep inside a cave.
     */
    private static final double ARRIVAL_VERTICAL_TOLERANCE = 24;
    /** Only check arrivals/dimension changes once per second; pure UX. */
    private static final int CHECK_INTERVAL_TICKS = 20;

    /** A final destination plus the leg currently being steered toward. */
    private static final class ActiveNav {
        final NavTarget finalTarget;
        NavLeg leg;
        /** Dimension the leg was resolved for; a mismatch means "re-resolve". */
        String resolvedForDimension;

        ActiveNav(NavTarget finalTarget) {
            this.finalTarget = finalTarget;
        }
    }

    private static final Map<UUID, ActiveNav> TARGETS = new HashMap<>();
    private static int tickCounter = 0;

    private NavigationManager() {
    }

    /** Sets a new destination, resolves its first leg, syncs, and persists. */
    public static void setTarget(ServerPlayer player, NavTarget target) {
        ActiveNav nav = new ActiveNav(target);
        TARGETS.put(player.getUUID(), nav);
        resolveLegAndSync(player, nav);
        WaypointStore.get().setActiveNav(player.level().getServer(), player.getUUID(), target);
    }

    public static NavTarget getTarget(ServerPlayer player) {
        ActiveNav nav = TARGETS.get(player.getUUID());
        return nav == null ? null : nav.finalTarget;
    }

    /** @return true if there was an active target to clear */
    public static boolean clearTarget(ServerPlayer player) {
        boolean hadTarget = TARGETS.remove(player.getUUID()) != null;
        ServerPlayNetworking.send(player, NavTargetPayload.clear());
        // Clear the persisted copy too, even if the in-memory one was already gone.
        WaypointStore.get().setActiveNav(player.level().getServer(), player.getUUID(), null);
        return hadTarget;
    }

    /** Re-hydrates a persisted nav target when a player logs in. */
    public static void onJoin(ServerPlayer player, MinecraftServer server) {
        NavTarget saved = WaypointStore.get().getActiveNav(server, player.getUUID());
        if (saved != null) {
            ActiveNav nav = new ActiveNav(saved);
            TARGETS.put(player.getUUID(), nav);
            resolveLegAndSync(player, nav);
        }
    }

    /**
     * Called when the player's markers change; a new/removed portal marker can
     * change which leg a cross-dimension route should use.
     */
    public static void onMarkersChanged(ServerPlayer player) {
        ActiveNav nav = TARGETS.get(player.getUUID());
        if (nav != null && nav.leg != null && nav.leg.mode() != NavLeg.MODE_DIRECT) {
            resolveLegAndSync(player, nav);
        }
    }

    public static void onDisconnect(UUID player) {
        TARGETS.remove(player); // the persisted copy stays for re-hydration
    }

    public static void onServerStopped() {
        TARGETS.clear();
        tickCounter = 0;
    }

    /** Refreshes legs after dimension changes and completes navigation on arrival. */
    public static void serverTick(MinecraftServer server) {
        if (++tickCounter % CHECK_INTERVAL_TICKS != 0 || TARGETS.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<UUID, ActiveNav>> it = TARGETS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ActiveNav> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                it.remove();
                continue;
            }

            ActiveNav nav = entry.getValue();
            if (!dimensionOf(player).equals(nav.resolvedForDimension)) {
                // Player went through a portal (or was teleported); pick the next leg.
                resolveLegAndSync(player, nav);
            }

            // Arrival only applies to the destination itself. Reaching a portal
            // marker is not arrival - the dimension change above advances the route.
            if (nav.leg.mode() == NavLeg.MODE_DIRECT && isAt(player, nav.finalTarget)) {
                it.remove();
                ServerPlayNetworking.send(player, NavTargetPayload.clear());
                WaypointStore.get().setActiveNav(server, player.getUUID(), null);
                String where = nav.finalTarget.label().isEmpty() ? "your destination" : nav.finalTarget.label();
                player.sendSystemMessage(Component.literal("You have arrived at " + where + ".")
                        .withStyle(ChatFormatting.GREEN));
            }
        }
    }

    /**
     * Derives the current leg from the player's dimension: the destination itself
     * when already in its dimension; otherwise the nearest portal marker here, or
     * a raw cross-dimension pointer when no portal marker exists.
     */
    private static void resolveLegAndSync(ServerPlayer player, ActiveNav nav) {
        String playerDimension = dimensionOf(player);
        NavTarget target = nav.finalTarget;

        NavLeg leg;
        if (playerDimension.equals(target.dimensionId())) {
            leg = new NavLeg(NavLeg.MODE_DIRECT, target.dimensionId(), target.pos(), target.label());
        } else {
            Waypoint portal = WaypointStore.get().nearestPortal(
                    player.level().getServer(), player.getUUID(), playerDimension, player.position());
            leg = portal != null
                    ? new NavLeg(NavLeg.MODE_PORTAL, portal.dimension(), portal.pos(), portal.name())
                    : new NavLeg(NavLeg.MODE_NO_PORTAL, target.dimensionId(), target.pos(), target.label());
        }

        boolean changed = !leg.equals(nav.leg);
        nav.leg = leg;
        nav.resolvedForDimension = playerDimension;
        if (changed) {
            ServerPlayNetworking.send(player, NavTargetPayload.of(leg, target));
        }
    }

    private static boolean isAt(ServerPlayer player, NavTarget target) {
        double dx = player.getX() - target.pos().x;
        double dz = player.getZ() - target.pos().z;
        return dx * dx + dz * dz <= ARRIVAL_RADIUS * ARRIVAL_RADIUS
                && Math.abs(player.getY() - target.pos().y) <= ARRIVAL_VERTICAL_TOLERANCE;
    }

    private static String dimensionOf(ServerPlayer player) {
        return player.level().dimension().identifier().toString();
    }
}
