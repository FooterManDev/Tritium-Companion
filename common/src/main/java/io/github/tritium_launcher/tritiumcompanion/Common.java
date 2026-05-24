package io.github.tritium_launcher.tritiumcompanion;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class Common
{
    public static final String MOD_ID = "tritiumcompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(Common.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static void init() {
        if(!INITIALIZED.compareAndSet(false, true)) return;
        TritiumBuiltinApi.registerAll();
        CompanionSocketBridge.init();
    }

    public static void onServerStarted(MinecraftServer server) {
        CompanionSocketBridge.setActiveServer(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        CompanionSocketBridge.clearActiveServer(server);
    }
}
