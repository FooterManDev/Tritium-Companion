package io.github.tritium_launcher.tritiumcompanion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import log.TLogFileDescriptor;
import log.TLogFileRegistry;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.NonNull;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persists registered {@link TLogFileDescriptor}s to {@code .tr/log_files.json} inside the
 * game directory.
 *
 * <p>The launcher reads this file whenever a project is open so log toggle buttons appear
 * as soon as a mod has registered descriptors.</p>
 */
public final class LogFilePersistence
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LogFilePersistence() {}

    /**
     * Writes the current set of registered log file descriptors to
     * {@code .tr/log_files.json} in the game directory.
     */
    public static void write() {
        Path gameDir = resolveGameDir();
        write(gameDir);
    }

    /**
     * Writes the current set of registered log file descriptors to
     * {@code .tr/log_files.json} under [gameDir].
     */
    public static void write(Path dir) {
        try {
            Path output = dir.resolve(".tr/log_files.json");
            Files.createDirectories(output.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("type", "log_files");
            JsonArray files = new JsonArray();
            for (TLogFileDescriptor descriptor : TLogFileRegistry.getDescriptors()) {
                JsonObject entry = createEntry(descriptor);
                files.add(entry);
            }
            root.add("files", files);
            try (var writer = Files.newBufferedWriter(output)) {
                GSON.toJson(root, writer);
            }
            TCompanion.LOGGER.debug("Persisted {} log file descriptors to {}", files.size(), output);
        } catch (Exception e) {
            TCompanion.LOGGER.warn("Failed to persist log file descriptors", e);
        }
    }

    private static @NonNull JsonObject createEntry(TLogFileDescriptor descriptor) {
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
        return entry;
    }

    private static Path resolveGameDir() {
        Minecraft mc = Minecraft.getInstance();
        return mc.gameDirectory.toPath();
    }
}
