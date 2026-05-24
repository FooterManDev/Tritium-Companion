package recipe;

import java.util.Collections;
import java.util.List;

/**
 * Associates rendered values with a specific component in a recipe descriptor.
 */
public record TRecipeBinding(String componentId, List<TRenderedValue> entries)
{
    public TRecipeBinding {
        entries = entries == null ? Collections.emptyList() : List.copyOf(entries);
    }

    public static TRecipeBinding of(String componentId, List<TRenderedValue> entries) {
        return new TRecipeBinding(componentId, entries);
    }
}
