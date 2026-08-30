package io.github.tritium_launcher.tritiumcompanion;

import net.minecraft.server.MinecraftServer;
import java.nio.file.Path;

public interface KubeJSSupport {
    void dump(MinecraftServer server, Path outputDir);

    KubeJSSupport NOOP = (server, outputDir) -> {};
}
