package io.github.tritium_launcher.tritiumcompanion.neoforge;

import io.github.tritium_launcher.tritiumcompanion.Command;
import io.github.tritium_launcher.tritiumcompanion.Common;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import static io.github.tritium_launcher.tritiumcompanion.Common.MOD_ID;

@Mod(MOD_ID)
public final class Neo
{
    public Neo() {
        NeoForge.EVENT_BUS.addListener(Neo::onCommandRegister);
        NeoForge.EVENT_BUS.addListener(Neo::onServerStarted);
        NeoForge.EVENT_BUS.addListener(Neo::onServerStopping);
        NeoForge.EVENT_BUS.addListener(Neo::onServerStopped);
        Common.init();
    }

    public static void onCommandRegister(RegisterCommandsEvent e) {
        Common.LOGGER.info("Registering command on NeoForge");
        Command.register(e.getDispatcher());
    }

    public static void onServerStarted(ServerStartedEvent e) {
        Common.onServerStarted(e.getServer());
    }

    public static void onServerStopping(ServerStoppingEvent e) {
        Common.onServerStopping(e.getServer());
    }

    public static void onServerStopped(ServerStoppedEvent e) {
        Common.onServerStopping(e.getServer());
    }
}
