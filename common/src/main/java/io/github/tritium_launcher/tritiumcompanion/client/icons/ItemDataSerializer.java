package io.github.tritium_launcher.tritiumcompanion.client.icons;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.function.Consumer;

public final class ItemDataSerializer
{
    private ItemDataSerializer() {}

    public static void appendIdentity(ItemStack stack, JsonObject out) {
        if (stack.isEmpty()) return;
        out.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        out.addProperty("count", stack.getCount());
        out.addProperty("displayName", stack.getDisplayName().getString());
    }

    public static void appendComponents(ItemStack stack, JsonObject out) {
        if (stack.isEmpty()) return;
        String formatted = formatComponents(stack);
        if (!formatted.isBlank()) {
            out.addProperty("componentsFormatted", formatted);
        }
    }

    public static String formatComponents(ItemStack stack) {
        if (stack.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (TypedDataComponent<?> comp : stack.getComponents()) {
            String key = String.valueOf(comp.type());
            try {
                @SuppressWarnings("unchecked")
                Codec<Object> codec = (Codec<Object>) comp.type().codec();
                Tag tag = (Tag) codec.encodeStart(NbtOps.INSTANCE, comp.value()).getOrThrow();
                sb.append("  ").append(key).append(": ").append(NbtUtils.toPrettyComponent(tag).getString()).append("\n");
            } catch (Exception e) {
                String fallback = String.valueOf(comp.value());
                sb.append("  ").append(key).append(": ").append(fallback).append("\n");
            }
        }
        return sb.toString();
    }

    public static JsonObject toJson(ItemStack stack) {
        if (stack.isEmpty()) return null;
        JsonObject out = new JsonObject();
        appendIdentity(stack, out);
        appendComponents(stack, out);
        return out;
    }
}
