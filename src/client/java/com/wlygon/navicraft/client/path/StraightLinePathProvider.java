package com.wlygon.navicraft.client.path;

import com.wlygon.navicraft.client.NaviCraftConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * v1 path: a straight horizontal line from the player toward the target, with each
 * sample snapped to the local ground surface so the arrow trail hugs the terrain.
 *
 * <p>Ground detection is incremental: each sample searches for solid ground (or a
 * fluid surface) in a small vertical window around the previous sample's ground
 * height. This follows hills and descends into valleys smoothly, works in the
 * Nether (no heightmap use, so no snapping to the bedrock roof), and simply
 * carries the previous height across gaps, unloaded chunks, and cliffs.
 */
public final class StraightLinePathProvider implements PathProvider {
    /** How far above the previous ground height to start searching for the next one. */
    private static final int SEARCH_UP = 6;
    /** How far below the previous ground height to keep searching before giving up. */
    private static final int SEARCH_DOWN = 12;

    @Override
    public List<PathNode> computePath(ClientLevel level, Vec3 from, Vec3 to, double maxLength) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 1.0) {
            return List.of(); // standing (nearly) on top of the target
        }

        // Horizontal unit direction of travel; constant for a straight line but
        // stored per node so curved providers can slot in later.
        Vec3 dir = new Vec3(dx / horizontalDistance, 0, dz / horizontalDistance);

        double length = Math.min(horizontalDistance, maxLength);
        int sampleCount = (int) Math.floor(length / SAMPLE_STEP) + 1;

        // Pass 1: ground height per sample, following terrain from the player's feet.
        double[] groundY = new double[sampleCount];
        double previousGround = from.y;
        for (int i = 0; i < sampleCount; i++) {
            double s = i * SAMPLE_STEP;
            double x = from.x + dir.x * s;
            double z = from.z + dir.z * s;
            previousGround = findGround(level, x, z, previousGround);
            groundY[i] = previousGround;
        }

        // Pass 2: light box-blur on the height profile so one-block terrain steps
        // become short ramps instead of arrows visibly popping up/down.
        double[] smoothed = smooth(groundY);

        double hover = NaviCraftConfig.get().arrowHoverHeight;
        List<PathNode> nodes = new ArrayList<>(sampleCount);
        for (int i = 0; i < sampleCount; i++) {
            double s = i * SAMPLE_STEP;
            nodes.add(new PathNode(
                    new Vec3(from.x + dir.x * s, smoothed[i] + hover, from.z + dir.z * s),
                    dir));
        }
        return nodes;
    }

    /**
     * Finds the Y of the walkable surface at (x, z): the top face of the highest
     * block that blocks motion (or a fluid surface, so trails float over water),
     * searched within a window around {@code nearY}. Falls back to {@code nearY}
     * when the chunk is unloaded or the window contains no surface (cliff edge,
     * flying over a gorge) - the trail then keeps its previous altitude.
     */
    private static double findGround(ClientLevel level, double x, double z, double nearY) {
        BlockPos probe = BlockPos.containing(x, nearY, z);
        if (!level.isLoaded(probe)) {
            return nearY;
        }

        int top = Mth.floor(nearY) + SEARCH_UP;
        int bottom = Mth.floor(nearY) - SEARCH_DOWN;
        Integer surface = null;

        for (int y = top; y >= bottom; y--) {
            BlockPos pos = BlockPos.containing(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (isSurface(level, pos, state)) {
                surface = y + 1;
                break;
            }
        }

        if (surface == null) {
            return nearY;
        }

        // If the found surface is buried (the block above it is also solid, e.g. we
        // scanned into a hillside), climb up until there is air to stand in.
        int y = surface;
        while (y < top + SEARCH_UP) {
            BlockPos pos = BlockPos.containing(x, y, z);
            if (level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return y;
            }
            y++;
        }
        return surface;
    }

    /** A block counts as ground if it has any collision box or holds a fluid. */
    private static boolean isSurface(ClientLevel level, BlockPos pos, BlockState state) {
        return !state.getCollisionShape(level, pos).isEmpty() || !state.getFluidState().isEmpty();
    }

    /** Symmetric box blur with window +-2; endpoints average over what exists. */
    private static double[] smooth(double[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double sum = 0;
            int count = 0;
            for (int j = Math.max(0, i - 2); j <= Math.min(values.length - 1, i + 2); j++) {
                sum += values[j];
                count++;
            }
            result[i] = sum / count;
        }
        return result;
    }
}
