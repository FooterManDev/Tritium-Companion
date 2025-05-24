package io.github.footermandev.tritiumcompanion;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.armortrim.TrimMaterial;
import net.minecraft.world.item.armortrim.TrimPattern;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import static io.github.footermandev.tritiumcompanion.RegistryDumper.*;

public class Command
{
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dumpRegistry")
                        .executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            var s = ctx.getSource();

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
                                    items +
                                    itemModel +
                                    blockModel +
                                    blockstate +
                                    recipe +
                                    tag;
                            ctx.getSource().sendSuccess(() ->
                                    Component.literal("Dumped " + count + " objects.").withStyle(ChatFormatting.GOLD), false
                            );

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
