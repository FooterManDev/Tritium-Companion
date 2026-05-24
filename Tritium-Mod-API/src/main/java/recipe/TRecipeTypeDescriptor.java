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
     * @return User-facing display name for this recipe type.
     */
    default String getDisplayName() {
        return getRecipeTypeId();
    }

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
     * @return An Optional adapter that converts runtime recipes into Tritium's display format.
     */
    default Optional<TRecipeDumpAdapter> getRecipeDumpAdapter() { return Optional.empty(); }

    /**
     * Gets additional metadata for this recipe type.
     * @return Map of metadata
     */
    default Map<String, Object> getMetadata() {
        return Collections.emptyMap();
    }

    /**
     * Gets the catalyst items for this recipe type.
     * @return List of item IDs that act as catalysts for this recipe type
     */
    default List<String> getCatalysts() {
        return Collections.emptyList();
    }
}
