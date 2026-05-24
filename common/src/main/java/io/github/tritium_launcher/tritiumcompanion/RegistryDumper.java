package io.github.tritium_launcher.tritiumcompanion;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import io.github.tritium_launcher.tritiumcompanion.client.RegistryIconRenderer;
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
import recipe.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
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
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();
    private static final Set<ResourceLocation> GENERIC_ITEM_MODEL_PARENTS = Set.of(
            ResourceLocation.withDefaultNamespace("item/generated"),
            ResourceLocation.withDefaultNamespace("item/handheld")
    );
    private static final int SCHEMA_VERSION = 1;
    private static final DateTimeFormatter SNAPSHOT_ID_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH-mm-ss'Z'")
            .withZone(ZoneOffset.UTC);

    static long calculateDirSize(Path dir) {
        AtomicLong size = new AtomicLong(0);
        try(Stream<Path> paths = Files.walk(dir)) {
            paths.filter(Files::isRegularFile)
                    .forEach(path -> {
                        try {
                            size.addAndGet(Files.size(path));
                        } catch (IOException e) {
                            Common.LOGGER.warn("Failed to get size of file: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            Common.LOGGER.error("Failed to calculate directory size", e);
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

    public static void printDumpSummary(Path snapshotPath, long startTime, int objectCount) {
        long elapsedTime = System.currentTimeMillis() - startTime;
        long directorySize = calculateDirSize(snapshotPath);

        Common.LOGGER.info("=");
        Common.LOGGER.info("Completed Registry Dump");
        Common.LOGGER.info("=");
        Common.LOGGER.info("Time Elapsed: {}", formatElapsedTime(elapsedTime));
        Common.LOGGER.info("Object Count: {}", objectCount);
        Common.LOGGER.info("Total Size: {}", formatBytes(directorySize));
        Common.LOGGER.info("Output Path: {}", snapshotPath.toAbsolutePath());
        Common.LOGGER.info("=");
    }

    public static DumpSession beginDump(MinecraftServer server) throws IOException {
        Path root = server.getFile("registryObjs").toAbsolutePath();
        Path snapshots = root.resolve("snapshots");
        Files.createDirectories(snapshots);

        String createdAt = Instant.now().toString();
        String snapshotId = SNAPSHOT_ID_FORMAT.format(Instant.now()) + "_" + UUID.randomUUID().toString().substring(0, 8);
        Path tempDir = snapshots.resolve(".tmp-" + snapshotId);
        Path finalDir = snapshots.resolve(snapshotId);

        deleteIfExists(tempDir);
        Files.createDirectories(tempDir);

        DumpManifest manifest = new DumpManifest(snapshotId, createdAt);
        return new DumpSession(root, tempDir, finalDir, manifest);
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
        }
        return session.finalDir;
    }

    public static void abandonDump(DumpSession session) {
        try {
            deleteIfExists(session.tempDir);
        } catch (IOException e) {
            Common.LOGGER.warn("Failed cleaning temporary dump directory {}", session.tempDir, e);
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
            Common.LOGGER.warn("Skipping missing registry: {}", registryKey.location());
            return 0;
        }

        var registry = optionalRegistry.get();
        RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, access);
        AtomicInteger count = new AtomicInteger();

        registry.holders().forEach(holder -> {
            Optional<ResourceKey<Object>> keyOpt = holder.unwrapKey();
            if(keyOpt.isEmpty()) return;

            ResourceKey<Object> rk = keyOpt.get();
            ResourceLocation id = rk.location();
            Object value = holder.value();

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
                Common.LOGGER.error("I/O error writing {} {}:", registryType, id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping {} {}:", registryType, id, e);
            }
        });

        session.manifest.sectionCounts.merge("registry:" + registryType, count.get(), Integer::sum);
        return count.get();
    }

    public static int dumpTags(MinecraftServer server, DumpSession session) {
        RegistryAccess access = server.registryAccess();
        AtomicInteger count = new AtomicInteger();

        access.registries().forEach(entry -> {
            ResourceKey<? extends Registry<?>> registryKey = entry.key();
            Registry<?> registry = entry.value();

            HolderLookup<?> lookup = registry.asLookup();
            List<? extends HolderSet.Named<?>> tags = lookup.listTags().toList();
            if(tags.isEmpty()) return;

            String registryType = registryKey.location().getPath();

            tags.forEach(named -> {
                ResourceLocation tagId = named.key().location();

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
                    Common.LOGGER.error("Failed dumping tag {}:{}", registryType, tagId, e);
                }
            });
        });

        session.manifest.sectionCounts.merge("tags", count.get(), Integer::sum);
        return count.get();
    }

    public static int dumpRecipes(MinecraftServer server, DumpSession session) {
        RecipeManager mngr = server.getRecipeManager();
        AtomicInteger count = new AtomicInteger();

        mngr.getRecipes().forEach(holder -> {
            ResourceLocation id = holder.id();
            Recipe<?> recipe = holder.value();

            try {
                DataResult<JsonElement> dr = Recipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe);
                JsonElement sourceJson = dr.getOrThrow(err ->
                        new IllegalStateException("Failed to encode recipe " + id + ": " + err));

                String recipeTypeId = resolveRecipeTypeId(server, recipe);
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
                Common.LOGGER.error("I/O error writing recipe {}:", id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping recipe {}:", id, e);
            }
        });

        session.manifest.sectionCounts.merge("recipes", count.get(), Integer::sum);
        Common.LOGGER.info("Tritium: Dump overview - {} recipes", mngr.getRecipes().size());
        return count.get();
    }

    public static int dumpTextures(DumpSession session) {
        return dumpTextures(session, Minecraft.getInstance().getResourceManager());
    }

    public static int dumpTextures(DumpSession session, ResourceManager mngr) {
        Map<ResourceLocation, Resource> resources = mngr.listResources("textures", path -> path.getPath().endsWith(".png"));
        AtomicInteger count = new AtomicInteger();

        resources.forEach((id, resource) -> {
            try (InputStream in = resource.open()) {
                String relativePath = id.getPath();
                if(relativePath.startsWith("textures/")) {
                    relativePath = relativePath.substring("textures/".length());
                }

                if(id.getNamespace().equals("realms")) return;

                Path rel = Path.of("assets", "textures", id.getNamespace()).resolve(relativePath);
                copyResource(session, rel, in, "asset", "texture", id.toString());
                count.getAndIncrement();
            } catch (IOException e) {
                Common.LOGGER.error("Failed to dump texture: {}", id, e);
            }
        });

        session.manifest.sectionCounts.merge("assets:textures", count.get(), Integer::sum);
        Common.LOGGER.info("Dumped {} texture files.", count.get());
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

        resources.forEach((rl, resource) -> {
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
                Common.LOGGER.error("Failed to dump {} {}:", outputType, rl, e);
            }
        });

        session.manifest.sectionCounts.merge((dataSection ? "data:" : "assets:") + outputType, count.get(), Integer::sum);
        Common.LOGGER.info("Tritium: Dumped {} {} resource.", count.get(), outputType);
        return count.get();
    }

    public static int dumpItems(MinecraftServer server, DumpSession session) {
        Registry<Item> items = server.registryAccess().registryOrThrow(Registries.ITEM);
        AtomicInteger count = new AtomicInteger();

        items.forEach(item -> {
            ResourceLocation id = items.getKey(item);
            assert id != null;

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
                Common.LOGGER.error("I/O error writing: {}:", id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping: {}:", id, e);
            }
        });

        session.manifest.sectionCounts.merge("items", count.get(), Integer::sum);
        Common.LOGGER.info("Tritium: Items Dumped: {}", count.get());
        return count.get();
    }

    public static int dumpCustomTypes(MinecraftServer server, DumpSession session) {
        AtomicInteger count = new AtomicInteger();

        for (TCustomTypeProvider provider : TCustomTypeRegistry.getProviders()) {
            TCustomTypeDescriptor descriptor = provider.getDescriptor();
            try {
                JsonObject descriptorJson = new JsonObject();
                descriptorJson.addProperty("id", descriptor.getTypeId());
                descriptorJson.addProperty("displayName", descriptor.getDisplayName());
                if (!descriptor.getIconTexture().isBlank()) {
                    descriptorJson.addProperty("iconTexture", descriptor.getIconTexture());
                }
                descriptorJson.addProperty("browseable", descriptor.isBrowseable());
                descriptorJson.add("metadata", toJson(descriptor.getMetadata()));

                String[] descriptorParts = splitNamespacedId(descriptor.getTypeId());
                Path descriptorPath = Path.of("data", "value_types", descriptorParts[0], descriptorParts[1] + ".json");
                writeJson(session, descriptorPath, descriptorJson, "derived", "value_type", descriptor.getTypeId());
                count.incrementAndGet();
            } catch (IOException e) {
                Common.LOGGER.error("I/O error writing custom type descriptor {}:", descriptor.getTypeId(), e);
            }

            for (TCustomTypeEntry entry : provider.dumpValues(server)) {
                try {
                    String[] typeParts = splitNamespacedId(descriptor.getTypeId());
                    String[] parts = splitNamespacedId(entry.id());
                    JsonObject json = new JsonObject();
                    json.addProperty("id", entry.id());
                    json.addProperty("typeId", descriptor.getTypeId());
                    json.addProperty("displayName", entry.displayName());
                    if (entry.texturePath() != null && !entry.texturePath().isBlank()) {
                        json.addProperty("texturePath", entry.texturePath());
                    }
                    json.add("rawData", toJson(entry.rawData()));

                    Path rel = Path.of("data", "values", typeParts[0], typeParts[1], parts[0], parts[1] + ".json");
                    writeJson(session, rel, json, "derived", descriptor.getTypeId(), entry.id());
                    count.incrementAndGet();
                } catch (IOException e) {
                    Common.LOGGER.error("I/O error writing custom value {} of type {}:", entry.id(), descriptor.getTypeId(), e);
                } catch (RuntimeException e) {
                    Common.LOGGER.error("Failed dumping custom value {} of type {}:", entry.id(), descriptor.getTypeId(), e);
                }
            }
        }

        session.manifest.sectionCounts.merge("custom_types", count.get(), Integer::sum);
        Common.LOGGER.info("Tritium: Custom Type Entries Dumped: {}", count.get());
        return count.get();
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

        recipeTypes.forEach(recipeType -> {
            ResourceLocation id = recipeTypes.getKey(recipeType);
            assert id != null;

            try {
                JsonObject json = new JsonObject();
                json.addProperty("id", id.toString());
                json.addProperty("displayName", id.getPath().replace('_', ' '));

                Recipe<?> sample = sampleRecipes.get(recipeType);
                TRecipeTypeDescriptor descriptor = TRecipeTypeDescriptorRegistry.getDescriptor(id.toString()).orElse(null);

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
                    if (catalysts != null && !catalysts.isEmpty()) {
                        JsonArray catalystsArray = new JsonArray();
                        catalysts.forEach(catalystsArray::add);
                        json.add("catalysts", catalystsArray);
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
                Common.LOGGER.error("I/O error writing recipe type {}:", id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping recipe type {}:", id, e);
            }
        });

        session.manifest.sectionCounts.merge("recipe_types", count.get(), Integer::sum);
        Common.LOGGER.info("Tritium: Recipe Types Dumped: {}", count.get());
        return count.get();
    }

    private static String resolveRecipeTypeId(MinecraftServer server, Recipe<?> recipe) {
        Registry<RecipeType<?>> recipeTypes = server.registryAccess().registryOrThrow(Registries.RECIPE_TYPE);
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
            json.addProperty("id", component.getId());
            json.addProperty("category", component.getCategory());
            json.addProperty("x", component.x());
            json.addProperty("y", component.y());
            json.addProperty("width", component.width());
            json.addProperty("height", component.height());
            json.add("data", toJson(component.getData()));
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
        if (value.displayName() != null && !value.displayName().isBlank()) {
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
        byte[] bytes = in.readAllBytes();
        writeBytes(session, relativePath, bytes, kind, type, id);
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
        Files.createDirectories(out.getParent());
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

        JsonObject summary = new JsonObject();
        summary.addProperty("fileCount", session.manifest.files.size());
        summary.addProperty("totalSize", session.manifest.files.stream().mapToLong(DumpedFile::size).sum());
        root.add("summary", summary);

        Files.writeString(out, GSON.toJson(root), StandardCharsets.UTF_8);
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
        Files.writeString(temp, GSON.toJson(latest), StandardCharsets.UTF_8);
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

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder out = new StringBuilder(hash.length * 2);
            for(byte b : hash) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Missing SHA-256 support", e);
        }
    }

    public static final class DumpSession {
        final Path rootDir;
        final Path tempDir;
        final Path finalDir;
        final DumpManifest manifest;

        DumpSession(Path rootDir, Path tempDir, Path finalDir, DumpManifest manifest) {
            this.rootDir = rootDir;
            this.tempDir = tempDir;
            this.finalDir = finalDir;
            this.manifest = manifest;
        }

        public Path finalDir() {
            return finalDir;
        }
    }

    private static final class DumpManifest {
        final String snapshotId;
        final String createdAt;
        final Map<String, Integer> sectionCounts = new TreeMap<>();
        final List<DumpedFile> files = new ArrayList<>();

        DumpManifest(String snapshotId, String createdAt) {
            this.snapshotId = snapshotId;
            this.createdAt = createdAt;
        }
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
        KubeJSDumper.dump(server, session.tempDir);
        return 1;
    }

    public static int dumpIcons(DumpSession session) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            Common.LOGGER.warn("Cannot dump icons: No level loaded.");
            return 0;
        }

        Registry<Item> items = mc.level.registryAccess().registryOrThrow(Registries.ITEM);
        ResourceManager resourceManager = mc.getResourceManager();
        Map<ItemStack, Path> tasks = new LinkedHashMap<>();
        Map<ResourceLocation, Path> renderedRelPaths = new HashMap<>();
        Map<ResourceLocation, Boolean> genericItemModelCache = new HashMap<>();

        items.forEach(item -> {
            ResourceLocation id = items.getKey(item);
            if (id == null) return;

            ItemStack stack = new ItemStack(item);
            if (usesGenericItemModel(resourceManager, id, genericItemModelCache)) return;

            String namespace = id.getNamespace();
            String path = id.getPath().replace('/', '_');
            Path relPath = Path.of("icons", namespace, path + ".png");
            renderedRelPaths.put(id, relPath);
            tasks.put(stack, session.tempDir.resolve(relPath));
        });

        if (!tasks.isEmpty()) {
            RegistryIconRenderer.renderIcons(tasks);
            
            renderedRelPaths.forEach((id, relPath) -> {
                Path fullPath = session.tempDir.resolve(relPath);
                if (Files.exists(fullPath)) {
                    try {
                        byte[] bytes = Files.readAllBytes(fullPath);
                        session.manifest.files.add(new DumpedFile(
                                normalizePath(relPath),
                                "item_icon",
                                "item",
                                id.toString(),
                                sha256(bytes),
                                bytes.length
                        ));
                    } catch (IOException e) {
                        Common.LOGGER.error("Failed to track rendered icon for {}:", id, e);
                    }
                }
            });
        }

        int count = renderedRelPaths.size();
        session.manifest.sectionCounts.merge("icons", count, Integer::sum);
        return count;
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
            Common.LOGGER.warn("Failed to inspect item model parent for {}:", modelId, e);
            cache.put(modelId, false);
            return false;
        }
    }
}
