package recipe;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic component representing energy or fuel requirements.
 */
public interface TEnergyComponent extends TRecipeComponent
{
    /**
     * Gets the energy type.
     * <p>
     * Common types: FE, EU, CF, J, μl
     * </p>
     * @return The energy type
     * @see <a href=https://mmkb.pages.dev/energy>Energy Types</a>
     */
    String getEnergyType();

    /**
     * @return The energy amount consumed per operation
     */
    long getAmountPerOperation();

    /**
     * @return True if fuel-based
     */
    default boolean isFuel() {
        return getEnergyType().equals("FUEL");
    }

    @Override
    default String getCategory() {
        return "ENERGY";
    }

    @Override
    default String getId() {
        return "energy";
    }

    @Override
    default Map<String, Object> getData() {
        Map<String, Object> data = new HashMap<>();
        data.put("energyType", getEnergyType());
        data.put("amountPerOperation", getAmountPerOperation());
        data.put("isFuel", isFuel());
        return data;
    }
}
