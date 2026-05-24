package recipe;

import java.util.HashMap;
import java.util.Map;

/**
 * Component representing a slot or tank.
 * <p>
 * Slots can hold Items, Fluids, or any other transferable resource.
 * </p>
 */
public interface TSlotComponent extends TRecipeComponent
{
    /**
     * @return The slot type
     */
    String getSlotType();

    /**
     * @return True if input, false if output
     */
    boolean isInput();

    /**
     * Gets the maximum capacity.
     * <p>
     * Item stack size is usually 1 to 64.
     * <br>
     * (1000 mB or 81000 Droplets) == 1 Bucket
     * </p>
     *
     * @return The maximum capacity
     * @see <a href="https://mmkb.pages.dev/fluids.html">Fluid Units Explained</a>
     */
    long getMaxCapacity();

    /**
     * @return The display name string
     */
    String getDisplayName();

    @Override
    default String getCategory() {
        return "SLOT";
    }

    @Override
    default Map<String, Object> getData() {
        Map<String, Object> data = new HashMap<>();
        data.put("slotType", getSlotType());
        data.put("valueType", getSlotType().toLowerCase());
        data.put("isInput", isInput());
        data.put("maxCapacity", getMaxCapacity());
        data.put("displayName", getDisplayName());
        return data;
    }
}
