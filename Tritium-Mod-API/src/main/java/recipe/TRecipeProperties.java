package recipe;

import java.util.Optional;

/**
 * Additional properties for Recipe Types.
 */
public interface TRecipeProperties
{
    /**
     * @return An Optional of the recipe duration in ticks or empty
     */
    default Optional<Integer> getDuration() {
        return Optional.empty();
    }

    /**
     * @return A Map of custom property names to values
     */
    default java.util.Map<String, Object> getCustomProperties() {
        return java.util.Collections.emptyMap();
    }
}
