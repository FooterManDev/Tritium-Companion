package io.github.footermandev.tritiumcompanion.fabric;

import io.github.footermandev.tritiumcompanion.Command;
import net.fabricmc.api.ModInitializer;

import io.github.footermandev.tritiumcompanion.Common;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public final class Fabric implements ModInitializer {
    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(((dispatcher, registryAccess, environment) -> {
            Command.register(dispatcher);
        }));
        Common.init();
    }
}
