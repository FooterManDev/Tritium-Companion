package io.github.tritium_launcher.tritiumcompanion.neoforge;

import io.github.tritium_launcher.tritiumcompanion.Warning;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import static io.github.tritium_launcher.tritiumcompanion.Common.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT)
public class NeoClient
{

    @SubscribeEvent
    public static void onClientJoin(ClientPlayerNetworkEvent.LoggingIn e) {
        if(Warning.shouldWarn()) {
            Minecraft.getInstance().execute(() -> {
                assert Minecraft.getInstance().player != null;
                Minecraft.getInstance().player.sendSystemMessage(Warning.getWarningMsg());
            });
        }
    }


}
