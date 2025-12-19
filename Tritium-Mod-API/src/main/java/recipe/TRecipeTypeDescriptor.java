package recipe;

import util.TJsonAdapter;

import java.util.List;
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
     * @return List of all ingredient slots/tanks
     */
    List<TIngredientSlot> getInputs();

    /*
     * @return List of all output slots/tanks
     */
    List<TOutputSlot> getOutputs();

    Optional<TEnergyRequirement> getEnergyRequirement();

    String getUITexture();

    TUILayout getUILayout();

    default Optional<TJsonAdapter> getRecipeJsonAdapter() { return Optional.empty(); }

    default Optional<TRecipeProperties> getProperties() {
        return Optional.empty();
    }
}
