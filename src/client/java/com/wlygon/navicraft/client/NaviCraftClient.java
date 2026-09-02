package com.wlygon.navicraft.client;

import com.wlygon.navicraft.NaviCraft;
import com.wlygon.navicraft.client.hud.NavHud;
import com.wlygon.navicraft.client.render.ArrowTrailRenderer;
import com.wlygon.navicraft.net.NavTargetPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public class NaviCraftClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        NaviCraftConfig.get(); // load config (and write defaults) up front

        ClientPlayNetworking.registerGlobalReceiver(NavTargetPayload.TYPE,
                (payload, context) -> ClientNavState.onPayload(payload));

        ClientTickEvents.END_CLIENT_TICK.register(ClientNavState::clientTick);

        // Nav targets are session-only; drop everything when leaving a world/server.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientNavState.reset());

        ArrowTrailRenderer.register();
        NavHud.register();

        NaviCraft.LOGGER.info("NaviCraft client initialized");
    }
}
