package com.wlygon.navicraft.nav;

import net.minecraft.world.phys.Vec3;

/**
 * An active navigation destination. {@code label} is the marker name when set via
 * {@code /gps goto}, or empty for raw-coordinate targets.
 */
public record NavTarget(String dimensionId, Vec3 pos, String label) {
}
