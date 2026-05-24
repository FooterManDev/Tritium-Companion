package io.github.tritium_launcher.tritiumcompanion;

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

import java.io.IOException;
import java.nio.file.Path;

import static io.github.tritium_launcher.tritiumcompanion.RegistryDumper.*;

public class Command
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dumpRegistry")
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            var s = ctx.getSource();

                            long startTime = System.currentTimeMillis();
                            Path publishedPath;
                            int count;

                            RegistryDumper.DumpSession session;
                            try {
                                session = beginDump(server);
                            } catch (IOException e) {
                                Common.LOGGER.error("Failed to create dump session", e);
                                s.sendFailure(Component.literal("Failed to create registry dump session."));
                                return 0;
                            }

                            try {
                                int biome = dumpRegistry(server, session, Registries.BIOME, Biome.DIRECT_CODEC, "biome");
                                send(s, biome, "Biomes");

                                int configFeature = dumpRegistry(server, session, Registries.CONFIGURED_FEATURE, ConfiguredFeature.DIRECT_CODEC, "configured_feature");
                                send(s, configFeature, "Configured Features");

                                int placeFeature = dumpRegistry(server, session, Registries.PLACED_FEATURE, PlacedFeature.DIRECT_CODEC, "placed_feature");
                                send(s, placeFeature, "Placed Features");

                                int struct = dumpRegistry(server, session, Registries.STRUCTURE, Structure.DIRECT_CODEC, "structure");
                                send(s, struct, "Structures");

                                int structSet = dumpRegistry(server, session, Registries.STRUCTURE_SET, StructureSet.DIRECT_CODEC, "structure_set");
                                send(s, structSet, "Structure Sets");

                                int noiseSetting = dumpRegistry(server, session, Registries.NOISE_SETTINGS, NoiseGeneratorSettings.DIRECT_CODEC, "noise_settings");
                                send(s, noiseSetting, "Noise Settings");

                                int noise = dumpRegistry(server, session, Registries.NOISE, NormalNoise.NoiseParameters.DIRECT_CODEC, "noise");
                                send(s, noise, "Noise");

                                int worldPreset = dumpRegistry(server, session, Registries.WORLD_PRESET, WorldPreset.DIRECT_CODEC, "world_preset");
                                send(s, worldPreset, "World Presets");

                                int dimType = dumpRegistry(server, session, Registries.DIMENSION_TYPE, DimensionType.DIRECT_CODEC, "dimension_type");
                                send(s, dimType, "Dimension Types");

                                int enchants = dumpRegistry(server, session, Registries.ENCHANTMENT, Enchantment.DIRECT_CODEC, "enchantment");
                                send(s, enchants, "Enchantments");

                                int dmg = dumpRegistry(server, session, Registries.DAMAGE_TYPE, DamageType.DIRECT_CODEC, "damage_type");
                                send(s, dmg, "Damage Types");

                                int trimMat = dumpRegistry(server, session, Registries.TRIM_MATERIAL, TrimMaterial.DIRECT_CODEC, "trim_material");
                                send(s, trimMat, "Trim Materials");

                                int trimPat = dumpRegistry(server, session, Registries.TRIM_PATTERN, TrimPattern.DIRECT_CODEC, "trim_pattern");
                                send(s, trimPat, "Trim Patterns");

                                int jukeSong = dumpRegistry(server, session, Registries.JUKEBOX_SONG, JukeboxSong.DIRECT_CODEC, "jukebox_song");
                                send(s, jukeSong, "Jukebox Songs");

                                int painting = dumpRegistry(server, session, Registries.PAINTING_VARIANT, PaintingVariant.DIRECT_CODEC, "painting_variant");
                                send(s, painting, "Painting Variants");

                                int bannerPattern = dumpRegistry(server, session, Registries.BANNER_PATTERN, BannerPattern.DIRECT_CODEC, "banner_pattern");
                                send(s, bannerPattern, "Banner Patterns");

                                int wolf = dumpRegistry(server, session, Registries.WOLF_VARIANT, WolfVariant.DIRECT_CODEC, "wolf_variant");
                                send(s, wolf, "Wolf Variants");

                                int chat = dumpRegistry(server, session, Registries.CHAT_TYPE, ChatType.DIRECT_CODEC, "chat_type");
                                send(s, chat, "Chat Types");

                                int instrument = dumpRegistry(server, session, Registries.INSTRUMENT, Instrument.DIRECT_CODEC, "instrument");
                                send(s, instrument, "Instruments");

                                int recipeTypes = dumpRecipeTypes(server, session);
                                send(s, recipeTypes, "Recipe Types");

                                int items = dumpItems(server, session);
                                send(s, items, "Items");

                                int customTypes = dumpCustomTypes(server, session);
                                send(s, customTypes, "Custom Types");

                                int loot = dumpJsonResourcesFromServer(server, session, "loot_table", "loot_tables");
                                send(s, loot, "Loot Tables");

                                int itemModel = dumpJsonResources(session, "models/item", "models/item");
                                send(s, itemModel, "Item Models");
                                int blockModel = dumpJsonResources(session, "models/block", "models/block");
                                send(s, blockModel, "Block Models");
                                int blockstate = dumpJsonResources(session, "blockstates", "blockstates");
                                send(s, blockstate, "Blockstates");

                                int recipe = dumpRecipes(server, session);
                                send(s, recipe, "Recipes");
                                int tag = dumpTags(server, session);
                                send(s, tag, "Tags");

                                int textures = dumpTextures(session);
                                int kubejs = dumpKubeJSTypings(server, session);
                                send(s, kubejs, "KubeJS Typings");
                                int icons = 0;
                                icons = dumpIcons(session);
                                send(s, icons, "Icons");
                                send(s, textures, "Textures");

                                count = kubejs + icons + biome + 
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
                                        customTypes +
                                        loot +
                                        itemModel +
                                        blockModel +
                                        blockstate +
                                        recipe +
                                        tag +
                                        textures;

                                publishedPath = finalizeDump(session);
                            } catch (Throwable t) {
                                abandonDump(session);
                                Common.LOGGER.error("Registry dump failed", t);
                                s.sendFailure(Component.literal("Registry dump failed. Check the game log for details."));
                                return 0;
                            }

                            long elapsedTime = System.currentTimeMillis() - startTime;
                            long directorySize = RegistryDumper.calculateDirSize(publishedPath);
                            String formattedTime = RegistryDumper.formatElapsedTime(elapsedTime);
                            String formattedSize = RegistryDumper.formatBytes(directorySize);

                            RegistryDumper.printDumpSummary(publishedPath, startTime, count);

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
                                    .append(Component.literal(publishedPath.toString())
                                            .withStyle(style -> style
                                                    .withColor(ChatFormatting.LIGHT_PURPLE)
                                                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, publishedPath.toString()))
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
                                    return runSingleSectionDump(server, ctx.getSource(), "Recipes", session -> dumpRecipes(server, session));
                                })
                        )
                        .then(Commands.literal("icons")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    return runSingleSectionDump(server, ctx.getSource(), "Icons", RegistryDumper::dumpIcons);
                                })
                        )
                        .then(Commands.literal("tags")
                                .executes(ctx -> {
                                    var server = ctx.getSource().getServer();
                                    return runSingleSectionDump(server, ctx.getSource(), "Tags", session -> dumpTags(server, session));
                                }))
        );
    }

    private static void send(CommandSourceStack source, int count, String type) {
        source.sendSystemMessage(Component.literal("Dumped " + count + " " + type + ".").withStyle(ChatFormatting.GREEN));
    }

    private static int runSingleSectionDump(
            MinecraftServer server,
            CommandSourceStack source,
            String label,
            SectionDump action
    ) {
        RegistryDumper.DumpSession session;
        try {
            session = beginDump(server);
        } catch (IOException e) {
            Common.LOGGER.error("Failed to create dump session", e);
            source.sendFailure(Component.literal("Failed to create registry dump session."));
            return 0;
        }

        try {
            int count = action.run(session);
            Path publishedPath = finalizeDump(session, false);
            source.sendSystemMessage(Component.literal("Dumped " + count + " " + label + " to " + publishedPath).withStyle(ChatFormatting.GREEN));
            return 1;
        } catch (Throwable t) {
            abandonDump(session);
            Common.LOGGER.error("Partial registry dump failed", t);
            source.sendFailure(Component.literal("Registry dump failed. Check the game log for details."));
            return 0;
        }
    }

    @FunctionalInterface
    private interface SectionDump {
        int run(RegistryDumper.DumpSession session) throws Exception;
    }
}
