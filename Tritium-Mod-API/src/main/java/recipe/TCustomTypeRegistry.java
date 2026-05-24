package recipe;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry for custom value type providers.
 */
public final class TCustomTypeRegistry
{
    private static final Map<String, TCustomTypeProvider> PROVIDERS = new LinkedHashMap<>();

    private TCustomTypeRegistry() {}

    public static void register(TCustomTypeProvider provider) {
        String id = provider.getDescriptor().getTypeId();
        if (PROVIDERS.containsKey(id)) {
            throw new IllegalStateException("Custom type provider already registered: " + id);
        }
        PROVIDERS.put(id, provider);
    }

    public static Optional<TCustomTypeProvider> getProvider(String typeId) {
        return Optional.ofNullable(PROVIDERS.get(typeId));
    }

    public static Collection<TCustomTypeProvider> getProviders() {
        return PROVIDERS.values();
    }

    public static void clear() {
        PROVIDERS.clear();
    }
}
