package com.wlygon.navicraft.nav;

import net.minecraft.world.phys.Vec3;

/**
 * The point navigation is currently steering the player toward - either the
 * final destination itself, or an intermediate portal marker on the way to a
 * destination in another dimension.
 *
 * @param mode        one of the MODE_* constants below
 * @param dimensionId dimension the leg point is in
 * @param pos         the leg point
 * @param label       marker name of the leg point ("" for raw coordinates)
 */
public record NavLeg(byte mode, String dimensionId, Vec3 pos, String label) {
    /** Destination is in the player's dimension; leg == final target. */
    public static final byte MODE_DIRECT = 0;
    /** Destination is in another dimension; leg is the nearest portal marker. */
    public static final byte MODE_PORTAL = 1;
    /** Destination is in another dimension and no portal marker exists there. */
    public static final byte MODE_NO_PORTAL = 2;
}
