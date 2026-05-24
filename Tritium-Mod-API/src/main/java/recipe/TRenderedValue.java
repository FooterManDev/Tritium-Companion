package recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A normalized recipe ingredient/result entry for Tritium rendering.
 */
public record TRenderedValue(
        String refType,
        String valueType,
        String id,
        long amount,
        String displayName,
        Map<String, Object> metadata
)
{
    public TRenderedValue {
        metadata = metadata == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    public static TRenderedValue item(String id, long amount) {
        return new TRenderedValue("item", "item", id, amount, id, Collections.emptyMap());
    }

    public static TRenderedValue itemTag(String id, long amount) {
        return new TRenderedValue("tag", "item", id, amount, "#" + id, Collections.emptyMap());
    }

    public static TRenderedValue value(String valueType, String id, long amount, String displayName) {
        return new TRenderedValue("value", valueType, id, amount, displayName, Collections.emptyMap());
    }

    public TRenderedValue withMeta(String key, Object value) {
        LinkedHashMap<String, Object> next = new LinkedHashMap<>(metadata);
        next.put(key, value);
        return new TRenderedValue(refType, valueType, id, amount, displayName, next);
    }
}
