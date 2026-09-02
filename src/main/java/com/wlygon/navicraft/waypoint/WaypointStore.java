package com.wlygon.navicraft.waypoint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.wlygon.navicraft.NaviCraft;
import com.wlygon.navicraft.nav.NavTarget;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player persistence. Each player gets one JSON file at
 * {@code <world>/data/navicraft/waypoints/<uuid>.json}, loaded lazily and written
 * through on every change (files are tiny, so no dirty-tracking is needed).
 *
 * <p>File schema (v2): {@code {"markers": [...], "activeNav": {...}|null}}.
 * v1 files were a bare JSON array of markers and are migrated on first read.
 *
 * <p>All access happens on the server thread (commands, connection events, tick),
 * so no synchronization is required.
 */
public final class WaypointStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LEGACY_LIST_TYPE = new TypeToken<List<Waypoint>>() { }.getType();

    private static final WaypointStore INSTANCE = new WaypointStore();

    /** On-disk shape of a persisted active nav target. */
    private record SavedNav(String dimension, double x, double y, double z, String label) {
        static SavedNav of(NavTarget target) {
            return new SavedNav(target.dimensionId(), target.pos().x, target.pos().y, target.pos().z, target.label());
        }

        NavTarget toTarget() {
            return new NavTarget(dimension, new Vec3(x, y, z), label == null ? "" : label);
        }
    }

    /** On-disk file shape. */
    private static final class PlayerFile {
        List<Waypoint> markers = new ArrayList<>();
        SavedNav activeNav;
    }

    /** In-memory state; marker keys are lowercased names, values keep casing. */
    private static final class PlayerData {
        final Map<String, Waypoint> markers = new LinkedHashMap<>();
        SavedNav activeNav;
    }

    private final Map<UUID, PlayerData> cache = new HashMap<>();

    private WaypointStore() {
    }

    public static WaypointStore get() {
        return INSTANCE;
    }

    public List<Waypoint> list(MinecraftServer server, UUID player) {
        return List.copyOf(load(server, player).markers.values());
    }

    public Waypoint find(MinecraftServer server, UUID player, String name) {
        return load(server, player).markers.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * The player's nearest portal-flagged marker in the given dimension, or null.
     * Used to pick the intermediate leg for cross-dimension navigation.
     */
    public Waypoint nearestPortal(MinecraftServer server, UUID player, String dimensionId, Vec3 from) {
        Waypoint nearest = null;
        double nearestDistSqr = Double.MAX_VALUE;
        for (Waypoint waypoint : load(server, player).markers.values()) {
            if (waypoint.portal() && waypoint.dimension().equals(dimensionId)) {
                double distSqr = waypoint.distanceSqrTo(from);
                if (distSqr < nearestDistSqr) {
                    nearestDistSqr = distSqr;
                    nearest = waypoint;
                }
            }
        }
        return nearest;
    }

    /** @return true if an existing waypoint with the same name was replaced */
    public boolean put(MinecraftServer server, UUID player, Waypoint waypoint) {
        PlayerData data = load(server, player);
        boolean replaced = data.markers.put(waypoint.name().toLowerCase(Locale.ROOT), waypoint) != null;
        save(server, player, data);
        return replaced;
    }

    public boolean remove(MinecraftServer server, UUID player, String name) {
        PlayerData data = load(server, player);
        boolean removed = data.markers.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            save(server, player, data);
        }
        return removed;
    }

    /** The persisted active nav target, or null when none was saved. */
    public NavTarget getActiveNav(MinecraftServer server, UUID player) {
        SavedNav saved = load(server, player).activeNav;
        return saved == null || saved.dimension() == null ? null : saved.toTarget();
    }

    /** Persist (or, with null, clear) the player's active nav target. */
    public void setActiveNav(MinecraftServer server, UUID player, NavTarget target) {
        PlayerData data = load(server, player);
        data.activeNav = target == null ? null : SavedNav.of(target);
        save(server, player, data);
    }

    /** Drop the cache entry when a player disconnects; it reloads on next use. */
    public void unload(UUID player) {
        cache.remove(player);
    }

    public void clearCache() {
        cache.clear();
    }

    private PlayerData load(MinecraftServer server, UUID player) {
        return cache.computeIfAbsent(player, uuid -> {
            PlayerData data = new PlayerData();
            Path file = fileFor(server, uuid);
            if (!Files.exists(file)) {
                return data;
            }
            try {
                String content = Files.readString(file);
                if (content.stripLeading().startsWith("[")) {
                    // v1 file: bare array of markers, no activeNav.
                    List<Waypoint> loaded = GSON.fromJson(content, LEGACY_LIST_TYPE);
                    addMarkers(data, loaded);
                } else {
                    PlayerFile parsed = GSON.fromJson(content, PlayerFile.class);
                    if (parsed != null) {
                        addMarkers(data, parsed.markers);
                        data.activeNav = parsed.activeNav;
                    }
                }
            } catch (Exception e) {
                NaviCraft.LOGGER.error("Failed to read waypoints for {} from {}", uuid, file, e);
            }
            return data;
        });
    }

    private static void addMarkers(PlayerData data, List<Waypoint> markers) {
        if (markers == null) {
            return;
        }
        for (Waypoint waypoint : markers) {
            if (waypoint != null && waypoint.name() != null && waypoint.dimension() != null) {
                data.markers.put(waypoint.name().toLowerCase(Locale.ROOT), waypoint);
            }
        }
    }

    private void save(MinecraftServer server, UUID player, PlayerData data) {
        Path file = fileFor(server, player);
        PlayerFile out = new PlayerFile();
        out.markers = new ArrayList<>(data.markers.values());
        out.activeNav = data.activeNav;
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(out, writer);
            }
        } catch (IOException e) {
            NaviCraft.LOGGER.error("Failed to save waypoints for {} to {}", player, file, e);
        }
    }

    private static Path fileFor(MinecraftServer server, UUID player) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data").resolve(NaviCraft.MOD_ID).resolve("waypoints")
                .resolve(player + ".json");
    }
}
