package com.wlygon.navicraft.waypoint;

import net.minecraft.world.phys.Vec3;

/**
 * A named, per-player saved location. {@code dimension} is the dimension id string
 * (e.g. "minecraft:overworld") so it serializes cleanly to JSON.
 *
 * <p>{@code portal} marks this waypoint as a dimension-crossing point (set via
 * {@code /gps marker ... --portal}); cross-dimension navigation routes through
 * the nearest portal marker in the player's current dimension. Files written
 * before this field existed deserialize with {@code portal == false}.
 */
public record Waypoint(String name, String dimension, double x, double y, double z, boolean portal) {
    public Vec3 pos() {
        return new Vec3(x, y, z);
    }

    public double distanceSqrTo(Vec3 point) {
        double dx = x - point.x;
        double dy = y - point.y;
        double dz = z - point.z;
        return dx * dx + dy * dy + dz * dz;
    }
}
