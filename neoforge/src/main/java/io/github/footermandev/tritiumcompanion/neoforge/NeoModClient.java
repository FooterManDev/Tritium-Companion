package io.github.footermandev.tritiumcompanion.neoforge;


import io.github.footermandev.tritiumcompanion.Common;
import io.github.footermandev.tritiumcompanion.RegistryDumper;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import static io.github.footermandev.tritiumcompanion.Common.MOD_ID;

@EventBusSubscriber(modid = MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public class NeoModClient
{
    @SubscribeEvent
    public static void onReload(RegisterClientReloadListenersEvent e) {
        e.registerReloadListener(new SimplePreparableReloadListener()
        {

            @Override
            protected Object prepare(ResourceManager arg, ProfilerFiller arg2) {
                return Unit.INSTANCE;
            }

            @Override
            protected void apply(Object object, ResourceManager mngr, ProfilerFiller arg2) {
                int textures = RegistryDumper.dumpTextures(mngr);
                Common.LOGGER.info("Dumped {} Textures", textures);
            }
        });
    }
}
