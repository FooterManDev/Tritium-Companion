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
     * Gets additional data for this component.
     *
     * @return Map of component data
     */
    Map<String, Object> getData();
}
