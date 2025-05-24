package io.github.footermandev.tritiumcompanion;

import com.google.gson.*;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@SuppressWarnings("LoggingSimilarMessage")
public class RegistryDumper
{

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    public static <T> int dumpRegistry(
            MinecraftServer server,
            ResourceKey<? extends Registry<?>> registryKey,
            Codec<T> codec,
            String subPath
    ) {
        Path baseData = server.getFile("registryObjs/data").toAbsolutePath();
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

                Path out = baseData
                        .resolve(id.getNamespace())
                        .resolve(subPath)
                        .resolve(id.getPath() + ".json");

                Files.createDirectories(out.getParent());
                Files.writeString(out, GSON.toJson(json));

                Common.LOGGER.info("Dumped {} {}:", subPath, id);
                count.incrementAndGet();
            } catch (IOException e) {
                Common.LOGGER.error("I/O error writing {} {}:", subPath, id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping {} {}:", subPath, id, e) ;
            }
        });

        return count.get();
    }

    public static int dumpTags(MinecraftServer server) {
        Path baseData = server.getFile("registryObjs/data").toAbsolutePath();
        RegistryAccess access = server.registryAccess();
        AtomicInteger count = new AtomicInteger();

        access.registries().forEach(entry -> {
            ResourceKey<? extends Registry<?>> registryKey = entry.key();
            Registry<?> registry = entry.value();

            HolderLookup<?> lookup = registry.asLookup();
            Stream<? extends HolderSet.Named<?>> tagStream = lookup.listTags();

            List<? extends HolderSet.Named<?>> tags = tagStream.toList();
            if(tags.isEmpty()) return;

            String registryPath = registryKey.location().getPath();

            tags.forEach(named -> {
                ResourceLocation tagId = named.key().location();
                Path out = baseData
                        .resolve(tagId.getNamespace())
                        .resolve("tags")
                        .resolve(registryPath)
                        .resolve(tagId.getPath() + ".json");

                try {
                    Files.createDirectories(out.getParent());

                    JsonObject json = new JsonObject();
                    json.addProperty("replace", false);

                    JsonArray values = new JsonArray();
                    named.forEach(holder -> holder.unwrapKey().ifPresent(k -> values.add(k.location().toString())));
                    json.add("values", values);

                    Files.writeString(out, GSON.toJson(json));
                    Common.LOGGER.info("Dumped tag {}:{}", registryPath, tagId);
                    count.incrementAndGet();
                } catch (IOException e) {
                    Common.LOGGER.error("Failed dumping tag {}:{}", registryPath, tagId, e);
                }
            });
        });

        return count.get();
    }

    public static int dumpRecipes(MinecraftServer server) {
        RecipeManager mngr = server.getRecipeManager();
        AtomicInteger count = new AtomicInteger();

        mngr.getRecipes().forEach(holder -> {
            ResourceLocation id = holder.id();
            Recipe<?> recipe = holder.value();

            try {
                DataResult<JsonElement> dr = Recipe.CODEC.encodeStart(JsonOps.INSTANCE, recipe);
                JsonElement json = dr.getOrThrow(err ->
                        new IllegalStateException("Failed to encode recipe " + id + ": " + err));

                Path out = server.getFile("registryObjs/data")
                        .toAbsolutePath()
                        .resolve(id.getNamespace())
                        .resolve("recipe")
                        .resolve(id.getPath() + ".json");

                Files.createDirectories(out.getParent());
                Files.writeString(out, GSON.toJson(json));

                Common.LOGGER.info("Dumped recipe {}", id);
                count.incrementAndGet();
            } catch (IOException e) {
                Common.LOGGER.error("I/O error writing recipe {}:", id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping recipe {}:", id, e);
            }
        });

        Common.LOGGER.info("Tritium: Dump overview - {} recipes", mngr.getRecipes().size());
        return count.get();
    }

    public static int dumpTextures(ResourceManager mngr) {
        Path outDir = Path.of(Minecraft.getInstance().gameDirectory.toString(), "registryObjs/textures");

        Map<ResourceLocation, Resource> resources = mngr.listResources("textures", path -> path.getPath().endsWith(".png"));

        AtomicInteger count = new AtomicInteger();

        resources.forEach((id, resource) -> {
            try (InputStream in = resource.open()) {
                Path relative = Path.of(id.getPath());
                int skipCount = Path.of("textures").getNameCount();
                Path trimmed = relative.subpath(skipCount, relative.getNameCount());

                if(id.getNamespace().equals("realms")) return; // No need to include Realms images

                Path out = outDir
                        .resolve(id.getNamespace())
                        .resolve(trimmed);

                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                Common.LOGGER.info("Dumped texture {}", id);
                count.getAndIncrement();
            } catch (IOException e) {
                Common.LOGGER.error("Failed to dump texture: {}", id, e);
            }
        });

        Common.LOGGER.info("Dumped {} texture files.", count);
        return count.get();
    }

    public static int dumpJsonResources(String loc, String outputName) {
        Path outDir = Path.of(Minecraft.getInstance().gameDirectory.toString(), "registryObjs/" + outputName);
        ResourceManager mngr = Minecraft.getInstance().getResourceManager();

        Map<ResourceLocation, Resource> resources = mngr.listResources(loc, path -> path.getPath().endsWith(".json"));
        AtomicInteger count = new AtomicInteger();

        resources.forEach((rl, resource) -> {
            try(InputStream in = resource.open()) {
                Path relative = Path.of(rl.getPath());
                int skipCount = Path.of(loc).getNameCount();
                Path trimmed = relative.subpath(skipCount, relative.getNameCount());

                Path out = outDir
                        .resolve(rl.getNamespace())
                        .resolve(trimmed);

                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                count.getAndIncrement();
                Common.LOGGER.info("Dumped {} {}", outputName, rl);
            } catch (IOException e) {
                Common.LOGGER.error("Failed to dump {} {}:", outputName, rl, e);
            }
        });

        Common.LOGGER.info("Tritium: Dumped {} {} resource.", count.get(), outputName);
        return count.get();
    }

    public static int dumpJsonResourcesFromServer(MinecraftServer server, String loc, String outputName) {
        Path outDir = server.getFile("registryObjs").toAbsolutePath().resolve(outputName);
        ResourceManager mngr = server.getResourceManager();

        Map<ResourceLocation, Resource> resources = mngr.listResources(loc, path -> path.getPath().endsWith(".json"));
        AtomicInteger count = new AtomicInteger();

        resources.forEach((rl, resource) -> {
            try(InputStream in = resource.open()) {
                Path relative = Path.of(rl.getPath());
                int skipCount = Path.of(loc).getNameCount();
                Path trimmed = relative.subpath(skipCount, relative.getNameCount());

                Path out = outDir
                        .resolve(rl.getNamespace())
                        .resolve(trimmed);

                Files.createDirectories(out.getParent());
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                count.getAndIncrement();
                Common.LOGGER.info("Dumped {} {}", outputName, rl);
            } catch (IOException e) {
                Common.LOGGER.error("Failed to dump {} {}:", outputName, rl, e);
            }
        });

        Common.LOGGER.info("Tritium: Dumped {} {} resource.", count.get(), outputName);
        return count.get();
    }

    public static int dumpItems(MinecraftServer server) {
        Path outDir = server.getFile("registryObjs").toAbsolutePath().resolve("items");
        Registry<Item> items = server.registryAccess().registryOrThrow(Registries.ITEM);

        AtomicInteger count = new AtomicInteger();

        items.forEach(item -> {
            ResourceLocation id = items.getKey(item);
            assert id != null;

            try {
                JsonObject json = new JsonObject();
                ItemStack stack = new ItemStack(item);
                json.addProperty("id", id.toString());
                json.addProperty("translationKey", item.getDescriptionId());
                json.addProperty("maxCount", stack.getMaxStackSize());
                json.addProperty("maxDamage", stack.getMaxDamage());
                json.addProperty("rarity", stack.getRarity().toString());
                json.addProperty("enchantability", item.getEnchantmentValue());

                Path out = outDir
                        .resolve(id.getNamespace())
                        .resolve(id.getPath() + ".json");

                Files.createDirectories(out.getParent());
                Files.writeString(out, GSON.toJson(json));
                count.getAndIncrement();
                Common.LOGGER.info("Dumped {}", id);
            } catch (IOException e) {
                Common.LOGGER.error("I/O error writing: {}:", id, e);
            } catch (RuntimeException e) {
                Common.LOGGER.error("Failed dumping: {}:", id, e);
            }
        });

        Common.LOGGER.info("Tritium: Items Dumped: {}", count.get());
        return count.get();
    }
}
