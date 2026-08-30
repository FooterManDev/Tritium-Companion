package io.github.tritium_launcher.tritiumcompanion;

import net.minecraft.server.MinecraftServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import log.TLogFileDescriptor;
import log.TLogFileRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

public final class TCompanion
{
    public static final String MOD_ID = "tritiumcompanion";
    public static final Logger LOGGER = LoggerFactory.getLogger(TCompanion.class);
    public static KubeJSSupport KUBE_JS = KubeJSSupport.NOOP;
    public static FluidTintResolver FLUID_TINT_RESOLVER = FluidTintResolver.NOOP;
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);

    public static void init() {
        if(!INITIALIZED.compareAndSet(false, true)) return;
        TLogFileRegistry.setChangeListener(LogFilePersistence::write);
        TritiumBuiltinApi.registerAll();
        CompanionSocketBridge.init();
    }

    public static void onServerStarted(MinecraftServer server) {
        CompanionSocketBridge.setActiveServer(server);
        LogFilePersistence.write();
        JsonObject msg = new JsonObject();
        msg.addProperty("action", "server_started");
        CompanionSocketBridge.broadcast(msg);
        broadcastLogFiles();
    }

    private static void broadcastLogFiles() {
        JsonObject msg = new JsonObject();
        msg.addProperty("action", "log_files");
        JsonObject data = new JsonObject();
        JsonArray files = new JsonArray();
        for (TLogFileDescriptor descriptor : TLogFileRegistry.getDescriptors()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", descriptor.getId());
            entry.addProperty("displayName", descriptor.getDisplayName());
            entry.addProperty("color", descriptor.getColor());
            String iconTexture = descriptor.getIconTexture();
            if (iconTexture != null) {
                entry.addProperty("iconTexture", iconTexture);
            }
            entry.addProperty("path", descriptor.getPath());
            JsonArray levels = new JsonArray();
            for (String level : descriptor.getLevels()) {
                levels.add(level);
            }
            entry.add("levels", levels);
            entry.addProperty("modId", descriptor.getModId());
            files.add(entry);
        }
        data.add("files", files);
        msg.add("data", data);
        CompanionSocketBridge.broadcast(msg);
    }

    public static void onServerStopping(MinecraftServer server) {
        CompanionSocketBridge.clearActiveServer(server);
        JsonObject msg = new JsonObject();
        msg.addProperty("action", "server_stopped");
        CompanionSocketBridge.broadcast(msg);
    }
}
