package com.wlygon.navicraft.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.wlygon.navicraft.client.ClientNavState;
import com.wlygon.navicraft.client.NaviCraftConfig;
import com.wlygon.navicraft.client.path.PathNode;
import com.wlygon.navicraft.client.path.PathProvider;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws the Forza-style trail of scrolling chevrons along the computed path.
 *
 * <p>Geometry goes through {@code RenderTypes.debugQuads()}: untextured
 * position+color quads with translucent blending, depth-tested but not
 * depth-writing, and culling disabled (so each quad is visible from both sides
 * and only needs to be emitted once).
 */
public final class ArrowTrailRenderer {
    /** Arrows closer than this to the player are invisible (they'd block the view). */
    private static final double FADE_IN_START = 2.0;
    /** ...and reach full opacity here. */
    private static final double FADE_IN_END = 6.0;
    /** Arrows fade out over this many blocks at the far render-distance cutoff. */
    private static final double FAR_FADE_LENGTH = 8.0;
    /** Arrows fade out over this many blocks as they scroll off the target end. */
    private static final double END_FADE_LENGTH = 1.5;

    private ArrowTrailRenderer() {
    }

    public static void register() {
        LevelRenderEvents.COLLECT_SUBMITS.register(ArrowTrailRenderer::collectSubmits);
    }

    private static void collectSubmits(LevelRenderContext context) {
        // Volatile snapshot; the client tick thread may swap the path mid-frame.
        List<PathNode> path = ClientNavState.getPath();
        if (path.size() < 2) {
            return;
        }

        NaviCraftConfig config = NaviCraftConfig.get();
        Vec3 cameraPos = context.levelState().cameraRenderState.pos;
        PoseStack poseStack = context.poseStack();

        double pathLength = (path.size() - 1) * PathProvider.SAMPLE_STEP;
        double spacing = config.arrowSpacing;

        // Scroll animation: every arrow sits at (k * spacing + phase) blocks along
        // the path, and phase advances with wall-clock time, so the whole trail
        // flows toward the destination. Modulo keeps the pattern seamless: as one
        // arrow fades off the end, another enters at the start.
        double phase = (System.nanoTime() / 1_000_000_000.0 * config.scrollSpeed) % spacing;

        for (double s = phase; s <= pathLength; s += spacing) {
            float alpha = alphaAt(s, pathLength, config);
            if (alpha <= 0.01f) {
                continue;
            }

            // Interpolate the arrow's position between the two neighboring samples.
            double index = s / PathProvider.SAMPLE_STEP;
            int i0 = Math.min((int) index, path.size() - 2);
            double frac = index - i0;
            PathNode a = path.get(i0);
            PathNode b = path.get(i0 + 1);
            Vec3 pos = Mth.lerp(frac, a.pos(), b.pos());
            Vec3 dir = a.dir();

            // Local frame at the arrow's center, camera-relative (world position
            // minus camera position - the convention for level rendering submits).
            poseStack.pushPose();
            poseStack.translate(pos.x - cameraPos.x, pos.y - cameraPos.y, pos.z - cameraPos.z);

            final float arrowAlpha = alpha;
            context.submitNodeCollector().submitCustomGeometry(poseStack, RenderTypes.debugQuads(),
                    (pose, buffer) -> emitChevron(pose, buffer, dir, config, arrowAlpha));

            poseStack.popPose();
        }
    }

    /**
     * Opacity envelope along the path: invisible right at the player, ramping to
     * full a few blocks out; fading near the render-distance cutoff; and fading
     * over the last blocks before the destination so scrolling arrows dissolve
     * instead of popping out of existence.
     */
    private static float alphaAt(double s, double pathLength, NaviCraftConfig config) {
        double nearFade = Mth.clamp((s - FADE_IN_START) / (FADE_IN_END - FADE_IN_START), 0.0, 1.0);

        double farLimit = Math.min(pathLength, config.maxRenderDistance);
        double farFade = Mth.clamp((farLimit - s) / FAR_FADE_LENGTH, 0.0, 1.0);

        double endFade = Mth.clamp((pathLength - s) / END_FADE_LENGTH, 0.0, 1.0);

        return (float) (nearFade * Math.min(farFade, endFade)) * config.arrowOpacity;
    }

    /**
     * Emits a flat ">"-shaped chevron lying in the horizontal plane, centered at
     * the local origin, pointing along {@code dir}.
     *
     * <p>Basis: {@code f} = forward (travel direction), {@code r} = f rotated 90°
     * clockwise in the XZ plane, i.e. {@code r = (-f.z, 0, f.x)}. The chevron is
     * two parallelogram strokes. Each stroke's leading edge runs from the tip
     * {@code +f*(S/2)} out and back to a wing corner {@code ±r*halfW - f*(S/2)};
     * the trailing edge is the same line pushed back by the stroke thickness
     * {@code -f*T}. The strokes only share the tip edge, so nothing overlaps
     * (overlap would double the translucent color).
     */
    private static void emitChevron(PoseStack.Pose pose, VertexConsumer buffer,
                                    Vec3 dir, NaviCraftConfig config, float alpha) {
        float fx = (float) dir.x;
        float fz = (float) dir.z;
        float rx = -fz;
        float rz = fx;

        float halfW = (float) (config.arrowWidth / 2.0);
        float halfS = (float) (config.arrowLength / 2.0); // half the tip-to-wing sweep
        float thick = (float) (config.arrowLength * 0.5); // stroke thickness, along -f

        float red = config.arrowRed;
        float green = config.arrowGreen;
        float blue = config.arrowBlue;

        // Key points, all at local y = 0 (the trail already floats above ground).
        float tipX = fx * halfS, tipZ = fz * halfS;                    // leading tip
        float backX = -fx * thick, backZ = -fz * thick;                // thickness offset
        float wingRX = rx * halfW - fx * halfS, wingRZ = rz * halfW - fz * halfS;
        float wingLX = -rx * halfW - fx * halfS, wingLZ = -rz * halfW - fz * halfS;

        // Right stroke: tip -> right wing, then the same edge shifted back.
        quad(pose, buffer, red, green, blue, alpha,
                tipX, tipZ,
                wingRX, wingRZ,
                wingRX + backX, wingRZ + backZ,
                tipX + backX, tipZ + backZ);

        // Left stroke, mirrored.
        quad(pose, buffer, red, green, blue, alpha,
                tipX, tipZ,
                tipX + backX, tipZ + backZ,
                wingLX + backX, wingLZ + backZ,
                wingLX, wingLZ);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer,
                             float red, float green, float blue, float alpha,
                             float x0, float z0, float x1, float z1,
                             float x2, float z2, float x3, float z3) {
        buffer.addVertex(pose, x0, 0, z0).setColor(red, green, blue, alpha);
        buffer.addVertex(pose, x1, 0, z1).setColor(red, green, blue, alpha);
        buffer.addVertex(pose, x2, 0, z2).setColor(red, green, blue, alpha);
        buffer.addVertex(pose, x3, 0, z3).setColor(red, green, blue, alpha);
    }
}
