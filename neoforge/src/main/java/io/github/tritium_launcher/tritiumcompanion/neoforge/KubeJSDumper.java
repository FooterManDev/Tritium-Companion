package io.github.tritium_launcher.tritiumcompanion.neoforge;

import com.google.flatbuffers.FlatBufferBuilder;
import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.event.EventGroup;
import dev.latvian.mods.kubejs.event.EventGroups;
import dev.latvian.mods.kubejs.event.EventHandler;
import dev.latvian.mods.kubejs.recipe.RecipeFunction;
import dev.latvian.mods.kubejs.recipe.RecipeKey;
import dev.latvian.mods.kubejs.recipe.schema.RecipeNamespace;
import dev.latvian.mods.kubejs.recipe.schema.RecipeSchema;
import dev.latvian.mods.kubejs.script.KubeJSContext;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.typings.Info;
import dev.latvian.mods.kubejs.typings.Param;
import dev.latvian.mods.rhino.NativeJavaClass;
import dev.latvian.mods.rhino.NativeJavaObject;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.util.HideFromJS;
import dev.latvian.mods.rhino.util.ReturnsSelf;
import io.github.tritium_launcher.tritiumcompanion.TCompanion;
import io.github.tritium_launcher.tritiumcompanion.kubejs.*;
import io.github.tritium_launcher.tritiumcompanion.kubejs.Constructor;
import io.github.tritium_launcher.tritiumcompanion.kubejs.Field;
import io.github.tritium_launcher.tritiumcompanion.kubejs.Method;
import io.github.tritium_launcher.tritiumcompanion.kubejs.Parameter;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;

import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class KubeJSDumper {
    private static final int MAX_METHODS = 5000;
    private static final int MAX_FIELDS = 2000;
    private static final int MAX_BINDINGS = 5000;
    private static final int MAX_CLASSES = 20000;

    public static void dump(MinecraftServer server, Path outputDir) {
        if (!isKubeJSLoaded()) return;

        TCompanion.LOGGER.info("Dumping KubeJS typings...");
        try {
            FlatBufferBuilder builder = new FlatBufferBuilder(1024 * 1024);

            List<Integer> bindingOffsets = new ArrayList<>();
            Set<String> seenBindings = new HashSet<>();
            List<Integer> classOffsets = new ArrayList<>();
            LinkedHashMap<String, Class<?>> discoveredClasses = new LinkedHashMap<>();

            enumerateScriptManager(KubeJS.getStartupScriptManager(), "startup", builder, bindingOffsets, seenBindings, discoveredClasses);

            ScriptManager clientMgr = KubeJS.getClientScriptManager();
            if (clientMgr != null) {
                enumerateScriptManager(clientMgr, "client", builder, bindingOffsets, seenBindings, discoveredClasses);
            }

            ScriptManager serverMgr = tryGetServerScriptManager();
            if (serverMgr != null) {
                enumerateScriptManager(serverMgr, "server", builder, bindingOffsets, seenBindings, discoveredClasses);
            }

            discoverEventClasses(discoveredClasses);
            List<Integer> recipeSchemaOffsets = new ArrayList<>(dumpRecipeSchemas(builder, server, discoveredClasses));

            for (Map.Entry<String, Class<?>> entry : discoveredClasses.entrySet()) {
                if (classOffsets.size() >= MAX_CLASSES) break;
                if (isHidden(entry.getValue())) continue;
                addClassDefinition(builder, classOffsets, entry.getValue());
            }

            int mcVersion = builder.createString(SharedConstants.getCurrentVersion().getName());
            int loader = builder.createString(detectLoader());

            int bindingsVec = KubeTypings.createBindingsVector(builder, toIntArray(bindingOffsets));
            int classesVec = KubeTypings.createClassesVector(builder, toIntArray(classOffsets));
            int eventsVec = KubeTypings.createEventsVector(builder, toIntArray(dumpEvents(builder)));
            int recipesVec = KubeTypings.createRecipesVector(builder, toIntArray(recipeSchemaOffsets));

            KubeTypings.startKubeTypings(builder);
            KubeTypings.addMinecraftVersion(builder, mcVersion);
            KubeTypings.addLoader(builder, loader);
            KubeTypings.addBindings(builder, bindingsVec);
            KubeTypings.addClasses(builder, classesVec);
            KubeTypings.addEvents(builder, eventsVec);
            KubeTypings.addRecipes(builder, recipesVec);
            int root = KubeTypings.endKubeTypings(builder);
            builder.finish(root);

            ByteBuffer buf = builder.dataBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);

            Files.write(outputDir.resolve("typings.fb"), bytes);
            TCompanion.LOGGER.info("KubeJS typings dumped ({} bindings, {} classes, {} events, {} recipes, {} bytes)",
                bindingOffsets.size(), classOffsets.size(), 0, 0, bytes.length);
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.error("Failed to dump KubeJS typings", e);
        }
    }

    private static void discoverEventClasses(LinkedHashMap<String, Class<?>> discoveredClasses) {
        try {
            var groups = EventGroups.ALL.get();
            if (groups == null) return;

            for (var entry : groups.map().entrySet()) {
                EventGroup group = entry.getValue();
                for (EventHandler handler : group.getHandlers().values()) {
                    Class<?> eventClass = handler.eventType.get();
                    addTypeHierarchy(eventClass, discoveredClasses);
                }
            }
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.warn("Failed to discover event classes: {}", e.getMessage());
        }
    }

    private static boolean isStdlibPackage(String name) {
        return name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
            || name.startsWith("sun.") || name.startsWith("com.sun.")
            || name.startsWith("org.slf4j") || name.startsWith("org.jetbrains")
            || name.startsWith("org.intellij") || name.startsWith("it.unimi.dsi")
            || name.startsWith("oshi.") || name.startsWith("io.netty");
    }

    private static void addTypeHierarchy(Class<?> clazz, LinkedHashMap<String, Class<?>> discoveredClasses) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray() || clazz.isAnnotation() || clazz.isSynthetic()) return;
        String name = clazz.getName();
        if (name.startsWith("java.lang.invoke")) return;
        if (name.startsWith("jdk.internal")) return;
        if (discoveredClasses.containsKey(name)) return;
        if (discoveredClasses.size() >= MAX_CLASSES) return;

        discoveredClasses.put(name, clazz);

        addTypeHierarchy(clazz.getSuperclass(), discoveredClasses);
        for (Class<?> iface : clazz.getInterfaces()) {
            addTypeHierarchy(iface, discoveredClasses);
        }

        if (isStdlibPackage(name)) return;

        Class<?>[] interfaces;
        try {
            Class<?> superclass = clazz.getSuperclass();
            if (superclass != null) addTypeHierarchy(superclass, discoveredClasses);
            interfaces = clazz.getInterfaces();
            for (Class<?> iface : interfaces) {
                addTypeHierarchy(iface, discoveredClasses);
            }
        } catch (Exception | LinkageError ignored) {

        }

        java.lang.reflect.Method[] declaredMethods;
        try {
            declaredMethods = clazz.getDeclaredMethods();
        } catch (Exception | LinkageError e) {
            return;
        }
        for (java.lang.reflect.Method m : declaredMethods) {
            if (m.isSynthetic() || m.isBridge()) continue;
            try {
                addTypeFromSignature(m.getGenericReturnType(), discoveredClasses);
                for (java.lang.reflect.Type pt : m.getGenericParameterTypes()) {
                    addTypeFromSignature(pt, discoveredClasses);
                }
            } catch (Exception | LinkageError ignored) {

            }
        }
    }

    private static void addTypeFromSignature(java.lang.reflect.Type type, LinkedHashMap<String, Class<?>> discoveredClasses) {
        switch (type) {
            case Class<?> clazz -> addTypeHierarchy(clazz, discoveredClasses);
            case ParameterizedType pt -> {
                addTypeFromSignature(pt.getRawType(), discoveredClasses);
                for (Type arg : pt.getActualTypeArguments()) {
                    addTypeFromSignature(arg, discoveredClasses);
                }
            }
            case WildcardType wt -> {
                for (Type bound : wt.getUpperBounds()) {
                    addTypeFromSignature(bound, discoveredClasses);
                }
            }
            case GenericArrayType gat -> addTypeFromSignature(gat.getGenericComponentType(), discoveredClasses);
            case null, default -> {
            }
        }
    }

    private static ScriptManager tryGetServerScriptManager() {
        try {
            Class<?> ssmClass = Class.forName("dev.latvian.mods.kubejs.server.ServerScriptManager");
            java.lang.reflect.Field f = ssmClass.getDeclaredField("staticInstance");
            f.setAccessible(true);
            Object instance = f.get(null);
            return (ScriptManager) instance;
        } catch (Exception e) {
            return null;
        }
    }

    private static void enumerateScriptManager(ScriptManager scriptManager, String side,
                                                 FlatBufferBuilder builder,
                                                 List<Integer> bindingOffsets,
                                                 Set<String> seenBindings,
                                                 LinkedHashMap<String, Class<?>> discoveredClasses) {
        if (scriptManager == null) return;

        KubeJSContext ctx = (KubeJSContext) scriptManager.contextFactory.enter();
        try {
            Scriptable scope = ctx.topLevelScope;
            if (scope == null) return;

            Object[] ids = scope.getIds(ctx);
            if (ids == null) return;

            for (Object id : ids) {
                if (!(id instanceof String name) || bindingOffsets.size() >= MAX_BINDINGS) continue;
                if (shouldSkipProperty(name)) continue;
                if (!seenBindings.add(name)) continue;

                Object value = scope.get(ctx, name, scope);
                if (value == null) continue;

                Class<?> valueClass;
                if (value instanceof NativeJavaClass njc) {
                    valueClass = njc.getClassObject();
                } else if (value instanceof NativeJavaObject njo) {
                    Object wrapped = njo.unwrap();
                    valueClass = wrapped != null ? wrapped.getClass() : value.getClass();
                } else {
                    valueClass = value.getClass();
                }

                if (valueClass == null) continue;

                Package pkg = valueClass.getPackage();
                if (pkg != null) {
                    String pn = pkg.getName();
                    if (pn.startsWith("java.lang.invoke") || pn.startsWith("jdk.internal")) continue;
                }

                if (isStdlibPackage(valueClass.getName())) continue;

                String className = valueClass.getName();
                String doc = getAnnotationDoc(valueClass.getAnnotationsByType(Info.class));
                addBinding(bindingOffsets, builder, name, className, doc, side);
                discoveredClasses.putIfAbsent(className, valueClass);
            }
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.warn("Failed to enumerate {} bindings: {}", side, e.getMessage());
        }
    }

    private static String getAnnotationDoc(Info[] infos) {
        if (infos == null || infos.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < infos.length; i++) {
            Info info = infos[i];
            if (i > 0) sb.append("\n");
            String val = info.value();
            if (val != null && !val.isEmpty()) sb.append(val);
            Param[] params = info.params();
            if (params != null) {
                for (Param p : params) {
                    String pn = p.name();
                    String pv = p.value();
                    if (pn != null && !pn.isEmpty() && pv != null && !pv.isEmpty()) {
                        sb.append("\n@param ").append(pn).append(" ").append(pv);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String getMethodDoc(java.lang.reflect.Method method) {
        StringBuilder sb = new StringBuilder();
        Info[] infos = method.getAnnotationsByType(Info.class);
        if (infos.length > 0) {
            sb.append(getAnnotationDoc(infos));
        }
        if (method.isAnnotationPresent(Deprecated.class)) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("@deprecated");
        }
        return sb.toString();
    }

    private static String getFieldDoc(java.lang.reflect.Field field) {
        StringBuilder sb = new StringBuilder();
        Info[] infos = field.getAnnotationsByType(Info.class);
        if (infos.length > 0) {
            sb.append(getAnnotationDoc(infos));
        }
        if (field.isAnnotationPresent(Deprecated.class)) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("@deprecated");
        }
        return sb.toString();
    }

    private static String getConstructorDoc(java.lang.reflect.Constructor<?> constructor) {
        StringBuilder sb = new StringBuilder();
        Info[] infos = constructor.getAnnotationsByType(Info.class);
        if (infos.length > 0) {
            sb.append(getAnnotationDoc(infos));
        }
        if (constructor.isAnnotationPresent(Deprecated.class)) {
            if (!sb.isEmpty()) sb.append("\n");
            sb.append("@deprecated");
        }
        return sb.toString();
    }

    private static boolean isHidden(java.lang.reflect.AnnotatedElement element) {
        return element.isAnnotationPresent(HideFromJS.class);
    }

    private static boolean shouldSkipProperty(String name) {
        return name.startsWith("__")
            || name.equals("constructor")
            || name.equals("prototype")
            || name.equals("__proto__")
            || name.equals("_scope")
            || name.equals("className")
            || name.equals("length")
            || name.equals("call")
            || name.equals("apply")
            || name.equals("bind");
    }

    private static String formatType(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz.getName();
        } else if (type instanceof ParameterizedType pt) {
            StringBuilder sb = new StringBuilder(formatType(pt.getRawType()));
            sb.append('<');
            Type[] args = pt.getActualTypeArguments();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(formatType(args[i]));
            }
            sb.append('>');
            return sb.toString();
        } else if (type instanceof TypeVariable<?> tv) {
            return tv.getName();
        } else if (type instanceof WildcardType wt) {
            Type[] lower = wt.getLowerBounds();
            Type[] upper = wt.getUpperBounds();
            if (lower.length > 0 && lower[0] != null && !lower[0].equals(Object.class)) {
                return "? super " + formatType(lower[0]);
            } else if (upper.length > 0 && upper[0] != null && !upper[0].equals(Object.class)) {
                return "? extends " + formatType(upper[0]);
            }
            return "?";
        } else if (type instanceof GenericArrayType gat) {
            return formatType(gat.getGenericComponentType()) + "[]";
        }
        return "java.lang.Object";
    }

    private static List<Integer> dumpEvents(FlatBufferBuilder builder) {
        List<Integer> offsets = new ArrayList<>();
        try {
            var groups = EventGroups.ALL.get();
            if (groups == null) return offsets;

            for (var entry : groups.map().entrySet()) {
                String groupName = entry.getKey();
                EventGroup group = entry.getValue();

                for (EventHandler handler : group.getHandlers().values()) {
                    String eventName = handler.name;
                    String eventClass = handler.eventType.get().getName();
                    String extraType = handler.target != null ? handler.target.type.getName() : "";
                    boolean targetRequired = handler.targetRequired;

                    String doc = "";
                    try {
                        Info[] infos = handler.eventType.get().getAnnotationsByType(Info.class);
                        doc = getAnnotationDoc(infos);
                    } catch (Exception ignored) {}

                    for (ScriptType side : ScriptType.VALUES) {
                        if (!handler.scriptTypePredicate.test(side)) continue;

                        int gn = builder.createString(groupName);
                        int en = builder.createString(eventName);
                        int ec = builder.createString(eventClass);
                        int sd = builder.createString(side.name);
                        int et = builder.createString(extraType);
                        int dc = builder.createString(doc);

                        offsets.add(EventBinding.createEventBinding(builder, gn, en, ec, sd, et, targetRequired, dc));
                    }
                }
            }
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.warn("Failed to dump events: {}", e.getMessage());
        }
        return offsets;
    }

    private static List<Integer> dumpRecipeSchemas(FlatBufferBuilder builder, MinecraftServer server,
                                                    LinkedHashMap<String, Class<?>> discoveredClasses) {
        List<Integer> offsets = new ArrayList<>();
        try {
            if (server == null) return offsets;
            java.lang.reflect.Method getResources = server.getClass().getMethod("getServerResources");
            Object resources = getResources.invoke(server);
            if (resources == null) return offsets;
            java.lang.reflect.Method getManagers = resources.getClass().getMethod("managers");
            Object managers = getManagers.invoke(resources);
            if (managers == null) return offsets;

            java.lang.reflect.Method getSsm = managers.getClass().getMethod("kjs$getServerScriptManager");
            Object serverScriptManager = getSsm.invoke(managers);
            if (serverScriptManager == null) return offsets;

            java.lang.reflect.Field storageField = serverScriptManager.getClass().getField("recipeSchemaStorage");
            Object storage = storageField.get(serverScriptManager);
            if (storage == null) return offsets;

            java.lang.reflect.Field namespacesField = storage.getClass().getField("namespaces");
            @SuppressWarnings("unchecked")
            Map<String, RecipeNamespace> namespaces = (Map<String, RecipeNamespace>) namespacesField.get(storage);
            if (namespaces == null) return offsets;

            for (Map.Entry<String, RecipeNamespace> nsEntry : namespaces.entrySet()) {
                String namespaceId = nsEntry.getKey();
                RecipeNamespace recipeNamespace = nsEntry.getValue();

                for (Map.Entry<String, dev.latvian.mods.kubejs.recipe.schema.RecipeSchemaType> schemaEntry : recipeNamespace.entrySet()) {
                    String schemaId = schemaEntry.getKey();
                    var schemaType = schemaEntry.getValue();
                    RecipeSchema schema = schemaType.schema;
                    if (schema.isHidden()) continue;

                    String recipeClass = schema.recipeFactory != null && schema.recipeFactory.recipeType() != null
                        ? schema.recipeFactory.recipeType().toString() : "";

                    if (!recipeClass.isEmpty() && discoveredClasses.size() < MAX_CLASSES) {
                        try {
                            Class<?> recipeClassObj = Class.forName(recipeClass, false, KubeJSDumper.class.getClassLoader());
                            addTypeHierarchy(recipeClassObj, discoveredClasses);
                        } catch (Exception ignored) {}
                    }

                    List<Integer> keyOffsets = new ArrayList<>();
                    for (RecipeKey<?> key : schema.keys) {
                        if (key.excluded) continue;
                        String keyName = key.getPrimaryFunctionName();
                        if (keyName == null || keyName.isEmpty()) continue;
                        if (!RecipeFunction.isValidIdentifier(keyName.toCharArray())) continue;

                        String keyType = key.component.typeInfo().toString();
                        int kn = builder.createString(keyName);
                        int kt = builder.createString(keyType);
                        keyOffsets.add(RecipeKeyInfo.createRecipeKeyInfo(builder, kn, kt, key.optional()));
                    }

                    int ns = builder.createString(namespaceId);
                    int si = builder.createString(schemaId);
                    int rc = builder.createString(recipeClass);
                    int kv = keyOffsets.isEmpty() ? 0 : RecipeSchemaBinding.createKeysVector(builder, toIntArray(keyOffsets));
                    int dc = builder.createString("");

                    offsets.add(RecipeSchemaBinding.createRecipeSchemaBinding(builder, ns, si, rc, kv, dc));
                }
            }
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.warn("Failed to dump recipe schemas: {}", e.getMessage());
        }
        return offsets;
    }

    private static void addClassDefinition(FlatBufferBuilder builder, List<Integer> classOffsets, Class<?> clazz) {
        try {
            String fullName = clazz.getName();
            String simpleName = clazz.getSimpleName();

            byte kind;
            if (clazz.isInterface()) kind = TypeKind.Interface;
            else if (clazz.isArray()) kind = TypeKind.Array;
            else if (clazz.isEnum() || clazz.isPrimitive()) kind = TypeKind.Primitive;
            else kind = TypeKind.Class;

            List<Integer> typeParamOffsets = new ArrayList<>();
            for (TypeVariable<?> tv : clazz.getTypeParameters()) {
                typeParamOffsets.add(builder.createString(tv.getName()));
            }

            List<Integer> methodOffsets = new ArrayList<>();
            int mc = 0;
            for (java.lang.reflect.Method m : clazz.getMethods()) {
                if (mc++ >= MAX_METHODS) break;
                if (isHidden(m)) continue;
                if (m.isSynthetic() || m.isBridge()) {
                    if (hasNonSyntheticOverride(clazz, m)) continue;
                }
                String mn = m.getName();
                if (mn.startsWith("lambda$") || mn.equals("$deserializeLambda$")) continue;

                List<Integer> paramOffsets = new ArrayList<>();
                Type[] paramTypes = m.getGenericParameterTypes();
                java.lang.reflect.Parameter[] params = m.getParameters();
                for (int i = 0; i < params.length; i++) {
                    String pType = i < paramTypes.length ? formatType(paramTypes[i]) : formatType(params[i].getType());
                    int pn = builder.createString(params[i].getName());
                    int pt = builder.createString(pType);
                    paramOffsets.add(Parameter.createParameter(builder, pn, pt));
                }

                List<Integer> methodTypeParamOffsets = new ArrayList<>();
                for (TypeVariable<?> tv : m.getTypeParameters()) {
                    methodTypeParamOffsets.add(builder.createString(tv.getName()));
                }

                boolean returnsSelf = clazz.isAnnotationPresent(ReturnsSelf.class)
                    || m.isAnnotationPresent(ReturnsSelf.class);

                String returnTypeStr;
                if (returnsSelf) {
                    returnTypeStr = "this";
                } else {
                    returnTypeStr = formatType(m.getGenericReturnType());
                }

                int pv = paramOffsets.isEmpty() ? 0 : Method.createParametersVector(builder, toIntArray(paramOffsets));
                int mtpv = methodTypeParamOffsets.isEmpty() ? 0 : Method.createTypeParamsVector(builder, toIntArray(methodTypeParamOffsets));
                int doc = builder.createString(getMethodDoc(m));

                methodOffsets.add(Method.createMethod(builder,
                    builder.createString(mn),
                    builder.createString(returnTypeStr),
                    mtpv,
                    pv,
                    Modifier.isStatic(m.getModifiers()),
                    m.isAnnotationPresent(Deprecated.class),
                    doc));
            }

            List<Integer> fieldOffsets = new ArrayList<>();
            int fc = 0;
            for (java.lang.reflect.Field f : clazz.getFields()) {
                if (fc++ >= MAX_FIELDS) break;
                if (isHidden(f)) continue;
                fieldOffsets.add(Field.createField(builder,
                    builder.createString(f.getName()),
                    builder.createString(formatType(f.getGenericType())),
                    Modifier.isStatic(f.getModifiers()),
                    f.isAnnotationPresent(Deprecated.class),
                    builder.createString(getFieldDoc(f))));
            }

            List<Integer> constructorOffsets = new ArrayList<>();
            for (java.lang.reflect.Constructor<?> c : clazz.getConstructors()) {
                if (isHidden(c)) continue;

                List<Integer> cParamOffsets = new ArrayList<>();
                Type[] cParamTypes = c.getGenericParameterTypes();
                java.lang.reflect.Parameter[] cParams = c.getParameters();
                for (int i = 0; i < cParams.length; i++) {
                    String pType = i < cParamTypes.length ? formatType(cParamTypes[i]) : formatType(cParams[i].getType());
                    int pn = builder.createString(cParams[i].getName());
                    int pt = builder.createString(pType);
                    cParamOffsets.add(Parameter.createParameter(builder, pn, pt));
                }

                int cpv = cParamOffsets.isEmpty() ? 0 : Constructor.createParametersVector(builder, toIntArray(cParamOffsets));
                constructorOffsets.add(Constructor.createConstructor(builder, cpv,
                    builder.createString(getConstructorDoc(c))));
            }

            List<Integer> interfaceOffsets = new ArrayList<>();
            for (Class<?> iface : clazz.getInterfaces()) {
                interfaceOffsets.add(builder.createString(iface.getName()));
            }

            String superClass = clazz.getSuperclass() != null ? clazz.getSuperclass().getName() : "";

            int tpvo = typeParamOffsets.isEmpty() ? 0 : ClassDefinition.createTypeParamsVector(builder, toIntArray(typeParamOffsets));
            int mv = methodOffsets.isEmpty() ? 0 : ClassDefinition.createMethodsVector(builder, toIntArray(methodOffsets));
            int fv = fieldOffsets.isEmpty() ? 0 : ClassDefinition.createFieldsVector(builder, toIntArray(fieldOffsets));
            int cv = constructorOffsets.isEmpty() ? 0 : ClassDefinition.createConstructorsVector(builder, toIntArray(constructorOffsets));
            int ivo = interfaceOffsets.isEmpty() ? 0 : ClassDefinition.createInterfacesVector(builder, toIntArray(interfaceOffsets));
            int doc = builder.createString(getAnnotationDoc(clazz.getAnnotationsByType(Info.class)));

            classOffsets.add(ClassDefinition.createClassDefinition(builder,
                builder.createString(fullName),
                builder.createString(simpleName),
                kind,
                tpvo,
                mv,
                fv,
                cv,
                doc,
                builder.createString(superClass),
                ivo));
        } catch (Exception | LinkageError e) {
            TCompanion.LOGGER.warn("Failed to reflect {}: {}", clazz.getName(), e.getMessage());
        }
    }

    private static boolean hasNonSyntheticOverride(Class<?> clazz, java.lang.reflect.Method syntheticMethod) {
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m == syntheticMethod || m.isSynthetic() || m.isBridge()) continue;
            if (m.getName().equals(syntheticMethod.getName())
                && m.getParameterCount() == syntheticMethod.getParameterCount()) {
                boolean paramsMatch = true;
                for (int i = 0; i < m.getParameterCount(); i++) {
                    if (!m.getParameterTypes()[i].equals(syntheticMethod.getParameterTypes()[i])) {
                        paramsMatch = false;
                        break;
                    }
                }
                if (paramsMatch) return true;
            }
        }
        return false;
    }

    private static boolean isKubeJSLoaded() {
        try {
            Class.forName("dev.latvian.mods.kubejs.KubeJS");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static String detectLoader() {
        try {
            Class.forName("net.neoforged.fml.loading.FMLLoader");
            return "neoforge";
        } catch (ClassNotFoundException e) {
            return "fabric";
        }
    }

    private static void addBinding(List<Integer> offsets, FlatBufferBuilder builder, String name, String type, String doc, String side) {
        offsets.add(Binding.createBinding(builder,
            builder.createString(name),
            builder.createString(type),
            builder.createString(doc),
            builder.createString(side)));
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }
}
