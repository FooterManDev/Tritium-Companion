package io.github.tritium_launcher.tritiumcompanion.fabric;

import io.github.tritium_launcher.tritiumcompanion.Command;
import io.github.tritium_launcher.tritiumcompanion.TCompanion;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class Fabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess, environment) -> Command.register(dispatcher)));
        ServerLifecycleEvents.SERVER_STARTED.register(TCompanion::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(TCompanion::onServerStopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(TCompanion::onServerStopping);
        TCompanion.init();
    }
}
