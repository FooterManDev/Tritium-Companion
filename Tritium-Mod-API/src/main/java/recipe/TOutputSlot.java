package recipe;

public interface TOutputSlot
{
    String getType();

    String getSlotId();

    long getMaxCapacity();

    default String getDisplayName() {
        return getSlotId();
    }
}
