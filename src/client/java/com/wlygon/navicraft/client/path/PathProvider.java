package com.wlygon.navicraft.client.path;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Strategy for turning "player is here, target is there" into a renderable trail.
 *
 * <p>Implementations return a polyline of nodes spaced roughly {@link #SAMPLE_STEP}
 * blocks apart, starting near {@code from} and heading toward {@code to}, cut off
 * after {@code maxLength} blocks. The renderer interpolates between nodes, so a
 * denser or curved node list (e.g. from road-following or A*) plugs in without
 * any renderer changes.
 */
public interface PathProvider {
    /** Spacing between consecutive nodes, in blocks. Implementations should honor this. */
    double SAMPLE_STEP = 1.0;

    List<PathNode> computePath(ClientLevel level, Vec3 from, Vec3 to, double maxLength);
}
