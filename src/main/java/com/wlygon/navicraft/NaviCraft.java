package com.wlygon.navicraft;

import com.wlygon.navicraft.command.GpsCommand;
import com.wlygon.navicraft.nav.NavigationManager;
import com.wlygon.navicraft.net.NavTargetPayload;
import com.wlygon.navicraft.waypoint.WaypointStore;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NaviCraft implements ModInitializer {
    public static final String MOD_ID = "navicraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Initializing NaviCraft...");

        PayloadTypeRegistry.clientboundPlay().register(NavTargetPayload.TYPE, NavTargetPayload.STREAM_CODEC);

        CommandRegistrationCallback.EVENT.register(GpsCommand::register);

        ServerTickEvents.END_SERVER_TICK.register(NavigationManager::serverTick);

        // Resume persisted navigation when a player logs back in.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                NavigationManager.onJoin(handler.getPlayer(), server));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            NavigationManager.onDisconnect(handler.getPlayer().getUUID());
            WaypointStore.get().unload(handler.getPlayer().getUUID());
        });

        // Reset all session state when a (integrated or dedicated) server shuts down,
        // so a following singleplayer session starts clean.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            NavigationManager.onServerStopped();
            WaypointStore.get().clearCache();
        });
    }
}
