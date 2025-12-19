package recipe;

public interface TEnergyRequirement
{
    String getEnergyType();

    long getAmountPerOperation();

    default boolean hasFuelSlot() {
        return getEnergyType().equals("FUEL");
    }
}
