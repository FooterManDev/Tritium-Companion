package io.github.footermandev.tritiumcompanion;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.animal.WolfVariant;
import net.minecraft.world.entity.decoration.PaintingVariant;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.nio.file.Path;

import static io.github.footermandev.tritiumcompanion.RegistryDumper.*;

public class Command
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dumpRegistry")
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            var s = ctx.getSource();

                            long startTime = System.currentTimeMillis();
                            Path objsPath = server.getFile("registryObjs").toAbsolutePath();

                            int biome = dumpRegistry(server, Registries.BIOME, Biome.DIRECT_CODEC, "worldgen/biome");
                            send(s, biome, "Biomes");

                            int configFeature = dumpRegistry(server, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC, "worldgen/configured_feature");
                            send(s, configFeature, "Configured Features");

                            int placeFeature = dumpRegistry(server, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC, "worldgen/placed_feature");
                            send(s, placeFeature, "Placed Features");

                            int struct = dumpRegistry(server, Registries.STRUCTURE, Structure.DIRECT_CODEC, "worldgen/structure");
                            send(s, struct, "Structures");

                            int structSet = dumpRegistry(server, Registries.STRUCTURE_SET, StructureSet.DIRECT_CODEC, "worldgen/structure_set");
                            send(s, structSet, "Structure Sets");

                            int noiseSetting = dumpRegistry(server, Registries.NOISE_SETTINGS, NoiseGeneratorSettings.DIRECT_CODEC, "worldgen/noise_settings");
                            send(s, noiseSetting, "Noise Settings");

                            int noise = dumpRegistry(server, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC, "worldgen/noise");
                            send(s, noise, "Noise");

                            int worldPreset = dumpRegistry(server, Registries.WORLD_PRESET, WorldPreset.DIRECT_CODEC, "worldgen/world_preset");
                            send(s, worldPreset, "World Presets");

                            int dimType = dumpRegistry(server, Registries.DIMENSION_TYPE, DimensionType.DIRECT_CODEC, "dimension_type");
                            send(s, dimType, "Dimension Types");

                            int enchants = dumpRegistry(server, Registries.ENCHANTMENT, Enchantment.DIRECT_CODEC, "enchantment");
                            send(s, enchants, "Enchantments");

                            int dmg = dumpRegistry(server, Registries.DAMAGE_TYPE, DamageType.DIRECT_CODEC, "damage_type");
                            send(s, dmg, "Damage Types");

                            int trimMat = dumpRegistry(server, Registries.TRIM_MATERIAL, TrimMaterial.DIRECT_CODEC, "trim_material");
                            send(s, trimMat, "Trim Materials");

                            int trimPat = dumpRegistry(server, Registries.TRIM_PATTERN, TrimPattern.DIRECT_CODEC, "trim_pattern");
                            send(s, trimPat, "Trim Patterns");

                            int jukeSong = dumpRegistry(server, Registries.JUKEBOX_SONG, JukeboxSong.DIRECT_CODEC, "jukebox_song");
                            send(s, jukeSong, "Jukebox Songs");

                            int painting = dumpRegistry(server, Registries.PAINTING_VARIANT, PaintingVariant.DIRECT_CODEC, "painting_variant");
                            send(s, painting, "Painting Variants");

                            int bannerPattern = dumpRegistry(server, Registries.BANNER_PATTERN, BannerPattern.DIRECT_CODEC, "banner_pattern");
                            send(s, bannerPattern, "Banner Patterns");

                            int wolf = dumpRegistry(server, Registries.WOLF_VARIANT, WolfVariant.DIRECT_CODEC, "wolf_variant");
                            send(s, wolf, "Wolf Variants");

                            int chat = dumpRegistry(server, Registries.CHAT_TYPE, ChatType.DIRECT_CODEC, "chat_type");
                            send(s, chat, "Chat Types");

                            int instrument = dumpRegistry(server, Registries.INSTRUMENT, Instrument.DIRECT_CODEC, "instrument");
                            send(s, instrument, "Instruments");

                            int recipeTypes = dumpRecipeTypes(server);
                            send(s, recipeTypes, "Recipe Types");

                            int items = dumpItems(server);
                            send(s, items, "Items");

                            int loot = dumpJsonResourcesFromServer(server, "loot_table", "data/loot_table");
                            send(s, loot, "Loot Tables");

                            int itemModel = dumpJsonResources("models/item", "models/item");
                            send(s, itemModel, "Item Models");
                            int blockModel = dumpJsonResources("models/block", "models/block");
                            send(s, blockModel, "Block Models");
                            int blockstate = dumpJsonResources("blockstates", "blockstates");
                            send(s, blockstate, "Blockstates");

                            int recipe = dumpRecipes(server);
                            send(s, recipe, "Recipes");
                            int tag = dumpTags(server);
                            send(s, tag, "Tags");

                            int textures = dumpTextures();
                            send(s, textures, "Textures");

                            int count = biome +
                                    configFeature +
                                    placeFeature +
                                    struct +
                                    structSet +
                                    noiseSetting +
                                    noise +
                                    worldPreset +
                                    dimType +
                                    enchants +
                                    dmg +
                                    trimMat +
                                    trimPat +
                                    jukeSong +
                                    painting +
                                    bannerPattern +
                                    wolf +
                                    chat +
                                    instrument +
                                    recipeTypes +
                                    items +
                                    itemModel +
                                    blockModel +
                                    blockstate +
                                    recipe +
                                    tag +
                                    textures;

                            long elapsedTime = System.currentTimeMillis() - startTime;
                            long directorySize = RegistryDumper.calculateDirSize(objsPath);
                            String formattedTime = RegistryDumper.formatElapsedTime(elapsedTime);
                            String formattedSize = RegistryDumper.formatBytes(directorySize);

                            RegistryDumper.printDumpSummary(objsPath, startTime, count);

                            s.sendSystemMessage(Component.literal("═").withStyle(ChatFormatting.DARK_GRAY));
                            s.sendSystemMessage(Component.literal("Completed Registry Dump").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
                            s.sendSystemMessage(Component.literal("═").withStyle(ChatFormatting.DARK_GRAY));

                            s.sendSystemMessage(Component.literal("Total Objects: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(String.valueOf(count)).withStyle(ChatFormatting.GREEN)));

                            s.sendSystemMessage(Component.literal("Time Elapsed: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(formattedTime).withStyle(ChatFormatting.AQUA)));

                            s.sendSystemMessage(Component.literal("Total Size: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(formattedSize).withStyle(ChatFormatting.YELLOW)));

                            Component pathComponent = Component.literal("Output Path: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(objsPath.toString())
                                            .withStyle(style -> style
                                                    .withColor(ChatFormatting.LIGHT_PURPLE)
                                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, objsPath.toString()))
                                                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                            Component.literal("Click to copy path").withStyle(ChatFormatting.GREEN)))
                                                    .withUnderlined(true)));
                            s.sendSystemMessage(pathComponent);

                            s.sendSystemMessage(Component.literal("═").withStyle(ChatFormatting.DARK_GRAY));

                            return 1;
                        })
                        .then(Commands.literal("recipes")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    dumpRecipes(server);
                                    return 1;
                                })
                        )
                        .then(Commands.literal("tags")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    dumpTags(server);
                                    return 1;
                                }))
        );
    }

    private static void send(CommandSourceStack source, int count, String type) {
        source.sendSystemMessage(Component.literal("Dumped " + count + " " + type + ".").withStyle(ChatFormatting.GREEN));
    }
}
