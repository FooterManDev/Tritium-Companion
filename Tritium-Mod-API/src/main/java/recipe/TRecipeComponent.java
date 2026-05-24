package recipe;

import java.util.Map;

/**
 * Recipe Components represent inventory in a recipe, such as
 * input / output Item slots, Energy cells, Fluid tanks, Time,
 * or custom mechanics.
 * <p>
 * Implementations should extend this to create specific component
 * types if necessary, such as:
 * <ul>
 *     <li>Custom Energy system</li>
 *     <li>Custom resources, like Gasses</li>
 * </ul>
 */
public interface TRecipeComponent
{
    /**
     * Gets the component category.
     * <p>
     * General categories: SLOT, ENERGY, DURATION
     * </p>
     * @return The component category string
     */
    String getCategory();

    /**
     * Gets the ID of this component.
     * @return The component ID string
     */
    String getId();

    /**
     * @return X coordinate on the recipe background texture.
     */
    default int x() {
        return 0;
    }

    /**
     * @return Y coordinate on the recipe background texture.
     */
    default int y() {
        return 0;
    }

    /**
     * @return Render width in pixels.
     */
    default int width() {
        return 18;
    }

    /**
     * @return Render height in pixels.
     */
    default int height() {
        return 18;
    }

    /**
     * Gets additional data for this component.
     *
     * @return Map of component data
     */
    Map<String, Object> getData();
}
