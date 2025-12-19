package recipe;

import java.util.Optional;

public interface TRecipeProperties
{
    default Optional<Integer> getDuration() {
        return Optional.empty();
    }

    default java.util.Map<String, Object> getCustomProperties() {
        return java.util.Collections.emptyMap();
    }
}
