package io.github.tritium_launcher.tritiumcompanion;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import io.github.tritium_launcher.tritiumcompanion.patch.FluidTintPatch;
import org.jspecify.annotations.NonNull;
import log.TLogFileDescriptor;
import log.TLogFileRegistry;
import recipe.*;

import java.util.*;

final class TritiumBuiltinApi
{
    private TritiumBuiltinApi() {}

    static void registerAll() {
        TRecipeTypeDescriptorRegistry.clear();
        TCustomTypeRegistry.clear();
        TSkipRuleRegistry.clear();
        TLogFileRegistry.clear();

        TSkipRuleRegistry.register(new TSkipRuleRecords.Exact("item", "minecraft:air"));
        TSkipRuleRegistry.register(new TSkipRuleRecords.Exact("item", "minecraft:barrier"));
        TSkipRuleRegistry.register(new TSkipRuleRecords.Exact("tritium:fluid", "minecraft:empty"));

        TRecipeTypeDescriptorRegistry.register(new CraftingRecipeDescriptor());
        TRecipeTypeDescriptorRegistry.register(new CookingRecipeDescriptor("minecraft:smelting", "Smelting", "minecraft:furnace", 200));
        TRecipeTypeDescriptorRegistry.register(new CampfireRecipeDescriptor());
        TRecipeTypeDescriptorRegistry.register(new SmithingRecipeDescriptor());
        TCustomTypeRegistry.register(new FluidTypeProvider());
        TDumpPatchRegistry.register(new FluidTintPatch());

        TLogFileRegistry.register(new ConsoleLogDescriptor(
                "tritiumcompanion:latest",
                "latest.log",
                "#f3cf71",
                "tritiumcompanion:console/latest.png",
                "logs/latest.log",
                Set.of("INFO", "WARN", "ERR")
        ));
        TLogFileRegistry.register(new ConsoleLogDescriptor(
                "tritiumcompanion:debug",
                "debug.log",
                "#E75E34",
                "tritiumcompanion:console/debug.png",
                "logs/debug.log",
                Set.of("INFO", "WARN", "ERR", "DEBUG")
        ));
        TLogFileRegistry.register(new ConsoleLogDescriptor(
                "tritiumcompanion:kubejs_server",
                "server.log",
                "#af61fc",
                "tritiumcompanion:console/kubejs.png",
                "logs/kubejs/server.log",
                Set.of("INFO", "WARN", "ERR")
        ));
        TLogFileRegistry.register(new ConsoleLogDescriptor(
                "tritiumcompanion:kubejs_startup",
                "startup.log",
                "#af61fc",
                "tritiumcompanion:console/kubejs.png",
                "logs/kubejs/startup.log",
                Set.of("INFO", "WARN", "ERR")
        ));
        TLogFileRegistry.register(new ConsoleLogDescriptor(
                "tritiumcompanion:kubejs_client",
                "client.log",
                "#af61fc",
                "tritiumcompanion:console/kubejs.png",
                "logs/kubejs/client.log",
                Set.of("INFO", "WARN", "ERR")
        ));
    }

    static List<TRenderedValue> renderedValuesForIngredient(Ingredient ingredient) {
        List<TRenderedValue> values = new ArrayList<>();
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                values.add(TRenderedValue.item(id.toString(), stack.getCount()));
            }
        }
        return values;
    }

    static TRenderedValue renderedValueForStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return TRenderedValue.item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
    }

    private static final class CraftingRecipeDescriptor implements TRecipeTypeDescriptor
    {
        private final List<TRecipeComponent> components = buildComponents();
        private final TUILayout layout = TComponentHelpers.LayoutBuilder.create(134, 72).build();
        private final TRecipeDumpAdapter adapter = (recipeObject, registryAccessObject) -> {
            CraftingRecipe recipe = (CraftingRecipe) recipeObject;
            RegistryAccess registryAccess = (RegistryAccess) registryAccessObject;
            TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.descriptor("minecraft:crafting");

            List<Ingredient> ingredients = recipe.getIngredients();
            if (recipe instanceof ShapedRecipe shaped) {
                int width = shaped.getWidth();
                int height = shaped.getHeight();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int ingredientIndex = y * width + x;
                        if (ingredientIndex >= ingredients.size()) {
                            continue;
                        }
                        List<TRenderedValue> entries = renderedValuesForIngredient(ingredients.get(ingredientIndex));
                        if (!entries.isEmpty()) {
                            String componentId = "input_" + (y * 3 + x);
                            builder.bind(componentId, entries);
                            entries.forEach(builder::input);
                        }
                    }
                }
            } else {
                for (int index = 0; index < Math.min(ingredients.size(), 9); index++) {
                    List<TRenderedValue> entries = renderedValuesForIngredient(ingredients.get(index));
                    if (!entries.isEmpty()) {
                        builder.bind("input_" + index, entries);
                        entries.forEach(builder::input);
                    }
                }
            }

            TRenderedValue result = renderedValueForStack(recipe.getResultItem(registryAccess));
            if (result != null) {
                builder.bind("output", List.of(result));
                builder.output(result);
            }

            return builder.build();
        };

        @Override
        public @NonNull String getRecipeTypeId() {
            return "minecraft:crafting";
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Crafting";
        }

        @Override
        public @NonNull List<TRecipeComponent> getComponents() {
            return components;
        }

        @Override
        public @NonNull String getUITexture() {
            return "tritiumcompanion:tgui/crafting_table.png";
        }

        @Override
        public @NonNull TUILayout getUILayout() {
            return layout;
        }

        @Override
        public @NonNull List<String> getCatalysts() {
            return List.of("minecraft:crafting_table");
        }

        @Override
        public @NonNull Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() {
            return Optional.of(adapter);
        }

        @Override
        public @NonNull Optional<TRecipeGenerator> getRecipeGenerator() {
            return Optional.of(new CraftingGenerator());
        }

        @Override
        public @NonNull List<String> getKubeJsMethodNames() {
            return List.of("shaped", "shapeless");
        }

        private static List<TRecipeComponent> buildComponents() {
            List<TRecipeComponent> components = new ArrayList<>();
            int[][] inputPositions = {
                {10, 10}, {28, 10}, {46, 10},
                {10, 28}, {28, 28}, {46, 28},
                {10, 46}, {28, 46}, {46, 46}
            };
            for (int i = 0; i < inputPositions.length; i++) {
                components.add(
                    TComponentHelpers.Slot.itemInput("input_" + i, 1)
                        .withDisplayName("Input " + (i + 1))
                        .bounds(inputPositions[i][0], inputPositions[i][1], 16, 16)
                );
            }
            components.add(
                TComponentHelpers.Slot.itemOutput("output", 64)
                    .withDisplayName("Result")
                    .bounds(104, 28, 16, 16)
            );
            return List.copyOf(components);
        }
    }

    private static final class CookingRecipeDescriptor implements TRecipeTypeDescriptor
    {
        private final String recipeTypeId;
        private final String displayName;
        private final String catalyst;
        private final List<TRecipeComponent> components;
        private final TUILayout layout;
        private final TRecipeDumpAdapter adapter;
        private final TRecipeGenerator generator;

        CookingRecipeDescriptor(String recipeTypeId, String displayName, String catalyst, int defaultCookTime) {
            this.recipeTypeId = recipeTypeId;
            this.displayName = displayName;
            this.catalyst = catalyst;
            this.generator = new CookingGenerator();

            this.components = List.of(
                    TComponentHelpers.Slot.itemInput("ingredient", 64).withDisplayName("Ingredient").bounds(10, 10, 16, 16),
                    TComponentHelpers.Slot.itemInput("fuel", 64).withDisplayName("Fuel").bounds(10, 46, 16, 16).withDisplayOnly(),
                    TComponentHelpers.Slot.itemOutput("output", 64).withDisplayName("Result").bounds(70, 28, 16, 16),
                    TComponentHelpers.GenericDuration.ticks(defaultCookTime).bounds(0, 0, 0, 0)
            );
            this.layout = TComponentHelpers.LayoutBuilder.create(100, 72).build();
            this.adapter = (recipeObject, registryAccessObject) -> {
                AbstractCookingRecipe recipe = (AbstractCookingRecipe) recipeObject;
                RegistryAccess registryAccess = (RegistryAccess) registryAccessObject;
                TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.descriptor(recipeTypeId);

                List<TRenderedValue> inputs = recipe.getIngredients().isEmpty()
                        ? List.of()
                        : renderedValuesForIngredient(recipe.getIngredients().getFirst());
                if (!inputs.isEmpty()) {
                    builder.bind("ingredient", inputs);
                    inputs.forEach(builder::input);
                }

                TRenderedValue result = renderedValueForStack(recipe.getResultItem(registryAccess));
                if (result != null) {
                    builder.bind("output", List.of(result));
                    builder.output(result);
                }

                builder.property("cookTime", recipe.getCookingTime());
                builder.property("experience", recipe.getExperience());
                return builder.build();
            };
        }

        @Override
        public @NonNull String getRecipeTypeId() {
            return recipeTypeId;
        }

        @Override
        public @NonNull String getDisplayName() {
            return displayName;
        }

        @Override
        public @NonNull List<TRecipeComponent> getComponents() {
            return components;
        }

        @Override
        public @NonNull String getUITexture() {
            return "tritiumcompanion:tgui/smelting.png";
        }

        @Override
        public @NonNull TUILayout getUILayout() {
            return layout;
        }

        @Override
        public @NonNull List<String> getCatalysts() {
            return List.of(catalyst);
        }

        @Override
        public @NonNull Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() {
            return Optional.of(adapter);
        }

        @Override
        public @NonNull Optional<TRecipeGenerator> getRecipeGenerator() {
            return Optional.of(generator);
        }

        @Override
        public @NonNull List<String> getAdditionalRecipeTypeIds() {
            return List.of("minecraft:smoking", "minecraft:blasting");
        }

        @Override
        public @NonNull List<String> getKubeJsMethodNames() {
            return List.of("smelting", "smoking", "blasting");
        }

        @Override
        public @NonNull List<ImportPositionalOption> getImportPositionalOptions() {
            return List.of(
                new ImportPositionalOption("experience", 2),
                new ImportPositionalOption("cookTime", 3)
            );
        }
    }

    private static final class FluidTypeProvider implements TCustomTypeProvider
    {
        private final TCustomTypeDescriptor descriptor = new TCustomTypeDescriptor() {
            @Override
            public @NonNull String typeId() {
                return "tritium:fluid";
            }

            @Override
            public @NonNull String displayName() {
                return "Fluid";
            }

            @Override
            public @NonNull Map<String, Object> getMetadata() {
                return Map.of("unit", "mB");
            }
        };

        @Override
        public @NonNull TCustomTypeDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public @NonNull List<TCustomTypeEntry> dumpValues(Object serverObject) {
            List<TCustomTypeEntry> values = new ArrayList<>();
            BuiltInRegistries.FLUID.forEach(fluid -> {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);

                String path = id.getPath();
                String displayName = path.substring(0, 1).toUpperCase() + path.substring(1);

                String texturePath;
                Integer tintColor = null;

                FluidTintResolver.Result resolved = TCompanion.FLUID_TINT_RESOLVER.resolve(fluid, fluid.defaultFluidState());
                if (resolved != null) {
                    ResourceLocation stillTex = resolved.stillTexture();
                    if (stillTex != null) {
                        texturePath = "assets/textures/" + stillTex.getNamespace()
                                + "/" + stillTex.getPath() + ".png";
                    } else {
                        texturePath = "assets/textures/" + id.getNamespace()
                                + "/block/" + path + "_still.png";
                    }
                    tintColor = resolved.tintColor();
                } else {
                    texturePath = "assets/textures/" + id.getNamespace()
                            + "/block/" + path + "_still.png";
                }

                Map<String, Object> rawData = new LinkedHashMap<>();
                rawData.put("id", id.toString());
                rawData.put("isSource", fluid.defaultFluidState().isSource());
                if (tintColor != null) {
                    rawData.put("tintColor", tintColor);
                }
                values.add(new TCustomTypeEntry(id.toString(), displayName, texturePath, rawData));
            });
            return values;
        }
    }

    private static final class CraftingGenerator implements TRecipeGenerator {
        private static final List<TGenerationFormat> FORMATS = List.of(
                new TGenerationFormat("json", "JSON"),
                new TGenerationFormat("kubejs", "KubeJS")
        );

        @Override
        public @NonNull List<TGenerationFormat> getSupportedFormats() {
            return FORMATS;
        }

        @Override
        public @NonNull List<TGenerationOption> getGenerationOptions() {
            return List.of(new TGenerationOption(
                    "recipeId", "Recipe ID", "text", "mod_id:recipe_name", ""
            ));
        }

        @Override
        public @NonNull Optional<GenerationTemplates> getGenerationTemplates() {
            Map<String, Map<String, String>> formats = new LinkedHashMap<>();

            formats.put("json", Map.of(
                    "shaped", "{\n  \"type\": \"minecraft:crafting_shaped\",\n  \"pattern\": [\n{{grid:input_0..input_8:cols=3:json}}\n  ],\n  \"key\": {\n{{keyMap:input_0..input_8:cols=3:json}}\n  },\n  \"result\": {{result:json}}\n}",
                    "shapeless", "{\n  \"type\": \"minecraft:crafting_shapeless\",\n  \"ingredients\": [\n{{list:input_0..input_8:json}}\n  ],\n  \"result\": {{result:json}}\n}"
            ));
            formats.put("kubejs", Map.of(
                    "shaped", "event.shaped(\n  {{result:kubejs}},\n  [\n{{grid:input_0..input_8:cols=3:quote}}\n  ],\n  {\n{{keyMap:input_0..input_8:cols=3:quote}}\n  }\n){{option:recipeId:.id(\"$0\")}}",
                    "shapeless", "event.shapeless(\n  {{result:kubejs}},\n  [\n{{list:input_0..input_8:kubejs}}\n  ]\n){{option:recipeId:.id(\"$0\")}}"
            ));

            return Optional.of(GenerationTemplates.withVariants("mode", "auto", "input_0..input_8", 3, formats));
        }

        @Override
        public @NonNull String generate(Map<String, SlotFill> fills, @NonNull String formatId, @NonNull Map<String, String> options) {
            SlotFill result = fills.get("output");
            if (result == null || result.itemId().isBlank()) {
                return "// Fill the result slot first";
            }

            List<SlotFill> inputs = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                SlotFill f = fills.get("input_" + i);
                inputs.add(f);
            }
            if (inputs.stream().allMatch(Objects::isNull)) {
                return "// Fill at least one input slot";
            }

            boolean shapeless = "shapeless".equals(options.get("mode"));
            if (!shapeless) {
                shapeless = !hasCompactShape(inputs);
            }

            String recipeId = options.getOrDefault("recipeId", "");

            return switch (formatId) {
                case "json" -> shapeless ? shapelessJson(result, inputs) : shapedJson(result, inputs);
                case "kubejs" -> shapeless ? shapelessKubeJs(result, inputs, recipeId) : shapedKubeJs(result, inputs, recipeId);
                default -> throw new IllegalArgumentException("Unsupported format: " + formatId);
            };
        }

        private static boolean hasCompactShape(List<SlotFill> inputs) {
            int minRow = 3, maxRow = -1, minCol = 3, maxCol = -1;
            for (int i = 0; i < 9; i++) {
                if (inputs.get(i) != null) {
                    int r = i / 3, c = i % 3;
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                }
            }
            if (maxRow < 0) return false;
            int area = (maxRow - minRow + 1) * (maxCol - minCol + 1);
            int filled = (int) inputs.stream().filter(Objects::nonNull).count();
            return filled > 1 && filled == area;
        }

        private static String shapedJson(SlotFill result, List<SlotFill> inputs) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"type\": \"minecraft:crafting_shaped\",\n");

            int minRow = 3, maxRow = -1, minCol = 3, maxCol = -1;
            for (int i = 0; i < 9; i++) {
                if (inputs.get(i) != null) {
                    int r = i / 3, c = i % 3;
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                }
            }

            sb.append("  \"pattern\": [\n");
            char[][] keyGrid = buildKeyGrid(inputs, minRow, maxRow, minCol, maxCol);
            Map<String, String> keyMap = new LinkedHashMap<>();
            for (int r = minRow; r <= maxRow; r++) {
                StringBuilder row = new StringBuilder("    \"");
                for (int c = minCol; c <= maxCol; c++) {
                    char k = keyGrid[r - minRow][c - minCol];
                    row.append(k == 0 ? ' ' : k);
                }
                row.append("\"");
                if (r < maxRow) row.append(",");
                sb.append(row).append("\n");
            }
            sb.append("  ],\n");

            Map<Character, String> keyToItem = new LinkedHashMap<>();
            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    char k = keyGrid[r - minRow][c - minCol];
                    if (k != 0) {
                        int idx = r * 3 + c;
                        SlotFill fill = inputs.get(idx);
                        keyToItem.putIfAbsent(k, fill.itemId());
                    }
                }
            }

            sb.append("  \"key\": {\n");
            List<Character> keys = new ArrayList<>(keyToItem.keySet());
            for (int i = 0; i < keys.size(); i++) {
                char k = keys.get(i);
                String item = keyToItem.get(k);
                sb.append("    \"").append(k).append("\": {\"item\": \"").append(item).append("\"}");
                if (i < keys.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  },\n");

            appendResult(sb, result, "  ");
            sb.append("\n}\n");
            return sb.toString();
        }

        private static String shapelessJson(SlotFill result, List<SlotFill> inputs) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"type\": \"minecraft:crafting_shapeless\",\n  \"ingredients\": [\n");
            List<SlotFill> filled = inputs.stream().filter(Objects::nonNull).toList();
            for (int i = 0; i < filled.size(); i++) {
                sb.append("    {\"item\": \"").append(filled.get(i).itemId()).append("\"}");
                if (i < filled.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ],\n");
            appendResult(sb, result, "  ");
            sb.append("\n}\n");
            return sb.toString();
        }

        private static String shapedKubeJs(SlotFill result, List<SlotFill> inputs, String recipeId) {
            int minRow = 3, maxRow = -1, minCol = 3, maxCol = -1;
            for (int i = 0; i < 9; i++) {
                if (inputs.get(i) != null) {
                    int r = i / 3, c = i % 3;
                    minRow = Math.min(minRow, r);
                    maxRow = Math.max(maxRow, r);
                    minCol = Math.min(minCol, c);
                    maxCol = Math.max(maxCol, c);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("event.shaped(\n  ");
            appendKubeJsResult(sb, result);
            sb.append(",\n  [\n");
            char[][] keyGrid = buildKeyGrid(inputs, minRow, maxRow, minCol, maxCol);

            Map<Character, String> keyToItem = new LinkedHashMap<>();
            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    char k = keyGrid[r - minRow][c - minCol];
                    if (k != 0) {
                        int idx = r * 3 + c;
                        SlotFill fill = inputs.get(idx);
                        keyToItem.putIfAbsent(k, fill.itemId());
                    }
                }
            }

            for (int r = minRow; r <= maxRow; r++) {
                StringBuilder row = new StringBuilder("    '");
                for (int c = minCol; c <= maxCol; c++) {
                    char k = keyGrid[r - minRow][c - minCol];
                    row.append(k == 0 ? ' ' : k);
                }
                row.append("'");
                if (r < maxRow) row.append(",");
                sb.append(row).append("\n");
            }
            sb.append("  ],\n  {\n");
            List<Character> keys = new ArrayList<>(keyToItem.keySet());
            for (int i = 0; i < keys.size(); i++) {
                char k = keys.get(i);
                String item = keyToItem.get(k);
                sb.append("    '").append(k).append("': '").append(item).append("'");
                if (i < keys.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  }");
            sb.append(")\n");
            if (!recipeId.isBlank()) {
                sb.append("  .id(\"").append(recipeId).append("\")\n");
            }
            return sb.toString();
        }

        private static String shapelessKubeJs(SlotFill result, List<SlotFill> inputs, String recipeId) {
            StringBuilder sb = new StringBuilder();
            sb.append("event.shapeless(\n  ");
            appendKubeJsResult(sb, result);
            sb.append(",\n  [\n");
            List<SlotFill> filled = inputs.stream().filter(Objects::nonNull).toList();
            for (int i = 0; i < filled.size(); i++) {
                sb.append("    '").append(filled.get(i).itemId()).append("'");
                if (i < filled.size() - 1) sb.append(",");
                sb.append("\n");
            }
            sb.append("  ]");
            sb.append(")\n");
            if (!recipeId.isBlank()) {
                sb.append("  .id(\"").append(recipeId).append("\")\n");
            }
            return sb.toString();
        }

        private static char[][] buildKeyGrid(List<SlotFill> inputs, int minRow, int maxRow, int minCol, int maxCol) {
            int rows = maxRow - minRow + 1;
            int cols = maxCol - minCol + 1;
            char[][] grid = new char[rows][cols];
            Map<String, Character> itemToKey = new LinkedHashMap<>();
            char nextKey = 'A';
            for (int r = minRow; r <= maxRow; r++) {
                for (int c = minCol; c <= maxCol; c++) {
                    int idx = r * 3 + c;
                    SlotFill fill = inputs.get(idx);
                    if (fill != null) {
                        String id = fill.itemId();
                        Character existing = itemToKey.get(id);
                        if (existing == null) {
                            existing = nextKey++;
                            itemToKey.put(id, existing);
                        }
                        grid[r - minRow][c - minCol] = existing;
                    }
                }
            }
            return grid;
        }

        private static void appendResult(StringBuilder sb, SlotFill result, String indent) {
            sb.append(indent).append("\"result\": {\n");
            sb.append(indent).append("  \"id\": \"").append(result.itemId()).append("\"");
            if (result.quantity() > 1) {
                sb.append(",\n").append(indent).append("  \"count\": ").append(result.quantity());
            }
            if (!result.data().isEmpty()) {
                sb.append(",\n").append(indent).append("  \"components\": {");
                boolean first = true;
                for (Map.Entry<String, Object> entry : result.data().entrySet()) {
                    if (!first) sb.append(",");
                    sb.append("\n").append(indent).append("    \"").append(entry.getKey()).append("\": ");
                    sb.append(entry.getValue());
                    first = false;
                }
                sb.append("\n").append(indent).append("  }");
            }
            sb.append("\n").append(indent).append("}");
        }

        private static void appendKubeJsResult(StringBuilder sb, SlotFill result) {
            if (result.quantity() > 1 || !result.data().isEmpty()) {
                sb.append("Item.of('").append(result.itemId()).append("', ").append(result.quantity());
                if (!result.data().isEmpty()) {
                    sb.append(", ").append(result.data());
                }
                sb.append(")");
            } else {
                sb.append("'").append(result.itemId()).append("'");
            }
        }
    }

    private static final class CookingGenerator implements TRecipeGenerator {
        private static final List<TGenerationFormat> FORMATS = List.of(
                new TGenerationFormat("json", "JSON"),
                new TGenerationFormat("kubejs", "KubeJS")
        );

        private static final List<TGenerationOption> OPTIONS = List.of(
                new TGenerationOption("cookTime", "Cook Time (ticks)", "text", "", "200"),
                new TGenerationOption("experience", "Experience", "text", "", "0.35"),
                new TGenerationOption("recipeId", "Recipe ID", "text", "mod_id:recipe_name", "")
        );

        @Override
        public @NonNull List<TGenerationFormat> getSupportedFormats() {
            return FORMATS;
        }

        @Override
        public @NonNull List<TGenerationOption> getGenerationOptions() {
            return OPTIONS;
        }

        @Override
        public @NonNull Optional<GenerationTemplates> getGenerationTemplates() {
            return Optional.of(new GenerationTemplates(
                    "type", null, false, null, 0,
                    Map.of(
                            "json", Map.of(
                                    "smelting", "{\n  \"type\": \"minecraft:smelting\",\n  \"ingredient\": {{fill:ingredient:json}},\n  \"result\": {{result:json}},\n  \"experience\": {{option:experience}},\n  \"cookingtime\": {{option:cookTime}}\n}",
                                    "smoking", "{\n  \"type\": \"minecraft:smoking\",\n  \"ingredient\": {{fill:ingredient:json}},\n  \"result\": {{result:json}},\n  \"experience\": {{option:experience}},\n  \"cookingtime\": {{option:cookTime}}\n}",
                                    "blasting", "{\n  \"type\": \"minecraft:blasting\",\n  \"ingredient\": {{fill:ingredient:json}},\n  \"result\": {{result:json}},\n  \"experience\": {{option:experience}},\n  \"cookingtime\": {{option:cookTime}}\n}"
                            ),
                            "kubejs", Map.of(
                                    "smelting", "event.smelting(\n  {{result:kubejs}},\n  {{fill:ingredient:kubejs}}\n){{option:experience:.experience($0)}}{{option:cookTime:.cookingTime($0)}}{{option:recipeId:.id(\"$0\")}}",
                                    "smoking", "event.smoking(\n  {{result:kubejs}},\n  {{fill:ingredient:kubejs}}\n){{option:experience:.experience($0)}}{{option:cookTime:.cookingTime($0)}}{{option:recipeId:.id(\"$0\")}}",
                                    "blasting", "event.blasting(\n  {{result:kubejs}},\n  {{fill:ingredient:kubejs}}\n){{option:experience:.experience($0)}}{{option:cookTime:.cookingTime($0)}}{{option:recipeId:.id(\"$0\")}}"
                            )
                    ),
                    Map.of(
                            "smelting", Map.of("cookTime", "200"),
                            "smoking", Map.of("cookTime", "100"),
                            "blasting", Map.of("cookTime", "100")
                    )
            ));
        }

        @Override
        public @NonNull String generate(Map<String, SlotFill> fills, @NonNull String formatId, @NonNull Map<String, String> options) {
            SlotFill result = fills.get("output");
            if (result == null || result.itemId().isBlank()) {
                return "// Fill the result slot first";
            }

            SlotFill ingredient = fills.get("ingredient");
            if (ingredient == null || ingredient.itemId().isBlank()) {
                return "// Fill the ingredient slot first";
            }

            String type = options.getOrDefault("type", "smelting");
            String recipeTypeId = "minecraft:" + type;
            String cookTime = options.getOrDefault("cookTime", "200");
            String experience = options.getOrDefault("experience", "0.35");
            String recipeId = options.getOrDefault("recipeId", "");

            return switch (formatId) {
                case "json" -> cookingJson(result, ingredient, recipeTypeId, experience, cookTime);
                case "kubejs" -> cookingKubeJs(result, ingredient, type, experience, cookTime, recipeId);
                default -> throw new IllegalArgumentException("Unsupported format: " + formatId);
            };
        }

        private String cookingJson(SlotFill result, SlotFill ingredient, String recipeTypeId, String experience, String cookTime) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"type\": \"").append(recipeTypeId).append("\",\n");
            sb.append("  \"ingredient\": {\"item\": \"").append(ingredient.itemId()).append("\"},\n");
            sb.append("  \"result\": {\n");
            sb.append("    \"id\": \"").append(result.itemId()).append("\"");
            if (result.quantity() > 1) {
                sb.append(",\n    \"count\": ").append(result.quantity());
            }
            sb.append("\n  },\n");
            sb.append("  \"experience\": ").append(experience).append(",\n");
            sb.append("  \"cookingtime\": ").append(cookTime).append("\n");
            sb.append("}\n");
            return sb.toString();
        }

        private String cookingKubeJs(SlotFill result, SlotFill ingredient, String type, String experience, String cookTime, String recipeId) {
            StringBuilder sb = new StringBuilder();
            sb.append("event.").append(type).append("(\n  ");
            if (result.quantity() > 1) {
                sb.append("Item.of('").append(result.itemId()).append("', ").append(result.quantity()).append(")");
            } else {
                sb.append("'").append(result.itemId()).append("'");
            }
            sb.append(",\n  '").append(ingredient.itemId()).append("'\n)");
            if (experience != null && !experience.isBlank()) {
                sb.append("\n  .experience(").append(experience).append(")");
            }
            if (cookTime != null && !cookTime.isBlank()) {
                sb.append("\n  .cookingTime(").append(cookTime).append(")");
            }
            if (!recipeId.isBlank()) {
                sb.append("\n  .id(\"").append(recipeId).append("\")");
            }
            sb.append("\n");
            return sb.toString();
        }
    }

    private static final class CampfireRecipeDescriptor implements TRecipeTypeDescriptor {
        private final List<TRecipeComponent> components = List.of(
                TComponentHelpers.Slot.itemInput("ingredient", 64).withDisplayName("Ingredient").bounds(10, 14, 16, 16),
                TComponentHelpers.Slot.itemOutput("output", 64).withDisplayName("Result").bounds(70, 14, 16, 16),
                TComponentHelpers.GenericDuration.ticks(600).bounds(0, 0, 0, 0)
        );
        private final TUILayout layout = TComponentHelpers.LayoutBuilder.create(100, 44).build();
        private final TRecipeGenerator generator = new CampfireGenerator();
        private final TRecipeDumpAdapter adapter = (recipeObject, registryAccessObject) -> {
            AbstractCookingRecipe recipe = (AbstractCookingRecipe) recipeObject;
            RegistryAccess registryAccess = (RegistryAccess) registryAccessObject;
            TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.descriptor("minecraft:campfire_cooking");

            List<TRenderedValue> inputs = recipe.getIngredients().isEmpty()
                    ? List.of()
                    : renderedValuesForIngredient(recipe.getIngredients().getFirst());
            if (!inputs.isEmpty()) {
                builder.bind("ingredient", inputs);
                inputs.forEach(builder::input);
            }

            TRenderedValue result = renderedValueForStack(recipe.getResultItem(registryAccess));
            if (result != null) {
                builder.bind("output", List.of(result));
                builder.output(result);
            }

            builder.property("cookTime", recipe.getCookingTime());
            return builder.build();
        };

        @Override
        public @NonNull String getRecipeTypeId() {
            return "minecraft:campfire_cooking";
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Campfire Cooking";
        }

        @Override
        public @NonNull List<TRecipeComponent> getComponents() {
            return components;
        }

        @Override
        public @NonNull String getUITexture() {
            return "tritiumcompanion:tgui/campfire.png";
        }

        @Override
        public @NonNull TUILayout getUILayout() {
            return layout;
        }

        @Override
        public @NonNull List<String> getCatalysts() {
            return List.of("minecraft:campfire");
        }

        @Override
        public @NonNull Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() {
            return Optional.of(adapter);
        }

        @Override
        public @NonNull Optional<TRecipeGenerator> getRecipeGenerator() {
            return Optional.of(generator);
        }

        @Override
        public @NonNull List<String> getKubeJsMethodNames() {
            return List.of("campfireCooking");
        }

        @Override
        public @NonNull List<ImportPositionalOption> getImportPositionalOptions() {
            return List.of(
                new ImportPositionalOption("cookTime", 2),
                new ImportPositionalOption("recipeId", 3)
            );
        }
    }

    private static final class ConsoleLogDescriptor implements TLogFileDescriptor
    {
        private final String id;
        private final String displayName;
        private final String color;
        private final String iconTexture;
        private final String path;
        private final Set<String> levels;

        ConsoleLogDescriptor(String id, String displayName, String color, String iconTexture, String path, Set<String> levels) {
            this.id = id;
            this.displayName = displayName;
            this.color = color;
            this.iconTexture = iconTexture;
            this.path = path;
            this.levels = levels;
        }

        @Override
        public @NonNull String getId() {
            return id;
        }

        @Override
        public @NonNull String getDisplayName() {
            return displayName;
        }

        @Override
        public @NonNull String getColor() {
            return color;
        }

        @Override
        public @NonNull String getIconTexture() {
            return iconTexture;
        }

        @Override
        public @NonNull String getPath() {
            return path;
        }

        @Override
        public @NonNull Set<String> getLevels() {
            return levels;
        }

        @Override
        public @NonNull String getModId() {
            return "tritiumcompanion";
        }
    }

    private static final class CampfireGenerator implements TRecipeGenerator {
        private static final List<TGenerationFormat> FORMATS = List.of(
                new TGenerationFormat("json", "JSON"),
                new TGenerationFormat("kubejs", "KubeJS")
        );

        private static final List<TGenerationOption> OPTIONS = List.of(
                new TGenerationOption("cookTime", "Cook Time (ticks)", "text", "", "600"),
                new TGenerationOption("recipeId", "Recipe ID", "text", "mod_id:recipe_name", "")
        );

        @Override
        public @NonNull List<TGenerationFormat> getSupportedFormats() {
            return FORMATS;
        }

        @Override
        public @NonNull List<TGenerationOption> getGenerationOptions() {
            return OPTIONS;
        }

        @Override
        public @NonNull Optional<GenerationTemplates> getGenerationTemplates() {
            return Optional.of(GenerationTemplates.single(Map.of(
                    "json", "{\n  \"type\": \"minecraft:campfire_cooking\",\n  \"ingredient\": {{fill:ingredient:json}},\n  \"result\": {{result:json}},\n  \"cookingtime\": {{option:cookTime}}\n}",
                    "kubejs", "event.campfireCooking(\n  {{result:kubejs}},\n  {{fill:ingredient:kubejs}}\n){{option:cookTime:.cookingTime($0)}}{{option:recipeId:.id(\"$0\")}}"
            )));
        }

        @Override
        public @NonNull String generate(Map<String, SlotFill> fills, @NonNull String formatId, @NonNull Map<String, String> options) {
            SlotFill result = fills.get("output");
            if (result == null || result.itemId().isBlank()) {
                return "// Fill the result slot first";
            }

            SlotFill ingredient = fills.get("ingredient");
            if (ingredient == null || ingredient.itemId().isBlank()) {
                return "// Fill the ingredient slot first";
            }

            String cookTime = options.getOrDefault("cookTime", "600");
            String recipeId = options.getOrDefault("recipeId", "");

            return switch (formatId) {
                case "json" -> campfireJson(result, ingredient, cookTime);
                case "kubejs" -> campfireKubeJs(result, ingredient, cookTime, recipeId);
                default -> throw new IllegalArgumentException("Unsupported format: " + formatId);
            };
        }

        private String campfireJson(SlotFill result, SlotFill ingredient, String cookTime) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"type\": \"minecraft:campfire_cooking\",\n");
            sb.append("  \"ingredient\": {\"item\": \"").append(ingredient.itemId()).append("\"},\n");
            sb.append("  \"result\": {\n");
            sb.append("    \"id\": \"").append(result.itemId()).append("\"");
            if (result.quantity() > 1) {
                sb.append(",\n    \"count\": ").append(result.quantity());
            }
            sb.append("\n  },\n");
            sb.append("  \"cookingtime\": ").append(cookTime).append("\n");
            sb.append("}\n");
            return sb.toString();
        }

        private String campfireKubeJs(SlotFill result, SlotFill ingredient, String cookTime, String recipeId) {
            StringBuilder sb = new StringBuilder();
            sb.append("event.campfireCooking(\n  ");
            if (result.quantity() > 1) {
                sb.append("Item.of('").append(result.itemId()).append("', ").append(result.quantity()).append(")");
            } else {
                sb.append("'").append(result.itemId()).append("'");
            }
            sb.append(",\n  '").append(ingredient.itemId()).append("'\n)");
            if (cookTime != null && !cookTime.isBlank()) {
                sb.append("\n  .cookingTime(").append(cookTime).append(")");
            }
            if (!recipeId.isBlank()) {
                sb.append("\n  .id(\"").append(recipeId).append("\")");
            }
            sb.append("\n");
            return sb.toString();
        }
    }

    private static final class SmithingRecipeDescriptor implements TRecipeTypeDescriptor {
        private final List<TRecipeComponent> components = List.of(
                TComponentHelpers.Slot.itemInput("template", 1).withDisplayName("Template").bounds(10, 14, 16, 16),
                TComponentHelpers.Slot.itemInput("base", 1).withDisplayName("Base").bounds(28, 14, 16, 16),
                TComponentHelpers.Slot.itemInput("addition", 64).withDisplayName("Addition").bounds(46, 14, 16, 16),
                TComponentHelpers.Slot.itemOutput("output", 64).withDisplayName("Result").bounds(104, 14, 16, 16)
        );
        private final TUILayout layout = TComponentHelpers.LayoutBuilder.create(134, 44).build();
        private final TRecipeGenerator generator = new SmithingGenerator();
        private final TRecipeDumpAdapter adapter = (recipeObject, registryAccessObject) -> {
            SmithingTransformRecipe recipe = (SmithingTransformRecipe) recipeObject;
            RegistryAccess registryAccess = (RegistryAccess) registryAccessObject;
            TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.descriptor("minecraft:smithing");

            var ingredients = recipe.getIngredients();
            if (!ingredients.isEmpty() && !ingredients.get(0).isEmpty()) {
                List<TRenderedValue> entries = renderedValuesForIngredient(ingredients.getFirst());
                if (!entries.isEmpty()) {
                    builder.bind("template", entries);
                    entries.forEach(builder::input);
                }
            }
            if (ingredients.size() >= 2 && !ingredients.get(1).isEmpty()) {
                List<TRenderedValue> entries = renderedValuesForIngredient(ingredients.get(1));
                if (!entries.isEmpty()) {
                    builder.bind("base", entries);
                    entries.forEach(builder::input);
                }
            }
            if (ingredients.size() >= 3 && !ingredients.get(2).isEmpty()) {
                List<TRenderedValue> entries = renderedValuesForIngredient(ingredients.get(2));
                if (!entries.isEmpty()) {
                    builder.bind("addition", entries);
                    entries.forEach(builder::input);
                }
            }

            {
                ItemStack result = recipe.getResultItem(registryAccess);
                TRenderedValue rendered = renderedValueForStack(result);
                if (rendered != null) {
                    builder.bind("output", List.of(rendered));
                    builder.output(rendered);
                }
            }

            return builder.build();
        };

        @Override
        public @NonNull String getRecipeTypeId() {
            return "minecraft:smithing";
        }

        @Override
        public @NonNull String getDisplayName() {
            return "Smithing";
        }

        @Override
        public @NonNull List<TRecipeComponent> getComponents() {
            return components;
        }

        @Override
        public @NonNull String getUITexture() {
            return "tritiumcompanion:tgui/smithing.png";
        }

        @Override
        public @NonNull TUILayout getUILayout() {
            return layout;
        }

        @Override
        public @NonNull List<String> getCatalysts() {
            return List.of("minecraft:smithing_table");
        }

        @Override
        public @NonNull Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() {
            return Optional.of(adapter);
        }

        @Override
        public @NonNull Optional<TRecipeGenerator> getRecipeGenerator() {
            return Optional.of(generator);
        }

        @Override
        public @NonNull List<String> getKubeJsMethodNames() {
            return List.of("smithing");
        }
    }

    private static final class SmithingGenerator implements TRecipeGenerator {
        private static final List<TGenerationFormat> FORMATS = List.of(
                new TGenerationFormat("json", "JSON"),
                new TGenerationFormat("kubejs", "KubeJS")
        );

        private static final List<TGenerationOption> OPTIONS = List.of(
                new TGenerationOption("recipeId", "Recipe ID", "text", "mod_id:recipe_name", "")
        );

        @Override
        public @NonNull List<TGenerationFormat> getSupportedFormats() {
            return FORMATS;
        }

        @Override
        public @NonNull List<TGenerationOption> getGenerationOptions() {
            return OPTIONS;
        }

        @Override
        public @NonNull Optional<GenerationTemplates> getGenerationTemplates() {
            return Optional.of(GenerationTemplates.single(Map.of(
                    "json", "{\n  \"type\": \"minecraft:smithing_transform\",\n  \"template\": {{fill:template:json}},\n  \"base\": {{fill:base:json}},\n  \"addition\": {{fill:addition:json}},\n  \"result\": {{result:json}}\n}",
                    "kubejs", "event.smithing(\n  {{result:kubejs}},\n  {{fill:template:kubejs}},\n  {{fill:base:kubejs}},\n  {{fill:addition:kubejs}}\n){{option:recipeId:.id(\"$0\")}}"
            )));
        }

        @Override
        public @NonNull String generate(Map<String, SlotFill> fills, @NonNull String formatId, @NonNull Map<String, String> options) {
            SlotFill template = fills.get("template");
            SlotFill base = fills.get("base");
            SlotFill addition = fills.get("addition");
            SlotFill result = fills.get("output");

            if (result == null || result.itemId().isBlank()) {
                return "// Fill the result slot first";
            }
            if (base == null || base.itemId().isBlank()) {
                return "// Fill the base slot first";
            }

            String recipeId = options.getOrDefault("recipeId", "");

            return switch (formatId) {
                case "json" -> smithingJson(result, template, base, addition);
                case "kubejs" -> smithingKubeJs(result, template, base, addition, recipeId);
                default -> throw new IllegalArgumentException("Unsupported format: " + formatId);
            };
        }

        private String smithingJson(SlotFill result, SlotFill template, SlotFill base, SlotFill addition) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"type\": \"minecraft:smithing_transform\",\n");
            if (template != null && !template.itemId().isBlank()) {
                sb.append("  \"template\": {\"item\": \"").append(template.itemId()).append("\"},\n");
            }
            sb.append("  \"base\": {\"item\": \"").append(base.itemId()).append("\"},\n");
            if (addition != null && !addition.itemId().isBlank()) {
                sb.append("  \"addition\": {\"item\": \"").append(addition.itemId()).append("\"},\n");
            }
            sb.append("  \"result\": {\n");
            sb.append("    \"id\": \"").append(result.itemId()).append("\"");
            if (result.quantity() > 1) {
                sb.append(",\n    \"count\": ").append(result.quantity());
            }
            sb.append("\n  }\n");
            sb.append("}\n");
            return sb.toString();
        }

        private String smithingKubeJs(SlotFill result, SlotFill template, SlotFill base, SlotFill addition, String recipeId) {
            StringBuilder sb = new StringBuilder();
            sb.append("event.smithing(\n  ");
            if (result.quantity() > 1) {
                sb.append("Item.of('").append(result.itemId()).append("', ").append(result.quantity()).append(")");
            } else {
                sb.append("'").append(result.itemId()).append("'");
            }
            sb.append(",\n  ");
            if (template != null && !template.itemId().isBlank()) {
                sb.append("'").append(template.itemId()).append("'");
            } else {
                sb.append("null");
            }
            sb.append(",\n  '").append(base.itemId()).append("'");
            sb.append(",\n  ");
            if (addition != null && !addition.itemId().isBlank()) {
                sb.append("'").append(addition.itemId()).append("'");
            } else {
                sb.append("null");
            }
            sb.append("\n)");
            if (!recipeId.isBlank()) {
                sb.append("\n  .id(\"").append(recipeId).append("\")");
            }
            sb.append("\n");
            return sb.toString();
        }
    }
}
