package io.github.tritium_launcher.tritiumcompanion.fabric;

import io.github.tritium_launcher.tritiumcompanion.Command;
import io.github.tritium_launcher.tritiumcompanion.Common;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class Fabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess, environment) -> Command.register(dispatcher)));
        ServerLifecycleEvents.SERVER_STARTED.register(Common::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(Common::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(Common::onServerStopping);
        Common.init();
    }
}
