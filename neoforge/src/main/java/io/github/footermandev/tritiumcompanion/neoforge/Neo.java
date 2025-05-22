package io.github.footermandev.tritiumcompanion.neoforge;

import io.github.footermandev.tritiumcompanion.Command;
import io.github.footermandev.tritiumcompanion.Common;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import static io.github.footermandev.tritiumcompanion.Common.MOD_ID;

@Mod(MOD_ID)
public final class Neo
{
    public Neo() {
        NeoForge.EVENT_BUS.addListener(Neo::onCommandRegister);
        Common.init();
    }

    @SubscribeEvent
    public static void onCommandRegister(RegisterCommandsEvent e) {
        Common.LOGGER.info("Registering command on NeoForge");
        Command.register(e.getDispatcher());
    }
}
