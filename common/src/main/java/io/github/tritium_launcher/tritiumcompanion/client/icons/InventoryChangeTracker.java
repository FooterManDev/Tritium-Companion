package io.github.tritium_launcher.tritiumcompanion.client.icons;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.tritium_launcher.tritiumcompanion.CompanionSocketBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.TypedDataComponent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * Watches the player's inventory and broadcasts an {@code inventory_changed} payload over
 * the websocket whenever any slot changed from the last sent snapshot.
 */
public final class InventoryChangeTracker
{
    private static final long THROTTLE_MS = 500;
    private static final int MAIN_SLOTS = 36;
    private static final int ARMOR_SLOTS = 4;
    private static final int OFFHAND_SLOTS = 1;
    private static final int TOTAL_SLOTS = MAIN_SLOTS + ARMOR_SLOTS + OFFHAND_SLOTS;

    private static String[] lastItemIds = new String[TOTAL_SLOTS];
    private static int[] lastCounts = new int[TOTAL_SLOTS];
    private static DataComponentMap[] lastComponentMaps = new DataComponentMap[TOTAL_SLOTS];
    private static long lastBroadcastMs;

    private InventoryChangeTracker() {}

    /**
     * True once the active inventory has been recorded
     */
    private static boolean captured;

    /**
     * Reads the current inventory and emits a broadcast
     * when it changed and enough time has elapsed.
     */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Inventory inv = mc.player.getInventory();
        String[] ids = new String[TOTAL_SLOTS];
        int[] counts = new int[TOTAL_SLOTS];
        DataComponentMap[] maps = new DataComponentMap[TOTAL_SLOTS];
        readSlots(inv, ids, counts, maps);

        boolean changed = !captured;
        if (!changed) {
            for (int i = 0; i < TOTAL_SLOTS; i++) {
                if (!Objects.equals(ids[i], lastItemIds[i]) || counts[i] != lastCounts[i]
                        || !componentsEqualIgnoringDamage(maps[i], lastComponentMaps[i])) /* Ignore Damage to prevent miniscule
                                                                                             changes from being constantly exported
                                                                                          */
                {

                    changed = true;
                    break;
                }
            }
        }
        if (!changed) return;

        long now = System.currentTimeMillis();
        if (now - lastBroadcastMs < THROTTLE_MS && captured) return;

        lastBroadcastMs = now;
        lastItemIds = ids;
        lastCounts = counts;
        lastComponentMaps = maps;
        captured = true;

        broadcast(inv);
    }

    /**
     * Forces an immediate broadcast of the current inventory.
     */
    public static void flush() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        lastBroadcastMs = 0;
        captured = false;
        tick();
    }

    private static void readSlots(Inventory inv, String[] ids, int[] counts, DataComponentMap[] maps) {
        for (int i = 0; i < MAIN_SLOTS; i++) {
            ItemStack stack = inv.getItem(i);
            ids[i] = stack.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts[i] = stack.getCount();
            maps[i] = stack.getComponents();
        }
        for (int i = 0; i < ARMOR_SLOTS; i++) {
            int idx = MAIN_SLOTS + i;
            ItemStack stack = inv.getArmor(i);
            ids[idx] = stack.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts[idx] = stack.getCount();
            maps[idx] = stack.getComponents();
        }
        ItemStack offhand = inv.offhand.getFirst();
        int offIdx = MAIN_SLOTS + ARMOR_SLOTS;
        ids[offIdx] = offhand.isEmpty() ? "" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString();
        counts[offIdx] = offhand.getCount();
        maps[offIdx] = offhand.getComponents();
    }

    private static boolean componentsEqualIgnoringDamage(DataComponentMap a, DataComponentMap b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        boolean aHasNonDamage = false;
        boolean bHasNonDamage = false;
        for (TypedDataComponent<?> comp : a) {
            if (comp.type() == DataComponents.DAMAGE) continue;
            aHasNonDamage = true;
            Object other = b.get(comp.type());
            if (!Objects.equals(comp.value(), other)) return false;
        }
        for (TypedDataComponent<?> comp : b) {
            if (comp.type() == DataComponents.DAMAGE) continue;
            bHasNonDamage = true;
            if (a.get(comp.type()) == null) return false;
        }
        return aHasNonDamage == bHasNonDamage;
    }

    private static void broadcast(Inventory inv) {
        JsonArray slots = new JsonArray();
        for (int i = 0; i < TOTAL_SLOTS; i++) {
            JsonObject slot = new JsonObject();
            slot.addProperty("index", i);
            ItemStack stack = itemAt(inv, i);
            if (!stack.isEmpty()) {
                JsonObject item = ItemDataSerializer.toJson(stack);
                if (item != null) {
                    item.addProperty("nbtFormatted", ItemDataSerializer.formatComponents(stack).stripTrailing());
                    slot.add("item", item);
                }
            }
            slots.add(slot);
        }

        JsonObject data = new JsonObject();
        data.addProperty("main", MAIN_SLOTS);
        data.addProperty("armor", ARMOR_SLOTS);
        data.addProperty("offhand", OFFHAND_SLOTS);
        data.add("slots", slots);

        JsonObject push = new JsonObject();
        push.addProperty("id", "");
        push.addProperty("action", "inventory_changed");
        push.addProperty("ok", true);
        push.addProperty("message", "ok");
        push.add("data", data);

        CompanionSocketBridge.broadcast(push);
    }

    private static ItemStack itemAt(Inventory inv, int index) {
        if (index < MAIN_SLOTS) return inv.getItem(index);
        if (index < MAIN_SLOTS + ARMOR_SLOTS) return inv.getArmor(index - MAIN_SLOTS);
        return inv.offhand.getFirst();
    }
}
