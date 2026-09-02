package com.wlygon.navicraft.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.wlygon.navicraft.NaviCraft;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side config, stored as JSON at {@code config/navicraft.json}.
 * Loaded once at client init; edit the file and restart (or rejoin) to apply.
 */
public final class NaviCraftConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static NaviCraftConfig instance;

    /** Arrow tint as #RRGGBB hex. */
    public String arrowColor = "#3FD9FF";
    /** Peak arrow opacity, 0..1. */
    public float arrowOpacity = 0.85f;
    /** Distance in blocks between consecutive arrows along the path. */
    public double arrowSpacing = 3.0;
    /** How high above the detected ground surface arrows float. */
    public double arrowHoverHeight = 1.6;
    /** Full width of a chevron, in blocks. */
    public double arrowWidth = 1.1;
    /** Tip-to-tail length of a chevron, in blocks. */
    public double arrowLength = 0.9;
    /** Arrows render at most this far from the player, in blocks. */
    public double maxRenderDistance = 64.0;
    /** Forward scroll speed of the arrow animation, in blocks per second. */
    public double scrollSpeed = 4.0;
    /** One of: bottom-left, bottom-right, bottom-center, top-left, top-right, top-center. */
    public String hudPosition = "bottom-right";
    public int hudMarginX = 10;
    public int hudMarginY = 10;

    // Parsed from arrowColor at load time; not serialized.
    public transient float arrowRed = 0.25f;
    public transient float arrowGreen = 0.85f;
    public transient float arrowBlue = 1.0f;

    public static NaviCraftConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static NaviCraftConfig load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("navicraft.json");
        NaviCraftConfig config = null;

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                config = GSON.fromJson(reader, NaviCraftConfig.class);
            } catch (Exception e) {
                NaviCraft.LOGGER.error("Failed to read {}, using defaults", file, e);
            }
        }
        if (config == null) {
            config = new NaviCraftConfig();
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(config, writer);
            } catch (Exception e) {
                NaviCraft.LOGGER.error("Failed to write default config to {}", file, e);
            }
        }

        config.parseColor();
        config.sanitize();
        return config;
    }

    private void parseColor() {
        try {
            int rgb = Integer.parseInt(arrowColor.replace("#", ""), 16);
            arrowRed = ((rgb >> 16) & 0xFF) / 255.0f;
            arrowGreen = ((rgb >> 8) & 0xFF) / 255.0f;
            arrowBlue = (rgb & 0xFF) / 255.0f;
        } catch (NumberFormatException e) {
            NaviCraft.LOGGER.warn("Invalid arrowColor \"{}\", using default", arrowColor);
        }
    }

    private void sanitize() {
        arrowOpacity = Math.clamp(arrowOpacity, 0.05f, 1.0f);
        arrowSpacing = Math.clamp(arrowSpacing, 1.0, 32.0);
        maxRenderDistance = Math.clamp(maxRenderDistance, 16.0, 256.0);
        scrollSpeed = Math.clamp(scrollSpeed, 0.0, 32.0);
    }
}
