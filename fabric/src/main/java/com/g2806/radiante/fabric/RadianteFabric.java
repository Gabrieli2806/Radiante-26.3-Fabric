package com.g2806.radiante.fabric;

import com.g2806.radiante.client.RadianteClient;
import com.g2806.radiante.client.RadianteKeys;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class RadianteFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        RadianteClient.init();
        for (KeyMapping key : RadianteKeys.ALL) {
            KeyMappingHelper.registerKeyMapping(key);
        }
        ClientTickEvents.END_CLIENT_TICK.register(RadianteClient::onEndClientTick);
    }
}
