package recipe;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class TRecipeTypeDescriptorRegistry
{

    private static final Map<String, TRecipeTypeDescriptor> DESCRIPTORS = new HashMap<>();

    public static void register(TRecipeTypeDescriptor descriptor) {
        String id = descriptor.getRecipeTypeId();
        if(DESCRIPTORS.containsKey(id)) {
            throw new IllegalStateException("Recipe type descriptor already registered: " + id);
        }
        DESCRIPTORS.put(id, descriptor);
    }

    public static Optional<TRecipeTypeDescriptor> getDescriptor(String recipeTypeId) {
        return Optional.ofNullable(DESCRIPTORS.get(recipeTypeId));
    }

    public static boolean hasDescriptor(String recipeTypeId) {
        return DESCRIPTORS.containsKey(recipeTypeId);
    }

    public static Map<String, TRecipeTypeDescriptor> getDescriptors() {
        return new HashMap<>(DESCRIPTORS);
    }

    public static void clear() {
        DESCRIPTORS.clear();
    }
}
