package io.github.tritium_launcher.tritiumcompanion;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import recipe.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class TritiumBuiltinApi
{
    private static boolean registered;

    private TritiumBuiltinApi() {}

    static void registerAll() {
        if (registered) {
            return;
        }
        registered = true;

        TRecipeTypeDescriptorRegistry.register(new CraftingRecipeDescriptor());
        TRecipeTypeDescriptorRegistry.register(new SmeltingRecipeDescriptor());
        TCustomTypeRegistry.register(new FluidTypeProvider());
    }

    static List<TRenderedValue> renderedValuesForIngredient(Ingredient ingredient) {
        JsonElement encoded = Ingredient.CODEC.encodeStart(JsonOps.INSTANCE, ingredient).result().orElse(null);
        if (encoded == null) {
            return List.of();
        }

        List<TRenderedValue> values = new ArrayList<>();
        collectIngredientValues(encoded, values);
        return values;
    }

    static TRenderedValue renderedValueForStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        return TRenderedValue.item(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
    }

    private static void collectIngredientValues(JsonElement element, List<TRenderedValue> values) {
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            array.forEach(child -> collectIngredientValues(child, values));
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        var object = element.getAsJsonObject();
        if (object.has("item")) {
            values.add(TRenderedValue.item(object.get("item").getAsString(), 1));
        }
        if (object.has("tag")) {
            values.add(TRenderedValue.itemTag(object.get("tag").getAsString(), 1));
        }
    }

    private static final class CraftingRecipeDescriptor implements TRecipeTypeDescriptor
    {
        private final List<TRecipeComponent> components = buildComponents();
        private final TUILayout layout = TComponentHelpers.LayoutBuilder.create(176, 166)
                .element("progress_arrow", 89, 34, 24, 17, "HORIZONTAL")
                .build();

        @Override
        public String getRecipeTypeId() {
            return "minecraft:crafting";
        }

        @Override
        public String getDisplayName() {
            return "Crafting";
        }

        @Override
        public List<TRecipeComponent> getComponents() {
            return components;
        }

        @Override
        public String getUITexture() {
            return "minecraft:textures/gui/container/crafting_table.png";
        }

        @Override
        public TUILayout getUILayout() {
            return layout;
        }

        @Override
        public List<String> getCatalysts() {
            return List.of("minecraft:crafting_table");
        }

        @Override
        public java.util.Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() {
            return java.util.Optional.of((recipeObject, registryAccessObject) -> {
                CraftingRecipe recipe = (CraftingRecipe) recipeObject;
                RegistryAccess registryAccess = (RegistryAccess) registryAccessObject;
                TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.descriptor(getRecipeTypeId());

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
            });
        }

        private static List<TRecipeComponent> buildComponents() {
            List<TRecipeComponent> components = new ArrayList<>();
            int startX = 30;
            int startY = 17;
            int spacing = 18;
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    int index = row * 3 + column;
                    components.add(
                            TComponentHelpers.Slot.itemInput("input_" + index, 64)
                                    .withDisplayName("Input " + (index + 1))
                                    .bounds(startX + (column * spacing), startY + (row * spacing), 18, 18)
                    );
                }
            }
            components.add(
                    TComponentHelpers.Slot.itemOutput("output", 64)
                            .withDisplayName("Result")
                            .bounds(124, 35, 18, 18)
            );
            return List.copyOf(components);
        }
    }

    private static final class SmeltingRecipeDescriptor implements TRecipeTypeDescriptor
    {
        private final List<TRecipeComponent> components = List.of(
                TComponentHelpers.Slot.itemInput("ingredient", 64).withDisplayName("Ingredient").bounds(56, 17, 18, 18),
                TComponentHelpers.Slot.itemInput("fuel", 64).withDisplayName("Fuel").bounds(56, 53, 18, 18),
                TComponentHelpers.Slot.itemOutput("output", 64).withDisplayName("Result").bounds(116, 35, 18, 18),
                TComponentHelpers.GenericDuration.ticks(200).bounds(79, 34, 24, 17)
        );
        private final TUILayout layout = TComponentHelpers.LayoutBuilder.create(176, 166)
                .element("fire", 56, 36, 14, 14, "VERTICAL")
                .element("progress_arrow", 79, 34, 24, 17, "HORIZONTAL")
                .build();

        @Override
        public String getRecipeTypeId() {
            return "minecraft:smelting";
        }

        @Override
        public String getDisplayName() {
            return "Smelting";
        }

        @Override
        public List<TRecipeComponent> getComponents() {
            return components;
        }

        @Override
        public String getUITexture() {
            return "minecraft:textures/gui/container/furnace.png";
        }

        @Override
        public TUILayout getUILayout() {
            return layout;
        }

        @Override
        public List<String> getCatalysts() {
            return List.of("minecraft:furnace");
        }

        @Override
        public java.util.Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() {
            return java.util.Optional.of((recipeObject, registryAccessObject) -> {
                AbstractCookingRecipe recipe = (AbstractCookingRecipe) recipeObject;
                RegistryAccess registryAccess = (RegistryAccess) registryAccessObject;
                TNormalizedRecipeView.Builder builder = TNormalizedRecipeView.descriptor(getRecipeTypeId());

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
            });
        }
    }

    private static final class FluidTypeProvider implements TCustomTypeProvider
    {
        private final TCustomTypeDescriptor descriptor = new TCustomTypeDescriptor() {
            @Override
            public String getTypeId() {
                return "tritium:fluid";
            }

            @Override
            public String getDisplayName() {
                return "Fluid";
            }

            @Override
            public Map<String, Object> getMetadata() {
                return Map.of("unit", "mB");
            }
        };

        @Override
        public TCustomTypeDescriptor getDescriptor() {
            return descriptor;
        }

        @Override
        public List<TCustomTypeEntry> dumpValues(Object serverObject) {
            List<TCustomTypeEntry> values = new ArrayList<>();
            BuiltInRegistries.FLUID.forEach(fluid -> {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);

                String displayName = id.toString();
                String texturePath = "";
                var bucket = fluid.getBucket();
                if (bucket instanceof BucketItem) {
                    displayName = bucket.getDefaultInstance().getHoverName().getString();
                    ResourceLocation bucketId = BuiltInRegistries.ITEM.getKey(bucket);
                    texturePath = "assets/textures/" + bucketId.getNamespace() + "/item/" + bucketId.getPath() + ".png";
                }

                Map<String, Object> rawData = new LinkedHashMap<>();
                rawData.put("id", id.toString());
                rawData.put("isSource", fluid.defaultFluidState().isSource());
                values.add(new TCustomTypeEntry(id.toString(), displayName, texturePath, rawData));
            });
            return values;
        }
    }
}
