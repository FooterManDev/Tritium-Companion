package recipe;

/**
 * Converts runtime recipes into Tritium's normalized recipe display model.
 */
public interface TRecipeDumpAdapter
{
    /**
     * Builds a normalized display definition for the provided recipe instance.
     *
     * @param recipe runtime recipe object
     * @param registryAccess runtime registry access object
     * @return normalized display data
     */
    TNormalizedRecipeView dumpRecipe(Object recipe, Object registryAccess);
}
