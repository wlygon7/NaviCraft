package com.wlygon.navicraft.client;

import com.wlygon.navicraft.client.path.PathNode;
import com.wlygon.navicraft.client.path.PathProvider;
import com.wlygon.navicraft.client.path.StraightLinePathProvider;
import com.wlygon.navicraft.net.NavTargetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Client-side navigation session state: the active target (as synced from the
 * server) and the computed arrow path.
 *
 * <p>Threading: the target and path are written on the client (game) thread —
 * payload receivers and tick events both run there — and read during rendering,
 * which may happen on another thread in the extract/submit pipeline. Both fields
 * are therefore volatile immutable snapshots; renderers must read each field once
 * per frame and work with the copy.
 */
public final class ClientNavState {
    /** Recompute at most every this many ticks while the player is moving. */
    private static final int RECALC_MIN_INTERVAL_TICKS = 10;
    /** Recompute at least every this many ticks (picks up newly loaded chunks). */
    private static final int RECALC_MAX_INTERVAL_TICKS = 40;
    /** Movement (in blocks) from the last path origin that triggers a recompute. */
    private static final double RECALC_MOVE_THRESHOLD = 2.5;

    /**
     * The leg the client is steering toward. {@code mode} mirrors the server's
     * {@link com.wlygon.navicraft.nav.NavLeg} constants: 0 = destination itself,
     * 1 = intermediate portal marker, 2 = cross-dimension with no portal known.
     * {@code label} names the leg point; {@code finalLabel} the destination.
     */
    public record Target(byte mode, String dimensionId, Vec3 pos, String label, String finalLabel) {
    }

    private static volatile Target target;
    private static volatile List<PathNode> path = List.of();

    private static PathProvider pathProvider = new StraightLinePathProvider();
    private static Vec3 lastPathOrigin;
    private static long lastRecalcTick = Long.MIN_VALUE;
    private static long tickCounter;

    private ClientNavState() {
    }

    public static Target getTarget() {
        return target;
    }

    public static List<PathNode> getPath() {
        return path;
    }

    /** Swap in a different path strategy (e.g. road-following) at runtime. */
    public static void setPathProvider(PathProvider provider) {
        pathProvider = provider;
        invalidatePath();
    }

    public static void onPayload(NavTargetPayload payload) {
        if (payload.active()) {
            target = new Target(payload.mode(), payload.dimensionId(),
                    new Vec3(payload.x(), payload.y(), payload.z()),
                    payload.legLabel(), payload.finalLabel());
        } else {
            target = null;
        }
        invalidatePath();
    }

    public static void reset() {
        target = null;
        invalidatePath();
    }

    public static boolean isInTargetDimension(Minecraft client) {
        Target current = target;
        return current != null && client.level != null
                && client.level.dimension().identifier().toString().equals(current.dimensionId());
    }

    /** Called at the end of every client tick; recomputes the path when it is stale. */
    public static void clientTick(Minecraft client) {
        tickCounter++;

        Target current = target;
        if (current == null || client.player == null || client.level == null || !isInTargetDimension(client)) {
            if (!path.isEmpty()) {
                invalidatePath();
            }
            return;
        }

        Vec3 playerPos = client.player.position();
        boolean stale = path.isEmpty()
                || tickCounter - lastRecalcTick >= RECALC_MAX_INTERVAL_TICKS
                || (lastPathOrigin != null
                        && playerPos.distanceToSqr(lastPathOrigin) >= RECALC_MOVE_THRESHOLD * RECALC_MOVE_THRESHOLD
                        && tickCounter - lastRecalcTick >= RECALC_MIN_INTERVAL_TICKS);
        if (!stale) {
            return;
        }

        path = pathProvider.computePath(client.level, playerPos, current.pos,
                NaviCraftConfig.get().maxRenderDistance + NaviCraftConfig.get().arrowSpacing);
        lastPathOrigin = playerPos;
        lastRecalcTick = tickCounter;
    }

    private static void invalidatePath() {
        path = List.of();
        lastPathOrigin = null;
        lastRecalcTick = Long.MIN_VALUE;
    }
}
