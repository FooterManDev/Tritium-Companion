package io.github.footermandev.tritiumcompanion.fabric;

import io.github.footermandev.tritiumcompanion.Common;
import io.github.footermandev.tritiumcompanion.RegistryDumper;
import io.github.footermandev.tritiumcompanion.Warning;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

public final class FabricClient implements ClientModInitializer
{
    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register(((handler, sender, client) -> {
            if(Warning.shouldWarn()) {
                client.execute(() -> {
                    assert client.player != null;
                    client.player.sendSystemMessage(Warning.getWarningMsg());
                });
            }
        }));

        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener()
        {
            @Override
            public ResourceLocation getFabricId() {
                return ResourceLocation.fromNamespaceAndPath(Common.MOD_ID, "resource_dump");
            }

            @Override
            public void onResourceManagerReload(ResourceManager resourceManager) {
                int textures = RegistryDumper.dumpTextures(resourceManager);
                Common.LOGGER.info("Dumped {} Textures", textures);
            }
        });
    }
}
