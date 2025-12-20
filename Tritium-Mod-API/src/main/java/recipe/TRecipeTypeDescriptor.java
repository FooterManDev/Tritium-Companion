package recipe;

import util.TJsonAdapter;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Descriptions for Mod's custom recipe types.
 */
public interface TRecipeTypeDescriptor
{
    /**
     * @return The recipe type ID
     */
    String getRecipeTypeId();

    /**
     * Gets all components that define this recipe type.
     * @return List of all ingredient slots/tanks
     * @see TRecipeComponent
     */
    List<TRecipeComponent> getComponents();

    /**
     * @return The texture path
     */
    String getUITexture();

    /**
     * @return The UI layout
     * @see TUILayout
     */
    TUILayout getUILayout();

    /**
     * @return An Optional of the JSON adapter or empty
     * @see TJsonAdapter
     */
    default Optional<TJsonAdapter> getRecipeJsonAdapter() { return Optional.empty(); }

    /**
     * Gets additional metadata for this recipe type.
     * @return Map of metadata
     */
    default Map<String, Object> getMetadata() {
        return Collections.emptyMap();
    }
}
