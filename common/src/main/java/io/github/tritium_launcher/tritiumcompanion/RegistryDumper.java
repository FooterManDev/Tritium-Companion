package io.github.tritium_launcher.tritiumcompanion;

import com.google.gson.*;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.tritium_launcher.tritiumcompanion.client.icons.RegistryIconRenderer;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.crafting.*;

import org.jspecify.annotations.NonNull;
import recipe.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

public class RegistryDumper
{
    private static final Gson PRETTY_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Gson GSON = new GsonBuilder()
            .create();
    private static final Set<ResourceLocation> GENERIC_ITEM_MODEL_PARENTS = Set.of(
            ResourceLocation.withDefaultNamespace("item/generated"),
            ResourceLocation.withDefaultNamespace("item/handheld")
    );
    private static final int SCHEMA_VERSION = 2;
    private static final DateTimeFormatter SNAPSHOT_ID_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    private static final int SNAPSHOT_RETENTION_COUNT = 5;

    static long calculateDirSize(Path dir) {
        AtomicLong size = new AtomicLong(0);
        try(Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            size.addAndGet(Files.size(path));
                        } catch (IOException e) {
                            TCompanion.LOGGER.warn("Failed to get size of file: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            TCompanion.LOGGER.error("Failed to calculate directory size", e);
        }
        return size.get();
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "B";
        return String.format("%.2f %s", bytes / Math.pow(1024, exp), pre);
    }

    static String formatElapsedTime(long millis) {
        long seconds = millis / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds % 60);
        } else {
            return String.format("%.3fs", millis / 1000.0);
        }
    }

    static String formatElapsedTimeMs(long millis) {
        long minutes = millis / 60_000;
        long seconds = (millis % 60_000) / 1000;
        long ms = millis % 1000;
        return String.format("%dm %ds %dms", minutes, seconds, ms);
    }

    public static void printDumpSummary(Path snapshotPath, long startTime, int objectCount) {
        long elapsedTime = System.currentTimeMillis() - startTime;
        long directorySize = calculateDirSize(snapshotPath);

        TCompanion.LOGGER.info("=");
        TCompanion.LOGGER.info("Completed Registry Dump");
        TCompanion.LOGGER.info("=");
        TCompanion.LOGGER.info("Time Elapsed: {}", formatElapsedTime(elapsedTime));
        TCompanion.LOGGER.info("Object Count: {}", objectCount);
        TCompanion.LOGGER.info("Total Size: {}", formatBytes(directorySize));
        TCompanion.LOGGER.info("Output Path: {}", snapshotPath.toAbsolutePath());
        TCompanion.LOGGER.info("=");
    }

    public static DumpSession beginDump(MinecraftServer server) throws IOException {
        return beginDump(server, DumpScope.FULL);
    }

    public static DumpSession beginDump(MinecraftServer server, DumpScope scope) throws IOException {
        Path root = server.getFile("registryObjs").toAbsolutePath();
        Path snapshots = root.resolve("snapshots");
        Files.createDirectories(snapshots);

        String createdAt = Instant.now().toString();
        String snapshotId = SNAPSHOT_ID_FORMAT.format(Instant.now()) + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path tempDir = snapshots.resolve(".tmp-" + snapshotId);
        Path finalDir = snapshots.resolve(snapshotId);

        deleteIfExists(tempDir);
        Files.createDirectories(tempDir);

        if(!scope.isFull()) {
            seedSnapshotFromCurrent(root, tempDir);
        }

        DumpManifest manifest = new DumpManifest(snapshotId, createdAt);
        return new DumpSession(root, tempDir, finalDir, manifest, scope);
    }

    private static void seedSnapshotFromCurrent(Path root, Path tempDir) throws IOException {
        Path latest = root.resolve("latest.json");
        if(!Files.exists(latest)) return;

        JsonObject latestJson;
        try {
            latestJson = JsonParser.parseString(Files.readString(latest, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            TCompanion.LOGGER.warn("[Scoped] could not parse latest.json, starting from empty seed", e);
            return;
        }
        if(!latestJson.has("path")) return;

        Path source = root.resolve(latestJson.get("path").getAsString()).normalize();
        if(!Files.isDirectory(source)) return;

        long copied = 0;
        try (Stream<Path> walk = Files.walk(source)) {
            copied = walk.filter(Files::isRegularFile)
                    .map(src -> {
                        try {
                            Path dest = tempDir.resolve(source.relativize(src));
                            Files.createDirectories(dest.getParent());
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                            return 1L;
                        } catch (IOException e) {
                            TCompanion.LOGGER.warn("[Scoped] failed copying {} during snapshot seed", src, e);
                            return 0L;
                        }
                    })
                    .reduce(0L, Long::sum);
        }
        TCompanion.LOGGER.info("[Scoped] seeded {} files into new snapshot from {}; scoped dumpers now overwrite their slice", copied, source);
    }

    private static String sectionIdFromPath(Path root, Path file, int fixedSegments) {
        Path rel = root.relativize(file);
        String[] segs = rel.toString().split("/");
        if (segs.length < fixedSegments + 2) return null;
        StringBuilder ns = new StringBuilder(segs[fixedSegments]);
        String last = segs[segs.length - 1];
        if (last.endsWith(".json")) last = last.substring(0, last.length() - ".json".length());
        for (int i = fixedSegments + 1; i < segs.length - 1; i++) {
            ns.append(':');
            ns.append(segs[i]);
        }
        ns.append(':').append(last);
        return ns.toString();
    }

    private static Set<String> readPriorDeletionCandidates(DumpSession session, String kind) {
        Set<String> ids = new HashSet<>();
        Path prior = session.tempDir.resolve("manifest.json");
        if (!Files.exists(prior)) return ids;
        try (Reader reader = Files.newBufferedReader(prior, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            if (obj.has("deletedCandidates") && obj.get("deletedCandidates").isJsonArray()) {
                for (JsonElement el : obj.getAsJsonArray("deletedCandidates")) {
                    JsonObject c = el.getAsJsonObject();
                    if (kind.equals(c.get("kind").getAsString())) {
                        ids.add(c.get("id").getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            TCompanion.LOGGER.warn("[Scoped] failed reading prior deletion candidates: {}", e.toString());
        }
        return ids;
    }

    private static void emitSectionDeletions(DumpSession session, String dataSection, String kind, int fixedSegments) {
        Path dir = session.tempDir.resolve(dataSection);
        if (!Files.isDirectory(dir)) return;

        Set<String> writtenIds = new HashSet<>();
        for (DumpedFile f : session.manifest.files) {
            if (kind.equals(f.kind())) writtenIds.add(f.id());
        }

        Set<String> priorCandidates = readPriorDeletionCandidates(session, kind);

        Map<Path, String> absent = new HashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> {
                        String id = sectionIdFromPath(dir, p, fixedSegments);
                        if (id == null) return;
                        ResourceLocation rl = ResourceLocation.tryParse(id);
                        if (rl == null || !session.scope.matches(rl)) return;
                        if (!writtenIds.contains(id)) absent.put(p, id);
                    });
        } catch (IOException e) {
            TCompanion.LOGGER.warn("[Scoped] failed walking {} for deletion comparison", dir, e);
            return;
        }

        for (Map.Entry<Path, String> e : absent.entrySet()) {
            String id = e.getValue();
            try {
                if (priorCandidates.contains(id)) {
                    session.manifest.deleted.add(new DumpedDelete(kind, id));
                    Files.deleteIfExists(e.getKey());
                    TCompanion.LOGGER.info("[Scoped] tombstone {} {} (confirmed)", kind, id);
                } else {
                    session.manifest.deletedCandidates.add(new DumpedDelete(kind, id));
                    TCompanion.LOGGER.info("[Scoped] deletion candidate {} {}", kind, id);
                }
            } catch (IOException ex) {
                TCompanion.LOGGER.warn("[Scoped] failed processing {} {}", kind, id, ex);
            }
        }
    }

    public static Path finalizeDump(DumpSession session) throws IOException {
        return finalizeDump(session, true);
    }

    public static Path finalizeDump(DumpSession session, boolean publishLatest) throws IOException {
        writeManifest(session, false);
        moveDirectory(session.tempDir, session.finalDir);
        writeManifestAt(session.finalDir.resolve("manifest.json"), session, true);
        if(publishLatest) {
            writeLatestPointer(session);
            pruneOldSnapshots(session.rootDir);
            broadcastCompletion(session);
        }
        return session.finalDir;
    }

    private static void broadcastCompletion(DumpSession session) {
        JsonObject msg = new JsonObject();
        msg.addProperty("action", "dump_complete");
        JsonObject data = new JsonObject();
        data.addProperty("snapshotId", session.manifest.snapshotId);
        data.addProperty("createdAt", session.manifest.createdAt);
        data.addProperty("path", normalizePath(session.rootDir.relativize(session.finalDir)));
        data.addProperty("complete", true);

        long atlasPageCount = session.manifest.files.stream()
                .filter(f -> "icon_atlas".equals(f.kind()))
                .count();
        data.addProperty("atlasPageCount", atlasPageCount);

        msg.add("data", data);
        CompanionSocketBridge.broadcast(msg);
    }

    public static void abandonDump(DumpSession session) {
        try {
            deleteIfExists(session.tempDir);
        } catch (IOException e) {
            TCompanion.LOGGER.warn("Failed cleaning temporary dump directory {}", session.tempDir, e);
        }
    }

    public static <T> int dumpRegistry(
            MinecraftServer server,
            DumpSession session,
            ResourceKey<? extends Registry<?>> registryKey,
            Codec<T> codec,
            String registryType
    ) {
        RegistryAccess access = server.registryAccess();
        Optional<? extends Registry<Object>> optionalRegistry = access.registry(registryKey);
        if(optionalRegistry.isEmpty()) {
            TCompanion.LOGGER.warn("Skipping missing registry: {}", registryKey.location());
            return 0;
        }

        var registry = optionalRegistry.get();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        registry.holders().forEach(holder -> {
            Optional<ResourceKey<Object>> keyOpt = holder.unwrapKey();
            if(keyOpt.isEmpty()) return;

            ResourceKey<Object> rk = keyOpt.get();
            ResourceLocation id = rk.location();
            Object value = holder.value();

            if(!session.scope.matches(id)) return;

            try {
                @SuppressWarnings("unchecked")
                DataResult<JsonElement> dr = ((Codec<Object>) codec).encodeStart(ops, value);
                JsonElement json = dr.getOrThrow(err ->
                        new IllegalStateException("Codec error for " + id + ": " + err)
                );

                Path rel = Path.of("data", "registry", registryType, id.getNamespace(), id.getPath() + ".json");
                writeJson(session, rel, json, "registry_entry", registryType, id.toString());
                count.incrementAndGet();
            } catch (IOException e) {
                TCompanion.LOGGER.error("I/O error writing {} {}:", registryType, id, e);
            } catch (RuntimeException e) {
                TCompanion.LOGGER.error("Failed dumping {} {}:", registryType, id, e);
            }
        });

        session.manifest.sectionCounts.merge("registry:" + registryType, count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task registry/{}: {} objects in {}", registryType, count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        return count.get();
    }

    public static int dumpTags(MinecraftServer server, DumpSession session) {
        RegistryAccess access = server.registryAccess();
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        access.registries().forEach(entry -> {
            ResourceKey<? extends Registry<?>> registryKey = entry.key();
            Registry<?> registry = entry.value();

            HolderLookup<?> lookup = registry.asLookup();
            List<? extends HolderSet.Named<?>> tags = lookup.listTags().toList();
            if(tags.isEmpty()) return;

            String registryType = registryKey.location().getPath();

            tags.forEach(named -> {
                ResourceLocation tagId = named.key().location();

                if(!session.scope.matches(tagId)) return;

                try {
                    JsonObject json = new JsonObject();
                    json.addProperty("replace", false);

                    JsonArray values = new JsonArray();
                    named.forEach(holder -> holder.unwrapKey().ifPresent(k -> values.add(k.location().toString())));
                    json.add("values", values);

                    Path rel = Path.of("data", "tags", registryType, tagId.getNamespace(), tagId.getPath() + ".json");
                    writeJson(session, rel, json, "tag", registryType, tagId.toString());
                    count.incrementAndGet();
                } catch (IOException e) {
                    TCompanion.LOGGER.error("Failed dumping tag {}:{}", registryType, tagId, e);
                }
            });
        });

        session.manifest.sectionCounts.merge("tags", count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task tags: {} objects in {}", count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        emitSectionDeletions(session, "data/tags", "tag", 3);
        return count.get();
    }

    public static int dumpRecipes(MinecraftServer server, DumpSession session) {
        RecipeManager mngr = server.getRecipeManager();
        Registry<RecipeType<?>> recipeTypeRegistry = server.registryAccess().registryOrThrow(Registries.RECIPE_TYPE);
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        mngr.getRecipes().forEach(holder -> {
            ResourceLocation id = holder.id();
            Recipe<?> recipe = holder.value();

            if(!session.scope.matches(id)) return;
            if(recipe instanceof SmithingTrimRecipe) return;

            try {
                DataResult<JsonElement> dr = Recipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe);
                JsonElement sourceJson = dr.getOrThrow(err ->
                        new IllegalStateException("Failed to encode recipe " + id + ": " + err));

                String recipeTypeId = resolveRecipeTypeId(recipeTypeRegistry, recipe);
                JsonObject json = new JsonObject();
                json.addProperty("id", id.toString());
                json.addProperty("recipeType", recipeTypeId);
                recipe.getGroup();
                if (!recipe.getGroup().isBlank()) {
                    json.addProperty("group", recipe.getGroup());
                }
                json.add("sourceJson", sourceJson);
                json.add("display", buildRecipeDisplayJson(server, recipe, recipeTypeId));

                Path rel = Path.of("data", "recipes", id.getNamespace(), id.getPath() + ".json");
                writeJson(session, rel, json, "recipe", "recipe", id.toString());
                count.incrementAndGet();
            } catch (IOException e) {
                TCompanion.LOGGER.error("I/O error writing recipe {}:", id, e);
            } catch (RuntimeException e) {
                TCompanion.LOGGER.error("Failed dumping recipe {}:", id, e);
            }
        });

        session.manifest.sectionCounts.merge("recipes", count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task recipes: {} objects in {}", count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        emitSectionDeletions(session, "data/recipes", "recipe", 2);
        return count.get();
    }

    public static int dumpTextures(DumpSession session) {
        return dumpTextures(session, Minecraft.getInstance().getResourceManager());
    }

    public static int dumpTextures(DumpSession session, ResourceManager mngr) {
        Map<ResourceLocation, Resource> resources = mngr.listResources("textures", path -> path.getPath().endsWith(".png"));
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        resources.forEach((id, resource) -> {
            try (InputStream in = resource.open()) {
                String relativePath = id.getPath();
                if(relativePath.startsWith("textures/")) {
                    relativePath = relativePath.substring("textures/".length());
                }

                if(id.getNamespace().equals("realms")) return;
                if(!session.scope.matches(id)) return;

                Path path = Path.of("assets", "textures", id.getNamespace());
                Path rel = path.resolve(relativePath);
                copyResource(session, rel, in, "asset", "texture", id.toString());
                count.getAndIncrement();

                ResourceLocation mcmetaId = ResourceLocation.fromNamespaceAndPath(
                        id.getNamespace(), id.getPath() + ".mcmeta");
                try {
                    Optional<Resource> mcmetaOpt = mngr.getResource(mcmetaId);
                    if (mcmetaOpt.isPresent()) {
                        try (InputStream mcmetaIn = mcmetaOpt.get().open()) {
                            Path mcmetaRel = path
                                    .resolve(relativePath + ".mcmeta");
                            copyResource(session, mcmetaRel, mcmetaIn, "asset", "texture_mcmeta", id.toString());
                        }
                    }
                } catch (IOException e) {
                    TCompanion.LOGGER.warn("Failed to dump .mcmeta for {}:", id, e);
                }
            } catch (IOException e) {
                TCompanion.LOGGER.error("Failed to dump texture: {}", id, e);
            }
        });

        session.manifest.sectionCounts.merge("assets:textures", count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task textures: {} objects in {}", count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        return count.get();
    }

    public static int dumpJsonResources(DumpSession session, String loc, String outputType) {
        return dumpJsonResources(session, Minecraft.getInstance().getResourceManager(), loc, outputType, false);
    }

    public static int dumpJsonResourcesFromServer(MinecraftServer server, DumpSession session, String loc, String outputType) {
        return dumpJsonResources(session, server.getResourceManager(), loc, outputType, true);
    }

    private static int dumpJsonResources(
            DumpSession session,
            ResourceManager mngr,
            String loc,
            String outputType,
            boolean dataSection
    ) {
        Map<ResourceLocation, Resource> resources = mngr.listResources(loc, path -> path.getPath().endsWith(".json"));
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        resources.forEach((rl, resource) -> {
            if(!session.scope.matches(rl)) return;
            try(InputStream in = resource.open()) {
                Path relative = Path.of(rl.getPath());
                int skipCount = Path.of(loc).getNameCount();
                Path trimmed = relative.subpath(skipCount, relative.getNameCount());

                Path rel = dataSection
                        ? Path.of("data", outputType, rl.getNamespace()).resolve(trimmed)
                        : Path.of("assets", outputType, rl.getNamespace()).resolve(trimmed);

                copyResource(session, rel, in, dataSection ? "data_resource" : "asset", outputType, rl.toString());
                count.getAndIncrement();
            } catch (IOException e) {
                TCompanion.LOGGER.error("Failed to dump {} {}:", outputType, rl, e);
            }
        });

        session.manifest.sectionCounts.merge((dataSection ? "data:" : "assets:") + outputType, count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task {}: {} objects in {}", outputType, count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        return count.get();
    }

    public static int dumpItems(MinecraftServer server, DumpSession session) {
        Registry<Item> items = server.registryAccess().registryOrThrow(Registries.ITEM);
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        items.forEach(item -> {
            ResourceLocation id = items.getKey(item);
            assert id != null;

            if(!session.scope.matches(id)) return;
            if(TSkipRuleRegistry.isSkipped("item", id.toString())) return;

            try {
                JsonObject json = new JsonObject();
                ItemStack stack = new ItemStack(item);
                json.addProperty("id", id.toString());
                json.addProperty("displayName", stack.getHoverName().getString());
                json.addProperty("maxCount", stack.getMaxStackSize());
                json.addProperty("maxDamage", stack.getMaxDamage());
                json.addProperty("rarity", stack.getRarity().toString());
                json.addProperty("enchantability", item.getEnchantmentValue());

                if(item instanceof TieredItem tItem) {
                    JsonObject tierInfo = new JsonObject();
                    tierInfo.addProperty("tier", tItem.getTier().toString());
                    tierInfo.addProperty("uses", tItem.getTier().getUses());
                    tierInfo.addProperty("speed", tItem.getTier().getSpeed());
                    tierInfo.addProperty("attackDamageBonus", tItem.getTier().getAttackDamageBonus());
                    json.add("toolTier", tierInfo);
                }

                if(item instanceof ArmorItem aItem) {
                    JsonObject armorInfo = new JsonObject();
                    armorInfo.addProperty("defence", aItem.getDefense());
                    armorInfo.addProperty("toughness", aItem.getToughness());
                    armorInfo.addProperty("type", aItem.getType().getName());
                    armorInfo.addProperty("slot", aItem.getEquipmentSlot().getName());
                    json.add("armorProperties", armorInfo);
                }

                Path rel = Path.of("data", "items", id.getNamespace(), id.getPath() + ".json");
                writeJson(session, rel, json, "derived", "item", id.toString());
                count.getAndIncrement();
            } catch (IOException e) {
                TCompanion.LOGGER.error("I/O error writing: {}:", id, e);
            } catch (RuntimeException e) {
                TCompanion.LOGGER.error("Failed dumping: {}:", id, e);
            }
        });

        session.manifest.sectionCounts.merge("items", count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task items: {} objects in {}", count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        return count.get();
    }

    public static int dumpCustomTypes(MinecraftServer server, DumpSession session) {
        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        for (TCustomTypeProvider provider : TCustomTypeRegistry.getProviders()) {
            TCustomTypeDescriptor descriptor = provider.getDescriptor();
            try {
                JsonObject descriptorJson = new JsonObject();
                descriptorJson.addProperty("id", descriptor.typeId());
                descriptorJson.addProperty("displayName", descriptor.displayName());
                if (!descriptor.getIconTexture().isBlank()) {
                    descriptorJson.addProperty("iconTexture", descriptor.getIconTexture());
                }
                descriptorJson.addProperty("browseable", descriptor.isBrowseable());
                String browserGroup = descriptor.getBrowserGroupId();
                if (browserGroup != null && !browserGroup.isBlank()) {
                    descriptorJson.addProperty("browserGroup", browserGroup);
                    TBrowserGroup group = TBrowserGroupRegistry.get(browserGroup).orElse(null);
                    if (group != null && !group.getColor().isBlank()) {
                        descriptorJson.addProperty("groupColor", group.getColor());
                    }
                }
                TAtlasDescriptor atlas = descriptor.getAtlasDescriptor();
                if (atlas != null) {
                    JsonObject atlasJson = createAtlasJson(atlas);
                    descriptorJson.add("atlas", atlasJson);
                }
                descriptorJson.add("metadata", toJson(descriptor.getMetadata()));

                String[] descriptorParts = splitNamespacedId(descriptor.typeId());
                Path descriptorPath = Path.of("data", "value_types", descriptorParts[0], descriptorParts[1] + ".json");
                writeJson(session, descriptorPath, descriptorJson, "derived", "value_type", descriptor.typeId());
                count.incrementAndGet();
            } catch (IOException e) {
                TCompanion.LOGGER.error("I/O error writing custom type descriptor {}:", descriptor.typeId(), e);
            }

            for (TCustomTypeEntry entry : provider.dumpValues(server)) {
                try {
                    if (TSkipRuleRegistry.isSkipped(descriptor.typeId(), entry.id())) continue;
                    String[] typeParts = splitNamespacedId(descriptor.typeId());
                    String[] parts = splitNamespacedId(entry.id());
                    JsonObject json = new JsonObject();
                    json.addProperty("id", entry.id());
                    json.addProperty("typeId", descriptor.typeId());
                    json.addProperty("displayName", entry.displayName());
                    if (!entry.texturePath().isBlank()) {
                        json.addProperty("texturePath", entry.texturePath());
                    }
                    json.add("rawData", toJson(entry.rawData()));

                    Path rel = Path.of("data", "values", typeParts[0], typeParts[1], parts[0], parts[1] + ".json");
                    writeJson(session, rel, json, "derived", descriptor.typeId(), entry.id());
                    count.incrementAndGet();
                } catch (IOException e) {
                    TCompanion.LOGGER.error("I/O error writing custom value {} of type {}:", entry.id(), descriptor.typeId(), e);
                } catch (RuntimeException e) {
                    TCompanion.LOGGER.error("Failed dumping custom value {} of type {}:", entry.id(), descriptor.typeId(), e);
                }
            }
        }

        session.manifest.sectionCounts.merge("custom_types", count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task custom_types: {} objects in {}", count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        return count.get();
    }

    private static @NonNull JsonObject createAtlasJson(TAtlasDescriptor atlas) {
        JsonObject atlasJson = new JsonObject();
        atlasJson.addProperty("typeId", atlas.typeId());
        atlasJson.addProperty("displayName", atlas.displayName());
        JsonArray families = new JsonArray();
        for (TAtlasDescriptor.TAtlasFamily f : atlas.families()) {
            JsonObject fam = new JsonObject();
            fam.addProperty("familyId", f.familyId());
            fam.addProperty("cellSize", f.cellSize());
            fam.addProperty("minAtlasSize", f.minAtlasSize());
            fam.addProperty("maxAtlasSize", f.maxAtlasSize());
            families.add(fam);
        }
        atlasJson.add("families", families);
        return atlasJson;
    }

    public static int dumpRecipeTypes(MinecraftServer server, DumpSession session) {
        Registry<RecipeType<?>> recipeTypes = server.registryAccess().registryOrThrow(Registries.RECIPE_TYPE);
        RecipeManager recipeManager = server.getRecipeManager();

        Map<RecipeType<?>, Recipe<?>> sampleRecipes = new HashMap<>();
        recipeManager.getRecipes().forEach(recipeHolder -> {
            RecipeType<?> type = recipeHolder.value().getType();
            sampleRecipes.putIfAbsent(type, recipeHolder.value());
        });

        AtomicInteger count = new AtomicInteger();
        long startMs = System.currentTimeMillis();

        recipeTypes.forEach(recipeType -> {
            ResourceLocation id = recipeTypes.getKey(recipeType);
            assert id != null;

            try {
                JsonObject json = new JsonObject();
                json.addProperty("id", id.toString());
                json.addProperty("displayName", id.getPath().replace('_', ' '));

                Recipe<?> sample = sampleRecipes.get(recipeType);
                TRecipeTypeDescriptor descriptor = TRecipeTypeDescriptorRegistry.getDescriptor(id.toString()).orElse(null);
                if (descriptor == null) {
                    for (var entry : TRecipeTypeDescriptorRegistry.getDescriptors().entrySet()) {
                        if (entry.getValue().getAdditionalRecipeTypeIds().contains(id.toString())) {
                            descriptor = entry.getValue();
                            break;
                        }
                    }
                }

                if(sample != null) {
                    int inputSlots  = 0;
                    int fuelSlots   = 0;
                    int outputSlots = 0;
                    int inputTanks  = 0;
                    int outputTanks = 0;
                    int energyCells = 0;

                    switch (sample) {
                        case CraftingRecipe ignored -> {
                            inputSlots = 9;
                            outputSlots = 1;
                        }
                        case AbstractCookingRecipe ignored -> {
                            inputSlots = 1;
                            fuelSlots = 1;
                            outputSlots = 1;
                        }
                        case SmithingRecipe ignored -> {
                            inputSlots = 3;
                            outputSlots = 1;
                        }
                        case StonecutterRecipe ignored -> inputSlots = 1;
                        default -> inputSlots = sample.getIngredients().size();
                    }

                    json.addProperty("inputSlots", inputSlots);
                    json.addProperty("fuelSlots", fuelSlots);
                    json.addProperty("outputSlots", outputSlots);
                    json.addProperty("inputTanks", inputTanks);
                    json.addProperty("outputTanks", outputTanks);
                    json.addProperty("energyCells", energyCells);
                } else {
                    json.addProperty("inputSlots", 0);
                    json.addProperty("fuelSlots", 0);
                    json.addProperty("outputSlots", 0);
                    json.addProperty("inputTanks", 0);
                    json.addProperty("outputTanks", 0);
                    json.addProperty("energyCells", 0);
                    json.addProperty("note", "No recipes registered");
                }

                if (descriptor != null) {
                    json.addProperty("displayName", descriptor.getDisplayName());
                    json.addProperty("uiTexture", descriptor.getUITexture());
                    json.add("layout", toJson(descriptor.getUILayout()));
                    json.add("components", toJsonRecipeComponents(descriptor.getComponents()));
                    json.add("metadata", toJson(descriptor.getMetadata()));
                    List<String> catalysts = descriptor.getCatalysts();
                    if (!catalysts.isEmpty()) {
                        JsonArray catalystsArray = new JsonArray();
                        catalysts.forEach(catalystsArray::add);
                        json.add("catalysts", catalystsArray);
                    }
                    List<String> kubeJsMethods = descriptor.getKubeJsMethodNames();
                    if (!kubeJsMethods.isEmpty()) {
                        JsonArray methodsArray = new JsonArray();
                        kubeJsMethods.forEach(methodsArray::add);
                        json.add("kubeJsMethods", methodsArray);
                    }
                    int importSkipArgs = descriptor.getImportSkipArgs();
                    if (importSkipArgs > 0) {
                        json.addProperty("importSkipArgs", importSkipArgs);
                    }
                    List<TRecipeTypeDescriptor.ImportPositionalOption> importOpts = descriptor.getImportPositionalOptions();
                    if (!importOpts.isEmpty()) {
                        JsonArray optsArray = new JsonArray();
                        for (var opt : importOpts) {
                            JsonObject o = new JsonObject();
                            o.addProperty("key", opt.key());
                            o.addProperty("positionalIndex", opt.index());
                            optsArray.add(o);
                        }
                        json.add("importPositionalOptions", optsArray);
                    }
                    Optional<TRecipeGenerator> generatorOpt = descriptor.getRecipeGenerator();
                    if (generatorOpt.isPresent()) {
                        TRecipeGenerator generator = generatorOpt.get();
                        List<TGenerationOption> genOpts = generator.getGenerationOptions();
                        if (!genOpts.isEmpty()) {
                            JsonArray optsArray = createOptsArray(genOpts);
                            json.add("generationOptions", optsArray);
                        }
                        Optional<GenerationTemplates> genTemplatesOpt = generator.getGenerationTemplates();
                        genTemplatesOpt.ifPresent(generationTemplates -> json.add("templates", serializeGenerationTemplates(generationTemplates)));
                    }
                } else {
                    json.add("layout", new JsonObject());
                    json.add("components", new JsonArray());
                    json.add("metadata", new JsonObject());
                }

                Path rel = Path.of("data", "recipe_types", id.getNamespace(), id.getPath() + ".json");
                writeJson(session, rel, json, "derived", "recipe_type", id.toString());
                count.getAndIncrement();
            } catch (IOException e) {
                TCompanion.LOGGER.error("I/O error writing recipe type {}:", id, e);
            } catch (RuntimeException e) {
                TCompanion.LOGGER.error("Failed dumping recipe type {}:", id, e);
            }
        });

        session.manifest.sectionCounts.merge("recipe_types", count.get(), Integer::sum);
        TCompanion.LOGGER.info("Task recipe_types: {} objects in {}", count.get(), formatElapsedTimeMs(System.currentTimeMillis() - startMs));
        return count.get();
    }

    private static @NonNull JsonArray createOptsArray(List<TGenerationOption> genOpts) {
        JsonArray optsArray = new JsonArray();
        for (TGenerationOption opt : genOpts) {
            JsonObject o = new JsonObject();
            o.addProperty("key", opt.key());
            o.addProperty("label", opt.label());
            o.addProperty("type", opt.type());
            o.addProperty("placeholder", opt.placeholder());
            o.addProperty("defaultValue", opt.defaultValue());
            optsArray.add(o);
        }
        return optsArray;
    }

    private static String resolveRecipeTypeId(Registry<RecipeType<?>> recipeTypes, Recipe<?> recipe) {
        ResourceLocation recipeTypeId = recipeTypes.getKey(recipe.getType());
        return recipeTypeId != null ? recipeTypeId.toString() : "unknown";
    }

    private static JsonObject buildRecipeDisplayJson(MinecraftServer server, Recipe<?> recipe, String recipeTypeId) {
        TNormalizedRecipeView view = TRecipeTypeDescriptorRegistry.getDescriptor(recipeTypeId)
                .flatMap(TRecipeTypeDescriptor::getRecipeDumpAdapter)
                .map(adapter -> adapter.dumpRecipe(recipe, server.registryAccess()))
                .orElseGet(() -> buildFallbackRecipeView(server, recipe, recipeTypeId));
        return toJson(view).getAsJsonObject();
    }

    private static TNormalizedRecipeView buildFallbackRecipeView(MinecraftServer server, Recipe<?> recipe, String recipeTypeId) {
        TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.fallback();
        builder.property("recipeType", recipeTypeId);

        for (Ingredient ingredient : recipe.getIngredients()) {
            for (TRenderedValue value : TritiumBuiltinApi.renderedValuesForIngredient(ingredient)) {
                builder.input(value);
            }
        }

        TRenderedValue result = TritiumBuiltinApi.renderedValueForStack(recipe.getResultItem(server.registryAccess()));
        if (result != null) {
            builder.output(result);
        }

        if (recipe instanceof AbstractCookingRecipe cookingRecipe) {
            builder.property("cookTime", cookingRecipe.getCookingTime());
            builder.property("experience", cookingRecipe.getExperience());
        }

        return builder.build();
    }

    private static JsonArray toJsonRecipeComponents(List<TRecipeComponent> components) {
        JsonArray array = new JsonArray();
        for (TRecipeComponent component : components) {
            JsonObject json = new JsonObject();
            json.addProperty("id", component.id());
            json.addProperty("category", component.category());
            json.addProperty("x", component.x());
            json.addProperty("y", component.y());
            json.addProperty("width", component.width());
            json.addProperty("height", component.height());
            json.add("data", toJson(component.data()));
            array.add(json);
        }
        return array;
    }

    private static JsonElement toJson(TNormalizedRecipeView view) {
        JsonObject json = new JsonObject();
        json.addProperty("mode", view.mode());
        if (view.descriptorId() != null && !view.descriptorId().isBlank()) {
            json.addProperty("descriptorId", view.descriptorId());
        }

        JsonArray bindings = new JsonArray();
        for (TRecipeBinding binding : view.bindings()) {
            JsonObject bindingJson = new JsonObject();
            bindingJson.addProperty("componentId", binding.componentId());
            JsonArray entries = new JsonArray();
            binding.entries().forEach(entry -> entries.add(toJson(entry)));
            bindingJson.add("entries", entries);
            bindings.add(bindingJson);
        }
        json.add("bindings", bindings);

        JsonArray inputs = new JsonArray();
        view.inputs().forEach(value -> inputs.add(toJson(value)));
        json.add("inputs", inputs);

        JsonArray outputs = new JsonArray();
        view.outputs().forEach(value -> outputs.add(toJson(value)));
        json.add("outputs", outputs);
        json.add("properties", toJson(view.properties()));
        return json;
    }

    private static JsonElement toJson(TRenderedValue value) {
        JsonObject json = new JsonObject();
        json.addProperty("refType", value.refType());
        json.addProperty("valueType", value.valueType());
        json.addProperty("id", value.id());
        json.addProperty("amount", value.amount());
        if (!value.displayName().isBlank()) {
            json.addProperty("displayName", value.displayName());
        }
        json.add("metadata", toJson(value.metadata()));
        return json;
    }

    private static JsonElement toJson(TUILayout layout) {
        JsonObject json = new JsonObject();
        json.addProperty("width", layout.getWidth());
        json.addProperty("height", layout.getHeight());
        JsonArray elements = new JsonArray();
        for (TUIElement element : layout.getElements()) {
            JsonObject elementJson = new JsonObject();
            elementJson.addProperty("type", element.type());
            elementJson.addProperty("x", element.x());
            elementJson.addProperty("y", element.y());
            elementJson.addProperty("width", element.width());
            elementJson.addProperty("height", element.height());
            elementJson.addProperty("animDirection", element.animDirection());
            elements.add(elementJson);
        }
        json.add("elements", elements);
        return json;
    }

    private static JsonElement serializeGenerationTemplates(GenerationTemplates templates) {
        JsonObject root = createRoot(templates);

        JsonObject formats = new JsonObject();
        for (var fmtEntry : templates.formats().entrySet()) {
            String fmtId = fmtEntry.getKey();
            Map<String, String> variants = fmtEntry.getValue();
            if (variants.size() == 1) {
                String template = variants.values().iterator().next();
                formats.addProperty(fmtId, template);
            } else {
                JsonObject variantsObj = new JsonObject();
                for (var varEntry : variants.entrySet()) {
                    variantsObj.addProperty(varEntry.getKey(), varEntry.getValue());
                }
                formats.add(fmtId, variantsObj);
            }
        }
        root.add("formats", formats);

        Map<String, Map<String, String>> variantDefaults = templates.variantDefaults();
        if (!variantDefaults.isEmpty()) {
            JsonObject vdObj = new JsonObject();
            for (var vdEntry : variantDefaults.entrySet()) {
                JsonObject optObj = new JsonObject();
                for (var optEntry : vdEntry.getValue().entrySet()) {
                    optObj.addProperty(optEntry.getKey(), optEntry.getValue());
                }
                vdObj.add(vdEntry.getKey(), optObj);
            }
            root.add("variantDefaults", vdObj);
        }

        return root;
    }

    private static @NonNull JsonObject createRoot(GenerationTemplates templates) {
        JsonObject root = new JsonObject();
        if (templates.variantOption() != null) {
            root.addProperty("variantOption", templates.variantOption());
        }
        if (templates.autoValue() != null) {
            root.addProperty("autoValue", templates.autoValue());
        }
        if (templates.expectsGrid()) {
            root.addProperty("expectsGrid", true);
        }
        if (templates.gridSlots() != null) {
            root.addProperty("gridSlots", templates.gridSlots());
        }
        root.addProperty("gridCols", templates.gridCols());
        return root;
    }

    private static JsonElement toJson(Map<String, Object> map) {
        JsonObject json = new JsonObject();
        map.forEach((key, value) -> json.add(key, toJsonValue(value)));
        return json;
    }

    private static JsonElement toJsonValue(Object value) {
        switch (value) {
            case null -> {
                return JsonNull.INSTANCE;
            }
            case JsonElement jsonElement -> {
                return jsonElement;
            }
            case Number number -> {
                return new JsonPrimitive(number);
            }
            case Boolean bool -> {
                return new JsonPrimitive(bool);
            }
            case Character character -> {
                return new JsonPrimitive(character);
            }
            case String string -> {
                return new JsonPrimitive(string);
            }
            case Map<?, ?> childMap -> {
                JsonObject object = new JsonObject();
                childMap.forEach((key, childValue) -> object.add(String.valueOf(key), toJsonValue(childValue)));
                return object;
            }
            case Iterable<?> iterable -> {
                JsonArray array = new JsonArray();
                iterable.forEach(child -> array.add(toJsonValue(child)));
                return array;
            }
            default -> {
            }
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    private static String[] splitNamespacedId(String id) {
        String[] split = id.split(":", 2);
        if (split.length == 2 && !split[0].isBlank() && !split[1].isBlank()) {
            return split;
        }
        return new String[]{"minecraft", id};
    }

    private static void writeJson(
            DumpSession session,
            Path relativePath,
            JsonElement json,
            String kind,
            String type,
            String id
    ) throws IOException {
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        writeBytes(session, relativePath, bytes, kind, type, id);
    }

    private static void copyResource(
            DumpSession session,
            Path relativePath,
            InputStream in,
            String kind,
            String type,
            String id
    ) throws IOException {
        Path out = session.tempDir.resolve(relativePath);
        Path parent = out.getParent();
        if (session.createdDirectories.add(parent)) {
            Files.createDirectories(parent);
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 support", e);
        }

        try (OutputStream outStream = Files.newOutputStream(out)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                outStream.write(buffer, 0, read);
            }
        }

        String hash = bytesToHex(digest.digest());
        long size = Files.size(out);
        session.manifest.files.add(new DumpedFile(
                normalizePath(relativePath),
                kind,
                type,
                id,
                hash,
                size
        ));
    }

    private static void writeBytes(
            DumpSession session,
            Path relativePath,
            byte[] bytes,
            String kind,
            String type,
            String id
    ) throws IOException {
        Path out = session.tempDir.resolve(relativePath);
        Path parent = out.getParent();
        if (session.createdDirectories.add(parent)) {
            Files.createDirectories(parent);
        }
        Files.write(out, bytes);
        session.manifest.files.add(new DumpedFile(
                normalizePath(relativePath),
                kind,
                type,
                id,
                sha256(bytes),
                bytes.length
        ));
    }

    private static void writeManifest(DumpSession session, boolean complete) throws IOException {
        writeManifestAt(session.tempDir.resolve("manifest.json"), session, complete);
    }

    private static void writeManifestAt(Path out, DumpSession session, boolean complete) throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("snapshotId", session.manifest.snapshotId);
        root.addProperty("createdAt", session.manifest.createdAt);
        root.addProperty("complete", complete);
        root.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        root.addProperty("environment", "server");
        root.addProperty("loader", "unknown");

        JsonObject scope = new JsonObject();
        scope.addProperty("type", session.scope.getType());
        switch (session.scope.getType()) {
            case "namespace" -> {
                JsonArray namespaces = new JsonArray();
                session.scope.namespaces().forEach(namespaces::add);
                scope.add("namespaces", namespaces);
            }
            case "section" -> {
                JsonArray kinds = new JsonArray();
                session.scope.kinds().forEach(kinds::add);
                scope.add("kinds", kinds);
            }
            case "textures" -> {
                JsonArray paths = new JsonArray();
                session.scope.paths().forEach(paths::add);
                scope.add("paths", paths);
            }
            default -> {

            }
        }
        root.add("scope", scope);

        JsonObject sections = new JsonObject();
        session.manifest.sectionCounts.forEach(sections::addProperty);
        root.add("counts", sections);

        JsonArray files = new JsonArray();
        for(DumpedFile file : session.manifest.files) {
            JsonObject obj = new JsonObject();
            obj.addProperty("path", file.path);
            obj.addProperty("kind", file.kind);
            obj.addProperty("type", file.type);
            obj.addProperty("id", file.id);
            obj.addProperty("sha256", file.sha256);
            obj.addProperty("size", file.size);
            files.add(obj);
        }
        root.add("files", files);

        JsonArray deleted = new JsonArray();
        session.manifest.deleted.forEach(del -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("kind", del.kind);
            obj.addProperty("id", del.id);
            deleted.add(obj);
        });
        root.add("deleted", deleted);

        JsonArray deletionCandidates = new JsonArray();
        session.manifest.deletedCandidates.forEach(del -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("kind", del.kind);
            obj.addProperty("id", del.id);
            deletionCandidates.add(obj);
        });
        root.add("deletedCandidates", deletionCandidates);

        JsonArray references = new JsonArray();
        session.manifest.references.forEach(ref -> {
            JsonObject obj = new JsonObject();
            obj.addProperty("kind", ref.kind);
            obj.addProperty("id", ref.id);
            obj.addProperty("blobSha", ref.blobSha);
            references.add(obj);
        });
        root.add("references", references);

        JsonObject summary = new JsonObject();
        summary.addProperty("fileCount", session.manifest.files.size());
        summary.addProperty("totalSize", session.manifest.files.stream().mapToLong(DumpedFile::size).sum());
        root.add("summary", summary);

        Files.writeString(out, PRETTY_GSON.toJson(root), StandardCharsets.UTF_8);
    }

    private static void writeLatestPointer(DumpSession session) throws IOException {
        JsonObject latest = new JsonObject();
        latest.addProperty("schemaVersion", SCHEMA_VERSION);
        latest.addProperty("snapshotId", session.manifest.snapshotId);
        latest.addProperty("createdAt", session.manifest.createdAt);
        latest.addProperty("path", normalizePath(session.rootDir.relativize(session.finalDir)));
        latest.addProperty("manifestPath", normalizePath(session.rootDir.relativize(session.finalDir.resolve("manifest.json"))));
        latest.addProperty("complete", true);

        Path temp = session.rootDir.resolve("latest.json.tmp");
        Path out = session.rootDir.resolve("latest.json");
        Files.createDirectories(session.rootDir);
        Files.writeString(temp, PRETTY_GSON.toJson(latest), StandardCharsets.UTF_8);
        try {
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, out, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void moveDirectory(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    private static void deleteIfExists(Path dir) throws IOException {
        if(!Files.exists(dir)) {
            return;
        }

        try(Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException("Failed deleting " + path, e);
                }
            });
        } catch (RuntimeException e) {
            if(e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    private static String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static void pruneOldSnapshots(Path rootDir) {
        Path snapshots = rootDir.resolve("snapshots");
        if(!Files.isDirectory(snapshots)) return;

        List<Path> candidates;
        try(Stream<Path> stream = Files.list(snapshots)) {
            candidates = stream
                    .filter(Files::isDirectory)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return !name.startsWith(".tmp-");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            TCompanion.LOGGER.warn("Failed listing snapshots for pruning", e);
            return;
        }

        if(candidates.size() <= SNAPSHOT_RETENTION_COUNT) return;

        int toDelete = candidates.size() - SNAPSHOT_RETENTION_COUNT;
        for(Path old : candidates.subList(0, toDelete)) {
            try {
                deleteIfExists(old);
                TCompanion.LOGGER.info("Pruned old snapshot {}", old.getFileName());
            } catch (IOException e) {
                TCompanion.LOGGER.warn("Failed deleting old snapshot {}", old.getFileName(), e);
            }
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return bytesToHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 support", e);
        }
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }

    public static final class DumpSession {
        final Path rootDir;
        final Path tempDir;
        final Path finalDir;
        final DumpManifest manifest;
        final DumpScope scope;
        final Set<Path> createdDirectories = new HashSet<>();

        DumpSession(Path rootDir, Path tempDir, Path finalDir, DumpManifest manifest, DumpScope scope) {
            this.rootDir = rootDir;
            this.tempDir = tempDir;
            this.finalDir = finalDir;
            this.manifest = manifest;
            this.scope = scope;
        }

        public Path finalDir() {
            return finalDir;
        }
    }

    public static final class DumpScope {
        private final String type;
        private final List<String> members;

        private DumpScope(String type, List<String> members) {
            this.type = type;
            this.members = List.copyOf(members);
        }

        public static final DumpScope FULL = new DumpScope("full", List.of());
        public static DumpScope namespace(List<String> namespaces) { return new DumpScope("namespace", namespaces); }
        public static DumpScope section(List<String> kinds) { return new DumpScope("section", kinds); }
        public static DumpScope textures(List<String> paths) { return new DumpScope("textures", paths); }

        public static DumpScope parse(JsonObject json) {
            if (json == null) return FULL;
            JsonObject scope = json.has("scope") ? json.getAsJsonObject("scope") : json;
            if (scope == null || !scope.has("type")) return FULL;
            String type = scope.get("type").getAsString();
            if ("full".equals(type)) return FULL;
            if ("namespace".equals(type)) {
                List<String> members = new ArrayList<>();
                if (scope.has("namespaces")) {
                    scope.getAsJsonArray("namespaces").forEach(e -> members.add(e.getAsString()));
                }
                return namespace(members);
            }
            if ("section".equals(type)) {
                List<String> members = new ArrayList<>();
                if (scope.has("kinds")) {
                    scope.getAsJsonArray("kinds").forEach(e -> members.add(e.getAsString()));
                }
                return section(members);
            }
            if ("textures".equals(type)) {
                List<String> members = new ArrayList<>();
                if (scope.has("paths")) {
                    scope.getAsJsonArray("paths").forEach(e -> members.add(e.getAsString()));
                }
                return textures(members);
            }
            return FULL;
        }

        public String getType() { return type; }
        public boolean isFull() { return "full".equals(type); }
        public List<String> namespaces() { return type.equals("namespace") ? members : List.of(); }
        public List<String> kinds() { return type.equals("section") ? members : List.of(); }
        public List<String> paths() { return type.equals("textures") ? members : List.of(); }

        public boolean matches(ResourceLocation location) {
            return switch (type) {
                case "namespace" -> namespaces().contains(location.getNamespace());
                default -> true;
            };
        }
    }

    private static final class DumpManifest {
        final String snapshotId;
        final String createdAt;
        final Map<String, Integer> sectionCounts = new TreeMap<>();
        final List<DumpedFile> files = new ArrayList<>();
        final List<DumpedDelete> deleted = new ArrayList<>();
        final List<DumpedDelete> deletedCandidates = new ArrayList<>();
        final List<DumpedReference> references = new ArrayList<>();

        DumpManifest(String snapshotId, String createdAt) {
            this.snapshotId = snapshotId;
            this.createdAt = createdAt;
        }
    }

    private record DumpedDelete(
            String kind,
            String id
    ) {
    }

    private record DumpedReference(
            String kind,
            String id,
            String blobSha
    ) {
    }

    private record DumpedFile(
            String path,
            String kind,
            String type,
            String id,
            String sha256,
            long size
    ) {
    }

    public static int dumpKubeJSTypings(MinecraftServer server, DumpSession session) {
        try {
            TCompanion.KUBE_JS.dump(server, session.tempDir);
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.warn("KubeJS typings dump failed; continuing registry dump ({})", e.toString());
        }
        return 1;
    }

    record AtlasDumpResult(List<RegistryIconRenderer.AtlasEntry> entries, List<DumpedFile> files, int count) {
        static AtlasDumpResult empty() {
            return new AtlasDumpResult(List.of(), List.of(), 0);
        }
    }

    public static AtlasDumpResult dumpIcons(DumpSession session) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            TCompanion.LOGGER.warn("Cannot dump icons: No level loaded.");
            return AtlasDumpResult.empty();
        }

        long startMs = System.currentTimeMillis();
        Registry<Item> items = mc.level.registryAccess().registryOrThrow(Registries.ITEM);
        Registry<net.minecraft.world.level.block.Block> blocks =
                mc.level.registryAccess().registryOrThrow(Registries.BLOCK);
        ResourceManager resourceManager = mc.getResourceManager();

        List<RegistryIconRenderer.RenderTask> glTasks = new ArrayList<>();
        Map<String, ResourceLocation> textureTasks = new LinkedHashMap<>();
        Map<String, String> textureTypes = new HashMap<>();
        Map<ResourceLocation, Boolean> genericItemModelCache = new HashMap<>();

        items.forEach(item -> {
            ResourceLocation id = items.getKey(item);
            if (id == null) return;
            if(!session.scope.matches(id)) return;
            boolean isBlock = blocks.containsKey(id);
            if (usesGenericItemModel(resourceManager, id, genericItemModelCache)) {
                ResourceLocation texLoc = resolveSimpleItemTexture(resourceManager, id);
                if (texLoc != null) {
                    textureTasks.put(id.toString(), texLoc);
                    textureTypes.put(id.toString(), isBlock ? "block" : "item");
                }
            } else {
                glTasks.add(new RegistryIconRenderer.RenderTask(new ItemStack(item), id.toString(), isBlock ? "block" : "item"));
            }
        });

        List<RegistryIconRenderer.AtlasEntry> entries = new ArrayList<>();
        Map<String, String> fingerprints = new HashMap<>();
        for (RegistryIconRenderer.RenderTask task : glTasks) {
            String fp = computeRenderFingerprint(resourceManager, ResourceLocation.parse(task.id()), genericItemModelCache);
            if (fp != null) fingerprints.put(task.id(), fp);
        }
        for (String itemId : textureTasks.keySet()) {
            String fp = computeRenderFingerprint(resourceManager, ResourceLocation.parse(itemId), genericItemModelCache);
            if (fp != null) fingerprints.put(itemId, fp);
        }

        Path atlasDir = session.tempDir.resolve("icons");
        Set<String> pageFiles = new LinkedHashSet<>();
        boolean resumed = false;

        if (session.scope.getType().equals("namespace")) {
            PriorAtlas prior = loadPriorAtlas(session);
            RegistryIconRenderer.beginScopedResume(prior.dir, prior.index);

            Map<String, byte[]> texBytes = new LinkedHashMap<>(textureTasks.size());
            for (Map.Entry<String, ResourceLocation> entry : textureTasks.entrySet()) {
                try (var resource = resourceManager.getResourceOrThrow(entry.getValue()).open()) {
                    texBytes.put(entry.getKey(), resource.readAllBytes());
                } catch (Exception e) {
                    TCompanion.LOGGER.warn("Failed to load texture bytes for {}: {}", entry.getKey(), e.getMessage());
                }
            }

            try {
                RegistryIconRenderer.ScopedAtlasResult result =
                        RegistryIconRenderer.renderScopedAtlas(glTasks, texBytes, textureTypes, fingerprints, atlasDir);
                entries = result.entries();
                pageFiles.addAll(result.pageFiles());
                resumed = true;
            } catch (IOException e) {
                TCompanion.LOGGER.error("Scoped atlas build failed", e);
            }
        } else {
            seedAtlasGroups(glTasks, textureTasks, textureTypes);

            if(!glTasks.isEmpty()) entries.addAll(RegistryIconRenderer.renderIcons(glTasks));

            for(Map.Entry<String, ResourceLocation> entry : textureTasks.entrySet()) {
                try(var resource = resourceManager.getResourceOrThrow(entry.getValue()).open()) {
                    NativeImage img = NativeImage.read(resource);
                    entries.add(RegistryIconRenderer.blitTextureToAtlas(entry.getKey(), textureTypes.getOrDefault(entry.getKey(), "item"), img));
                } catch (Exception e) {
                    TCompanion.LOGGER.warn("Failed to blit texture for {}: {}", entry.getKey(), e.getMessage());
                }
            }

            entries.replaceAll(e -> fingerprints.isEmpty() ? e
                    : new RegistryIconRenderer.AtlasEntry(e.itemId(), e.namespace(), e.type(), e.family(),
                            fingerprints.getOrDefault(e.itemId(), ""), e.x(), e.y(), e.size(), e.page(), e.frameCount(), e.frameSizeY()));

            try {
                pageFiles.addAll(RegistryIconRenderer.finalizeAtlas(atlasDir));
            } catch (IOException e) {
                TCompanion.LOGGER.error("Failed to write icon atlas", e);
            }
        }

        List<DumpedFile> iconFiles = new ArrayList<>();
        if (!entries.isEmpty()) {
            try {
                for (String name : pageFiles) {
                    Path pageFile = atlasDir.resolve(name);
                    if (!Files.exists(pageFile)) continue;
                    byte[] bytes = Files.readAllBytes(pageFile);
                    Path rel = Path.of("icons", name);
                    iconFiles.add(new DumpedFile(
                            normalizePath(rel), "icon_atlas", "atlas", "atlas_page_" + name,
                            sha256(bytes), bytes.length
                    ));
                }
            } catch (IOException e) {
                TCompanion.LOGGER.error("Failed to track atlas files", e);
            }
        }

        int count = glTasks.size() + textureTasks.size();
        TCompanion.LOGGER.info("Task: {} items in {}, resumed={}, pages={}",
                count, formatElapsedTimeMs(System.currentTimeMillis() - startMs), resumed, pageFiles.size());
        return new AtlasDumpResult(entries, iconFiles, count);
    }

    public static void applyIconManifest(DumpSession session, AtlasDumpResult result) {
        if (result.files != null) {
            session.manifest.files.addAll(result.files);
        }
        session.manifest.sectionCounts.merge("icons", result.count, Integer::sum);
    }

    private static void seedAtlasGroups(
            List<RegistryIconRenderer.RenderTask> glTasks,
            Map<String, ResourceLocation> textureTasks,
            Map<String, String> textureTypes
    ) {
        Map<String, Integer> glCounts = new HashMap<>();
        for (RegistryIconRenderer.RenderTask t : glTasks) {
            glCounts.merge(t.type() + "\u0000" + splitNamespacedId(t.id())[0], 1, Integer::sum);
        }
        Map<String, Integer> blitCounts = new HashMap<>();
        for (String id : textureTasks.keySet()) {
            blitCounts.merge(textureTypes.getOrDefault(id, "item") + "\u0000" + splitNamespacedId(id)[0], 1, Integer::sum);
        }

        TAtlasDescriptor itemAtlas = Objects.requireNonNull(MinecraftTypeDescriptors.ITEM.getAtlasDescriptor());
        TAtlasDescriptor blockAtlas = Objects.requireNonNull(MinecraftTypeDescriptors.BLOCK.getAtlasDescriptor());
        TAtlasDescriptor.TAtlasFamily itemGl = familyOf(itemAtlas, "gl");
        TAtlasDescriptor.TAtlasFamily blockGl = familyOf(blockAtlas, "gl");
        TAtlasDescriptor.TAtlasFamily itemBlit = familyOf(itemAtlas, "blit");

        glCounts.forEach((key, count) -> {
            String[] parts = key.split("\u0000", -1);
            String type = parts[0];
            String ns = parts[1];
            boolean block = type.equals("block");
            TAtlasDescriptor.TAtlasFamily fam = block ? blockGl : itemGl;
            RegistryIconRenderer.seedGroup(type, ns, "gl", fam.cellSize(), fam.minAtlasSize(), fam.maxAtlasSize(), count);
        });

        blitCounts.forEach((key, count) -> {
            String[] parts = key.split("\u0000", -1);
            String type = parts[0];
            String ns = parts[1];
            boolean block = type.equals("block");
            TAtlasDescriptor.TAtlasFamily fam = block ? familyOf(blockAtlas, "blit") : itemBlit;
            RegistryIconRenderer.seedGroup(type, ns, "blit",
                    fam.cellSize(), fam.minAtlasSize(), fam.maxAtlasSize(), count);
        });
    }

    private static TAtlasDescriptor.TAtlasFamily familyOf(TAtlasDescriptor atlas, String familyId) {
        for (TAtlasDescriptor.TAtlasFamily f : atlas.families()) {
            if (f.familyId().equals(familyId)) return f;
        }
        throw new IllegalStateException("Atlas descriptor " + atlas.typeId() + " missing family " + familyId);
    }

    private static PriorAtlas loadPriorAtlas(DumpSession session) {
        Path latest = session.rootDir.resolve("latest.json");
        if (!Files.exists(latest)) {
            TCompanion.LOGGER.info("[AtlasScoped] no latest.json; starting resume from scratch");
            return PriorAtlas.NONE;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(latest)).getAsJsonObject();
            if (!root.has("path")) {
                TCompanion.LOGGER.info("[AtlasScoped] latest.json lacks a path; no resume base");
                return PriorAtlas.NONE;
            }
            Path snapshot = session.rootDir.resolve(root.get("path").getAsString()).normalize();
            Path indexFile = snapshot.resolve("icons/atlas_index.json");
            Path atlasDir = snapshot.resolve("icons");
            if (!Files.exists(indexFile)) {
                TCompanion.LOGGER.info("[AtlasScoped] prior snapshot has no atlas_index.json; no resume base");
                return new PriorAtlas(atlasDir, Map.of());
            }
            Map<String, RegistryIconRenderer.AtlasEntry> index = parseAtlasIndex(indexFile);
            TCompanion.LOGGER.info("[AtlasScoped] resume base {}: {} cells", snapshot, index.size());
            return new PriorAtlas(atlasDir, index);
        } catch (IOException | RuntimeException e) {
            TCompanion.LOGGER.warn("[AtlasScoped] failed to read prior atlas, starting fresh", e);
            return PriorAtlas.NONE;
        }
    }

    private static Map<String, RegistryIconRenderer.AtlasEntry> parseAtlasIndex(Path indexFile) throws IOException {
        JsonArray arr = JsonParser.parseString(Files.readString(indexFile)).getAsJsonArray();
        Map<String, RegistryIconRenderer.AtlasEntry> map = new HashMap<>();
        for (JsonElement element : arr) {
            JsonObject o = element.getAsJsonObject();
            String id = o.get("id").getAsString();
            String ns = o.has("namespace") ? o.get("namespace").getAsString() : splitNamespacedId(id)[0];
            String type = o.has("type") ? o.get("type").getAsString() : "item";
            int rawPage = o.get("page").getAsInt();
            String family = o.has("family") ? o.get("family").getAsString()
                    : (rawPage >= 1000 ? "blit" : "gl");
            int page = rawPage >= 1000 && !o.has("family") ? rawPage - 1000 : rawPage;
            String fp = o.has("renderFingerprint") ? o.get("renderFingerprint").getAsString() : "";
            map.put(id, new RegistryIconRenderer.AtlasEntry(
                    id, ns, type, family, fp,
                    o.get("x").getAsInt(), o.get("y").getAsInt(), o.get("size").getAsInt(),
                    page, o.has("frameCount") ? o.get("frameCount").getAsInt() : 1,
                    o.has("frameSizeY") ? o.get("frameSizeY").getAsInt() : o.get("size").getAsInt()));
        }
        return map;
    }

    private record PriorAtlas(Path dir, Map<String, RegistryIconRenderer.AtlasEntry> index) {
        static final PriorAtlas NONE = new PriorAtlas(null, Map.of());
    }

    public static void writeAtlasIndex(DumpSession session, List<RegistryIconRenderer.AtlasEntry> entries) {
        if(entries.isEmpty()) return;
        Path rel = Path.of("icons/atlas_index.json");
        Path full = session.tempDir.resolve(rel);
        try {
            Files.createDirectories(full.getParent());
            JsonArray arr = createArr(entries);
            Files.writeString(full, GSON.toJson(arr), StandardCharsets.UTF_8);
            byte[] bytes = Files.readAllBytes(full);
            session.manifest.files.add(new DumpedFile(
                    normalizePath(rel), "icon_atlas_index", "atlas_index", "atlas_index",
                    sha256(bytes), bytes.length
            ));
        } catch (IOException e) {
            TCompanion.LOGGER.error("Failed to write atlas index:", e);
        }
    }

    private static @NonNull JsonArray createArr(List<RegistryIconRenderer.AtlasEntry> entries) {
        JsonArray arr = new JsonArray();
        for (RegistryIconRenderer.AtlasEntry e : entries) {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", e.itemId());
            obj.addProperty("namespace", e.namespace());
            obj.addProperty("type", e.type());
            obj.addProperty("family", e.family());
            obj.addProperty("page", e.page());
            obj.addProperty("x", e.x());
            obj.addProperty("y", e.y());
            obj.addProperty("size", e.size());
            obj.addProperty("frameCount", e.frameCount());
            obj.addProperty("frameSizeY", e.frameSizeY());
            obj.addProperty("renderFingerprint", e.renderFingerprint());
            arr.add(obj);
        }
        return arr;
    }

    public static void runPatches(DumpSession session) {
        for (TDumpPatch patch : TDumpPatchRegistry.getPatches()) {
            try {
                long startMs = System.currentTimeMillis();
                patch.apply(session.tempDir, Minecraft.getInstance());
                TCompanion.LOGGER.info("Patch {} completed in {}ms",
                        patch.getClass().getSimpleName(), System.currentTimeMillis() - startMs);
            } catch (Exception e) {
                TCompanion.LOGGER.error("Patch {} failed:", patch.getClass().getSimpleName(), e);
            }
        }
        rescanManifestFiles(session);
    }

    private static void rescanManifestFiles(DumpSession session) {
        List<DumpedFile> updated = new ArrayList<>(session.manifest.files.size());
        for (DumpedFile file : session.manifest.files) {
            Path fullPath = session.tempDir.resolve(file.path);
            if (Files.exists(fullPath)) {
                try {
                    byte[] bytes = Files.readAllBytes(fullPath);
                    updated.add(new DumpedFile(file.path, file.kind, file.type, file.id, sha256(bytes), bytes.length));
                } catch (IOException e) {
                    TCompanion.LOGGER.warn("Failed to re-hash {}:", file.path, e);
                    updated.add(file);
                }
            } else {
                updated.add(file);
            }
        }
        session.manifest.files.clear();
        session.manifest.files.addAll(updated);
    }



    private static boolean usesGenericItemModel(
            ResourceManager resourceManager,
            ResourceLocation itemId,
            Map<ResourceLocation, Boolean> cache
    ) {
        ResourceLocation modelId = ResourceLocation.fromNamespaceAndPath(itemId.getNamespace(), "item/" + itemId.getPath());
        return usesGenericItemModel(resourceManager, modelId, new HashSet<>(), cache);
    }

    private static boolean usesGenericItemModel(
            ResourceManager resourceManager,
            ResourceLocation modelId,
            Set<ResourceLocation> visited,
            Map<ResourceLocation, Boolean> cache
    ) {
        Boolean cached = cache.get(modelId);
        if (cached != null) {
            return cached;
        }

        if (!visited.add(modelId)) {
            return false;
        }

        ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                modelId.getNamespace(),
                "models/" + modelId.getPath() + ".json"
        );

        Optional<Resource> resource = resourceManager.getResource(resourceId);
        if (resource.isEmpty()) {
            cache.put(modelId, false);
            return false;
        }

        try (Reader reader = resource.get().openAsReader()) {
            JsonObject modelJson = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement parentElement = modelJson.get("parent");
            if (parentElement == null || !parentElement.isJsonPrimitive()) {
                cache.put(modelId, false);
                return false;
            }

            ResourceLocation parentId = ResourceLocation.parse(parentElement.getAsString());
            boolean generic = GENERIC_ITEM_MODEL_PARENTS.contains(parentId)
                    || usesGenericItemModel(resourceManager, parentId, visited, cache);
            cache.put(modelId, generic);
            return generic;
        } catch (Exception e) {
            TCompanion.LOGGER.warn("Failed to inspect item model parent for {}:", modelId, e);
            cache.put(modelId, false);
            return false;
        }
    }

    private static String computeRenderFingerprint(
            ResourceManager resourceManager,
            ResourceLocation itemId,
            Map<ResourceLocation, Boolean> genericItemModelCache
    ) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return null;
        }

        ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                itemId.getNamespace(), "item/" + itemId.getPath());
        digestModelChain(resourceManager, modelLoc, md);

        try {
            ResourceLocation layer0 = resolveSimpleItemTexture(resourceManager, itemId);
            if (layer0 != null) {
                var res = resourceManager.getResource(layer0);
                if (res.isPresent()) {
                    try (var stream = res.get().open()) {
                        digestStream(md, stream);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return HexFormat.ofDelimiter("").formatHex(md.digest());
    }

    private static void digestModelChain(ResourceManager resourceManager, ResourceLocation modelId, MessageDigest md) {
        Set<ResourceLocation> visited = new HashSet<>();
        ResourceLocation cursor = modelId;
        while (visited.add(cursor)) {
            ResourceLocation resourceId = ResourceLocation.fromNamespaceAndPath(
                    cursor.getNamespace(), "models/" + cursor.getPath() + ".json");
            var res = resourceManager.getResource(resourceId);
            if (res.isEmpty()) break;
            String jsonText;
            try (Reader reader = res.get().openAsReader()) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[4096];
                int n;
                while ((n = reader.read(buf)) >= 0) sb.append(buf, 0, n);
                jsonText = sb.toString();
            } catch (Exception e) {
                break;
            }
            md.update(jsonText.getBytes(StandardCharsets.UTF_8));

            String parent = null;
            try {
                JsonObject model = JsonParser.parseString(jsonText).getAsJsonObject();
                JsonElement pe = model.get("parent");
                if (pe != null && pe.isJsonPrimitive()) parent = pe.getAsString();
                digestModelTextures(resourceManager, model, md);
            } catch (Exception ignored) {
            }
            if (parent == null) break;
            cursor = ResourceLocation.parse(parent);
        }
    }

    private static void digestModelTextures(ResourceManager resourceManager, JsonObject model, MessageDigest md) {
        if (model == null) return;
        JsonElement te = model.get("textures");
        if (te == null || !te.isJsonObject()) return;
        for (Map.Entry<String, JsonElement> entry : te.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive()) continue;
            String ref = value.getAsString();
            if (ref == null || ref.startsWith("#")) continue;
            int colon = ref.indexOf(':');
            String ns = colon >= 0 ? ref.substring(0, colon) : "minecraft";
            String path = colon >= 0 ? ref.substring(colon + 1) : ref;
            ResourceLocation tex = ResourceLocation.fromNamespaceAndPath(ns, "textures/" + path + ".png");
            try {
                var res = resourceManager.getResource(tex);
                if (res.isPresent()) {
                    try (var stream = res.get().open()) {
                        digestStream(md, stream);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void digestStream(MessageDigest md, InputStream stream) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = stream.read(buf)) >= 0) {
            md.update(buf, 0, n);
        }
    }

    private static ResourceLocation resolveSimpleItemTexture(ResourceManager resourceManager, ResourceLocation itemId) {
        ResourceLocation modelLoc = ResourceLocation.fromNamespaceAndPath(
                itemId.getNamespace(), "models/item/" + itemId.getPath() + ".json");
        try (var resource = resourceManager.getResourceOrThrow(modelLoc).open()) {
            JsonObject model = GSON.fromJson(new InputStreamReader(resource), JsonObject.class);
            JsonObject textures = model.getAsJsonObject("textures");
            if (textures == null) return null;
            String layer0 = Optional.ofNullable(textures.get("layer0"))
                    .map(JsonElement::getAsString).orElse(null);
            if (layer0 == null) return null;
            int colon = layer0.indexOf(':');
            String ns = colon >= 0 ? layer0.substring(0, colon) : itemId.getNamespace();
            String path = colon >= 0 ? layer0.substring(colon + 1) : layer0;
            return ResourceLocation.fromNamespaceAndPath(ns, "textures/" + path + ".png");
        } catch (Exception e) {
            return null;
        }
    }
}
