package com.wlygon.navicraft.client.path;

import net.minecraft.world.phys.Vec3;

/**
 * One sample point along a computed navigation path.
 *
 * @param pos world-space position an arrow at this point would occupy
 *            (already lifted above the ground by the hover height)
 * @param dir horizontal unit vector the arrow should point along; stored per node
 *            so future path providers (roads, A*) can curve the trail
 */
public record PathNode(Vec3 pos, Vec3 dir) {
}
