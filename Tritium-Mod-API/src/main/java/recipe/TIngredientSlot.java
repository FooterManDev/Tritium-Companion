package recipe;

public interface TIngredientSlot
{
    String getType();

    String getSlotId();

    long getMaxCapacity();

    default String getDisplayName() {
        return getSlotId();
    }
}
