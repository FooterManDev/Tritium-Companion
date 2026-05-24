package recipe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A dumped value instance for a custom type.
 */
public record TCustomTypeEntry(
        String id,
        String displayName,
        String texturePath,
        Map<String, Object> rawData
)
{
    public TCustomTypeEntry {
        rawData = rawData == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(rawData));
    }
}
