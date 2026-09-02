package com.wlygon.navicraft.client.hud;

import com.wlygon.navicraft.NaviCraft;
import com.wlygon.navicraft.client.ClientNavState;
import com.wlygon.navicraft.client.NaviCraftConfig;
import com.wlygon.navicraft.nav.NavLeg;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The v1 HUD: a horizontal bearing bar. A colored caret slides left/right to show
 * how far the target is off the player's current heading (full deflection = 90°
 * or more), with distance and direction text underneath.
 */
public final class NavHud {
    private static final int BAR_WIDTH = 122;
    private static final int BAR_HEIGHT = 14;
    /** Total widget height: bar + gap + one line of text. */
    private static final int WIDGET_HEIGHT = BAR_HEIGHT + 12;

    private static final int BACKGROUND = 0xA010161E;
    private static final int TICK_MAJOR = 0x90FFFFFF;
    private static final int TICK_MINOR = 0x50FFFFFF;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFAAAAAA;

    private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private NavHud() {
    }

    public static void register() {
        HudElementRegistry.addLast(NaviCraft.id("nav_hud"), NavHud::extract);
    }

    private static void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft client = Minecraft.getInstance();
        ClientNavState.Target target = ClientNavState.getTarget();
        if (target == null || client.player == null || client.level == null) {
            return;
        }

        NaviCraftConfig config = NaviCraftConfig.get();
        int x = anchorX(graphics, config);
        int y = anchorY(graphics, config);
        int centerX = x + BAR_WIDTH / 2;

        graphics.fill(x, y, x + BAR_WIDTH, y + BAR_HEIGHT, BACKGROUND);

        if (!ClientNavState.isInTargetDimension(client)) {
            // Bearings are meaningless across dimensions; show where the target is instead.
            // Normally only MODE_NO_PORTAL lands here (a portal leg is always in the
            // player's own dimension), so hint at the fix.
            String dimension = shortDimensionName(target.dimensionId());
            graphics.centeredText(client.font, "target in " + dimension, centerX, y + 3, TEXT_DIM);
            if (target.mode() == NavLeg.MODE_NO_PORTAL) {
                graphics.centeredText(client.font, "no portal marker · set one with --portal",
                        centerX, y + BAR_HEIGHT + 3, TEXT_DIM);
            }
            return;
        }

        Vec3 playerPos = client.player.position();
        double dx = target.pos().x - playerPos.x;
        double dz = target.pos().z - playerPos.z;

        // Minecraft yaw convention: 0° faces +Z (south), increasing clockwise, so
        // the yaw pointing at the target is atan2(-dx, dz). The signed difference
        // from the player's yaw is how far the caret deflects from center.
        double yawToTarget = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = Mth.wrapDegrees(yawToTarget - client.player.getYRot());

        // Tick marks: center line plus ±45° / ±90° reference ticks.
        int halfTravel = BAR_WIDTH / 2 - 5;
        graphics.fill(centerX, y + 2, centerX + 1, y + BAR_HEIGHT - 2, TICK_MAJOR);
        for (int i = -2; i <= 2; i++) {
            if (i == 0) {
                continue;
            }
            int tickX = centerX + i * halfTravel / 2;
            graphics.fill(tickX, y + 4, tickX + 1, y + BAR_HEIGHT - 4, TICK_MINOR);
        }

        // The caret: a small upward-pointing step pyramid at the deflection offset.
        int caretX = centerX + (int) Math.round(Mth.clamp(relative, -90, 90) / 90.0 * halfTravel);
        int caretColor = ARGB.colorFromFloat(1.0f, config.arrowRed, config.arrowGreen, config.arrowBlue);
        int caretTop = y + 3;
        graphics.fill(caretX, caretTop, caretX + 1, caretTop + 2, caretColor);
        graphics.fill(caretX - 1, caretTop + 2, caretX + 2, caretTop + 4, caretColor);
        graphics.fill(caretX - 2, caretTop + 4, caretX + 3, caretTop + 8, caretColor);

        // "142m NW · home", or "142m NW · portal (then home)" when routing through
        // a portal marker toward another dimension.
        double distance = playerPos.distanceTo(target.pos());
        String text = formatDistance(distance) + " " + cardinal(yawToTarget);
        if (target.mode() == NavLeg.MODE_PORTAL) {
            String portalName = target.label().isEmpty() ? "portal" : target.label();
            String destination = target.finalLabel().isEmpty() ? "destination" : target.finalLabel();
            text += " · " + portalName + " (then " + destination + ")";
        } else if (!target.label().isEmpty()) {
            text += " · " + target.label();
        }
        graphics.centeredText(client.font, text, centerX, y + BAR_HEIGHT + 3, TEXT_COLOR);
    }

    private static int anchorX(GuiGraphicsExtractor graphics, NaviCraftConfig config) {
        return switch (config.hudPosition) {
            case "bottom-left", "top-left" -> config.hudMarginX;
            case "bottom-center", "top-center" -> (graphics.guiWidth() - BAR_WIDTH) / 2;
            default -> graphics.guiWidth() - BAR_WIDTH - config.hudMarginX;
        };
    }

    private static int anchorY(GuiGraphicsExtractor graphics, NaviCraftConfig config) {
        return switch (config.hudPosition) {
            case "top-left", "top-right", "top-center" -> config.hudMarginY;
            default -> graphics.guiHeight() - WIDGET_HEIGHT - config.hudMarginY;
        };
    }

    private static String formatDistance(double blocks) {
        return blocks < 1000
                ? String.format("%.0fm", blocks)
                : String.format("%.1fkm", blocks / 1000.0);
    }

    /** Maps an absolute Minecraft yaw to a compass point (yaw ±180° = north). */
    private static String cardinal(double yaw) {
        int index = Math.floorMod(Math.round((yaw + 180.0) / 45.0), 8);
        return CARDINALS[(int) index];
    }

    /** "minecraft:the_nether" -> "the nether"; other namespaces keep their prefix. */
    private static String shortDimensionName(String dimensionId) {
        String name = dimensionId.startsWith("minecraft:") ? dimensionId.substring(10) : dimensionId;
        return name.replace('_', ' ');
    }
}
