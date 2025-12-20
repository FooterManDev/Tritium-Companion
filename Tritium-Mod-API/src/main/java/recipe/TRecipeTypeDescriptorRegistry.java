package recipe;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry for Recipe Type descriptors.
 *
 * @see TRecipeTypeDescriptor
 */
public class TRecipeTypeDescriptorRegistry
{

    /**
     * Internal storage for registered descriptors.
     */
    private static final Map<String, TRecipeTypeDescriptor> DESCRIPTORS = new HashMap<>();

    /**
     * Registers a recipe type descriptor.
     * @param descriptor The descriptor to register
     * @throws IllegalStateException If a descriptor with the same ID is already registered
     * @throws NullPointerException If a descriptor is null
     */
    public static void register(TRecipeTypeDescriptor descriptor) {
        String id = descriptor.getRecipeTypeId();
        if(DESCRIPTORS.containsKey(id)) {
            throw new IllegalStateException("Recipe type descriptor already registered: " + id);
        }
        DESCRIPTORS.put(id, descriptor);
    }

    /**
     * Retrieves a registered descriptor by its recipe type ID.
     *
     * @param recipeTypeId The recipe type ID
     * @return An Optional containing the descriptor if registered or empty otherwise
     */
    public static Optional<TRecipeTypeDescriptor> getDescriptor(String recipeTypeId) {
        return Optional.ofNullable(DESCRIPTORS.get(recipeTypeId));
    }

    /**
     * Checks if a descriptor is registered for the given recipe type ID.
     *
     * @param recipeTypeId The recipe type ID to check
     * @return True if a descriptor is registered
     */
    public static boolean hasDescriptor(String recipeTypeId) {
        return DESCRIPTORS.containsKey(recipeTypeId);
    }

    /**
     * Gets all registered descriptors.
     *
     * @return A new Map of all registered descriptors
     */
    public static Map<String, TRecipeTypeDescriptor> getDescriptors() {
        return new HashMap<>(DESCRIPTORS);
    }

    /**
     * Clears all registered descriptors.
     */
    public static void clear() {
        DESCRIPTORS.clear();
    }
}
