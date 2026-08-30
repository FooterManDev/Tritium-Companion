package io.github.tritium_launcher.tritiumcompanion.client.options;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import io.github.tritium_launcher.tritiumcompanion.mixin.SliderableValueSetAccessor;
import io.github.tritium_launcher.tritiumcompanion.mixin.TooltipAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import io.github.tritium_launcher.tritiumcompanion.TCompanion;

import io.github.tritium_launcher.tritiumcompanion.mixin.OptionInstanceAccessor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.sounds.SoundSource;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class OptionsExporter
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private OptionsExporter() {}

    public static void writeExport() {
        writeExport(Minecraft.getInstance().gameDirectory.toPath().resolve(".tr/options_export.json"));
    }

    public static void writeExport(Path outputPath) {
        try {
            Files.createDirectories(outputPath.getParent());
            JsonObject json = buildExport();
            try (var writer = Files.newBufferedWriter(outputPath)) {
                GSON.toJson(json, writer);
            }
            TCompanion.LOGGER.info("Exported options to {}", outputPath);
        } catch (Exception e) {
            TCompanion.LOGGER.error("Failed to export options", e);
        }
    }

    public static JsonObject buildExport() {
        Options options = Minecraft.getInstance().options;
        JsonObject root = new JsonObject();
        root.addProperty("type", "options_export");

        List<CapturedOption> captured = captureOptions(options);
        Map<String, String> categories = detectCategories(options, captured);

        List<String> categoryOrder = List.of(
            "Video", "Sound", "Controls", "Chat", "Accessibility",
            "Skin", "Language", "Multiplayer");
        Map<String, Integer> catRank = new HashMap<>();
        for (int i = 0; i < categoryOrder.size(); i++) {
            catRank.put(categoryOrder.get(i), i);
        }

        captured.sort(Comparator
            .comparingInt((CapturedOption o) -> {
                String cat = categories.getOrDefault(o.key(), "Other");
                return catRank.getOrDefault(cat, Integer.MAX_VALUE);
            })
            .thenComparing(CapturedOption::key));

        JsonArray arr = new JsonArray();
        for (CapturedOption opt : captured) {
            arr.add(serialize(opt, categories));
        }
        root.add("options", arr);
        return root;
    }

    private static List<CapturedOption> captureOptions(Options options) {
        Class<?> fieldAccessClass = findFieldAccess();
        List<CapturedOption> captured = new ArrayList<>();

        Object proxy = Proxy.newProxyInstance(
            OptionsExporter.class.getClassLoader(),
            new Class<?>[] { fieldAccessClass },
            new CapturingHandler(captured)
        );

        try {
            Method processOptions = Options.class.getDeclaredMethod("processOptions", fieldAccessClass);
            processOptions.setAccessible(true);
            processOptions.invoke(options, proxy);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            TCompanion.LOGGER.error("Failed to export options", e);
        }

        return captured;
    }

    private static Class<?> findFieldAccess() {
        for (Class<?> inner : Options.class.getDeclaredClasses()) {
            if ("net.minecraft.client.Options$FieldAccess".equals(inner.getName())) {
                return inner;
            }
        }
        throw new RuntimeException("Could not find Options.FieldAccess");
    }

    private record CapturedOption(String key, OptionInstance<?> opt) {}

    private record CapturingHandler(List<CapturedOption> captured) implements InvocationHandler
    {

        @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();

            switch (name) {
                case "toString" -> {
                    return "OptionsExporter proxy";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
            }

            if (name.equals("process") && args.length == 2 && args[1] instanceof OptionInstance<?> opt) {
                    captured.add(new CapturedOption((String) args[0], opt));
                    return null;
                }

                if (args != null && args.length >= 2 && args[0] instanceof String) {
                    return args[1];
                }
                if (args != null && args.length >= 2) {
                    return args[1];
                }
                return null;
            }
        }

    private static Map<String, String> detectCategories(Options options, List<CapturedOption> captured) {
        Map<OptionInstance<?>, String> optToKey = new IdentityHashMap<>();
        for (CapturedOption co : captured) {
            optToKey.put(co.opt(), co.key());
        }

        Map<String, String> categories = new HashMap<>();

        record ScreenEntry(String className, String methodName, String category) {}
        ScreenEntry[] screens = {
            new ScreenEntry("net.minecraft.client.gui.screens.options.VideoSettingsScreen", "options", "Video"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.SoundOptionsScreen", "buttonOptions", "Sound"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.MouseSettingsScreen", "options", "Controls"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.ChatOptionsScreen", "options", "Chat"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.AccessibilityOptionsScreen", "options", "Accessibility"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.SkinCustomizationScreen", "options", "Skin"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.FontOptionsScreen", "options", "Language"),
            new ScreenEntry("net.minecraft.client.gui.screens.options.OnlineOptionsScreen", "options", "Multiplayer"),
        };

        for (ScreenEntry se : screens) {
            try {
                Class<?> clazz = Class.forName(se.className);
                Method m = clazz.getDeclaredMethod(se.methodName, Options.class);
                m.setAccessible(true);
                OptionInstance<?>[] opts = (OptionInstance<?>[]) m.invoke(null, options);
                for (OptionInstance<?> opt : opts) {
                    String key = optToKey.get(opt);
                    if (key != null && !categories.containsKey(key)) {
                        categories.put(key, se.category);
                    }
                }
            } catch (Exception e) {
                TCompanion.LOGGER.warn("Category detection failed for {}::{}", se.className, se.methodName, e);
            }
        }

        try {
            Class<?> soundSourceClass = Class.forName("net.minecraft.sounds.SoundSource");
            Method getSoundSource = Options.class.getMethod("getSoundSourceOptionInstance", soundSourceClass);
            Object[] sources = soundSourceClass.getEnumConstants();
            if (sources != null) {
                for (Object source : sources) {
                    OptionInstance<?> opt = (OptionInstance<?>) getSoundSource.invoke(options, (SoundSource) source);
                    String key = optToKey.get(opt);
                    if (key != null && !categories.containsKey(key)) {
                        categories.put(key, "Sound");
                    }
                }
            }
        } catch (Exception e) {
            TCompanion.LOGGER.warn("Failed to detect sound source categories", e);
        }

        for (var entry : optToKey.entrySet()) {
            String key = entry.getValue();
            if (categories.containsKey(key)) continue;
            if (key.startsWith("soundCategory_") || key.equals("soundDevice")) {
                categories.put(key, "Sound");
            } else if (key.startsWith("modelPart_") || key.equals("mainHand")) {
                categories.put(key, "Skin");
            } else {
                categories.put(key, "Other");
            }
        }

        return categories;
    }

    private static <T> JsonObject serialize(CapturedOption captured, Map<String, String> categories) {
        @SuppressWarnings("unchecked")
        OptionInstance<T> opt = (OptionInstance<T>) captured.opt();
        String key = captured.key();

        JsonObject entry = new JsonObject();
        entry.addProperty("key", key);
        entry.addProperty("caption", opt.toString());

        opt.codec()
            .encodeStart(JsonOps.INSTANCE, opt.get())
            .ifError(err -> TCompanion.LOGGER.warn("Failed to encode option '{}': {}", key, err))
            .ifSuccess(json -> entry.add("value", json));

        entry.add("schema", serializeSchema(opt));

        String category = categories.getOrDefault(key, "Other");
        entry.addProperty("category", category);

        String tooltip = resolveTooltip(opt);
        if (tooltip != null) {
            entry.addProperty("tooltip", tooltip);
        }

        return entry;
    }

    private static <T> String resolveTooltip(OptionInstance<T> opt) {
        try {
            @SuppressWarnings("unchecked")
            OptionInstance.TooltipSupplier<T> supplier =
                    (OptionInstance.TooltipSupplier<T>) ((OptionInstanceAccessor)(Object) opt).tooltip();
            if (supplier == null) return null;

            Object tooltipObj = supplier.apply(opt.get());
            if (tooltipObj == null) return null;

            if(tooltipObj instanceof Tooltip t) {
                return ((TooltipAccessor) t).getMessage().getString();
            }

            TCompanion.LOGGER.warn("Unexpected tooltip type: {}", tooltipObj.getClass().getName());
            return null;
        } catch (Exception e) {
            TCompanion.LOGGER.warn("Failed to resolve tooltip for option", e);
            return null;
        }
    }

    private static JsonObject serializeSchema(OptionInstance<?> opt) {
        JsonObject schema = new JsonObject();
        Object values = opt.values();

        switch (values) {
            case OptionInstance.Enum<?> e -> {
                schema.addProperty("type", "enum");
                schema.addProperty("control", "cycling");
                JsonArray possible = new JsonArray();
                for (Object v : e.values()) {
                    possible.add(v instanceof Enum<?> ev ? ev.name() : v.toString());
                }
                schema.add("values", possible);

            }
            case OptionInstance.AltEnum<?> a -> {
                schema.addProperty("type", "enum");
                schema.addProperty("control", "cycling");
                JsonArray possible = new JsonArray();
                for (Object v : a.values()) {
                    possible.add(v instanceof Enum<?> ev ? ev.name() : v.toString());
                }
                schema.add("values", possible);

            }
            case OptionInstance.LazyEnum<?> l -> {
                schema.addProperty("type", "enum");
                schema.addProperty("control", "cycling");
                try {
                    List<?> resolved = l.values().get();
                    JsonArray possible = new JsonArray();
                    for (Object v : resolved) {
                        possible.add(v instanceof Enum<?> ev ? ev.name() : v.toString());
                    }
                    schema.add("values", possible);
                } catch (Exception e) {
                    schema.addProperty("values_note", "lazy — evaluate at runtime");
                }
            }
            case OptionInstance.IntRange ir -> {
                schema.addProperty("type", "int");
                schema.addProperty("control", "slider");
                schema.addProperty("min", ir.minInclusive());
                schema.addProperty("max", ir.maxInclusive());
            }
            case OptionInstance.ClampingLazyMaxIntRange cr -> {
                schema.addProperty("type", "int");
                schema.addProperty("control", "slider");
                schema.addProperty("min", cr.minInclusive());
                schema.addProperty("max", cr.encodableMaxInclusive());
            }
            case OptionInstance.UnitDouble unitDouble -> {
                schema.addProperty("type", "double");
                schema.addProperty("control", "slider");
                schema.addProperty("min", 0.0);
                schema.addProperty("max", 1.0);
            }
            case SliderableValueSetAccessor sv -> {
                Object min = sv.invokeFromSliderValue(0.0);
                Object max = sv.invokeFromSliderValue(1.0);
                if (min instanceof Integer) {
                    schema.addProperty("type", "int");
                    schema.addProperty("min", ((Integer) min));
                    schema.addProperty("max", ((Integer) max));
                } else {
                    schema.addProperty("type", "double");
                    schema.addProperty("min", ((Number) min).doubleValue());
                    schema.addProperty("max", ((Number) max).doubleValue());
                }
                schema.addProperty("control", "slider");
            }
            default ->
                    detectSliderFallback(schema, values);
        }

        return schema;
    }

    private static void detectSliderFallback(JsonObject schema, Object values) {
        try {
            Class<?> vc = values.getClass();
            Method fromSlider = vc.getMethod("fromSliderValue", double.class);
            Object minObj = fromSlider.invoke(values, 0.0);
            Object maxObj = fromSlider.invoke(values, 1.0);
            if (!(minObj instanceof Number) || !(maxObj instanceof Number)) {
                schema.addProperty("type", "unknown");
                schema.addProperty("control", "unknown");
                return;
            }
            double min = ((Number) minObj).doubleValue();
            double max = ((Number) maxObj).doubleValue();
            if (minObj instanceof Integer) {
                schema.addProperty("type", "int");
            } else {
                schema.addProperty("type", "double");
            }
            schema.addProperty("control", "slider");
            schema.addProperty("min", min);
            schema.addProperty("max", max);
        } catch (Exception e) {
            schema.addProperty("type", "unknown");
            schema.addProperty("control", "unknown");
        }
    }
}
