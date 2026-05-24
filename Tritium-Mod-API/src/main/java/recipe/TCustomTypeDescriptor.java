package recipe;

import java.util.Collections;
import java.util.Map;

/**
 * Metadata for a custom value type that Tritium can render.
 */
public interface TCustomTypeDescriptor
{
    String getTypeId();

    String getDisplayName();

    default String getIconTexture() {
        return "";
    }

    default boolean isBrowseable() {
        return true;
    }

    default Map<String, Object> getMetadata() {
        return Collections.emptyMap();
    }
}
