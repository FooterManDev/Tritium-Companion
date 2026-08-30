package io.github.tritium_launcher.tritiumcompanion.fabric;

import io.github.tritium_launcher.tritiumcompanion.Warning;
import io.github.tritium_launcher.tritiumcompanion.client.icons.InventoryChangeTracker;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

public final class FabricClient implements ClientModInitializer
{

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> InventoryChangeTracker.tick());

        ClientPlayConnectionEvents.JOIN.register(((handler, sender, client) -> {
            if(Warning.shouldWarn()) {
                client.execute(() -> {
                    assert client.player != null;
                    client.player.sendSystemMessage(Warning.getWarningMsg());
                });
            }
        }));

    }
}
