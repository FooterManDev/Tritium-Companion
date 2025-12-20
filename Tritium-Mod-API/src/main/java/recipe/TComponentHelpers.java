package recipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple implementations of recipe components.
 */
public class TComponentHelpers
{
    /**
     * Generic Slot component impl.
     */
    public static class Slot implements TSlotComponent
    {
        private final String slotType;
        private final String slotId;
        private final boolean isInput;
        private final long maxCapacity;
        private final String displayName;

        public Slot(String slotType, String slotId, boolean isInput, long maxCapacity, String displayName) {
            this.slotType = slotType;
            this.slotId = slotId;
            this.isInput = isInput;
            this.maxCapacity = maxCapacity;
            this.displayName = displayName;
        }

        /**
         * Creates an Item input slot.
         */
        public static Slot itemInput(String id, int stackSize) {
            return new Slot("ITEM", id, true, stackSize, id);
        }

        /**
         * Creates an Item output slot.
         */
        public static Slot itemOutput(String id, int stackSize) {
            return new Slot("ITEM", id, false, stackSize, id);
        }

        /**
         * Creates a Fluid input tank.
         */
        public static Slot fluidInput(String id, int amount) {
            return new Slot("FLUID", id, true, amount, id);
        }

        /**
         * Creates a Fluid output tank.
         */
        public static Slot fluidOutput(String id, int amount) {
            return new Slot("FLUID", id, false, amount, id);
        }

        /**
         * Creates a custom type slot.
         */
        public static Slot custom(String type, String id, boolean isInput, long capacity) {
            return new Slot(type, id, isInput, capacity, id);
        }

        /**
         * Sets a custom display name.
         */
        public Slot withDisplayName(String name) {
            return new Slot(slotType, slotId, isInput, maxCapacity, name);
        }

        @Override
        public String getSlotType() {
            return slotType;
        }

        @Override
        public String getId() {
            return slotId;
        }

        @Override
        public boolean isInput() {
            return isInput;
        }

        @Override
        public long getMaxCapacity() {
            return maxCapacity;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Generic Energy component impl.
     */
    public static class GenericEnergy implements TEnergyComponent
    {
        private final String energyType;
        private final long amount;

        public GenericEnergy(String energyType, long amount) {
            this.energyType = energyType;
            this.amount = amount;
        }

        /**
         * Creates a Forge Energy requirement.
         */
        public static GenericEnergy fe(long amount) {
            return new GenericEnergy("FE", amount);
        }

        /**
         * Creates an Energy Unit requirement.
         */
        public static GenericEnergy eu(long amount) {
            return new GenericEnergy("EU", amount);
        }

        /**
         * Creates an Item Fuel requirement.
         */
        public static GenericEnergy fuel() {
            return new GenericEnergy("FUEL", 0);
        }

        /**
         * Creates a custom energy type requirement.
         */
        public static GenericEnergy custom(String type, long amount) {
            return new GenericEnergy(type, amount);
        }

        @Override
        public String getEnergyType() {
            return energyType;
        }

        @Override
        public long getAmountPerOperation() {
            return amount;
        }
    }

    /**
     * Generic Duration component impl.
     */
    public static class GenericDuration implements TDurationComponent
    {
        private final int duration;

        public GenericDuration(int duration) {
            this.duration = duration;
        }

        /**
         * Creates a duration in ticks.
         */
        public static GenericDuration ticks(int ticks) {
            return new GenericDuration(ticks);
        }

        /**
         * Creates a duration in seconds.
         */
        public static GenericDuration seconds(int seconds) {
            return new GenericDuration(seconds * 20);
        }

        /**
         * Creates a duration in minutes.
         */
        public static GenericDuration minutes(int minutes) {
            return new GenericDuration(minutes * 20 * 60);
        }

        @Override
        public int getDuration() {
            return duration;
        }
    }

    /**
     * Custom component impl.
     */
    public static class Custom implements TRecipeComponent
    {
        private final String id;
        private final String category;
        private final Map<String, Object> data;

        public Custom(String id, String category) {
            this.id = id;
            this.category = category;
            this.data = new HashMap<>();
        }

        /**
         * Creates a custom component with the given ID.
         */
        public static Custom of(String id, String category) {
            return new Custom(id, category);
        }

        /**
         * Adds a property to this component.
         */
        public Custom with(String key, Object value) {
            data.put(key, value);
            return this;
        }

        @Override
        public String getCategory() {
            return category;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Map<String, Object> getData() {
            return new HashMap<>(data);
        }
    }

    /**
     * Builder for creating UI layouts.
     */
    public static class LayoutBuilder
    {
        private final int width;
        private final int height;
        private final List<TUIElement> elements = new ArrayList<>();

        private LayoutBuilder(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /**
         * Creates a new layout builder.
         */
        public static LayoutBuilder create(int width, int height) {
            return new LayoutBuilder(width, height);
        }

        /**
         * Adds a UI element.
         */
        public LayoutBuilder element(TUIElement element) {
            elements.add(element);
            return this;
        }

        /**
         * Adds a UI element with type and position.
         */
        public LayoutBuilder element(String type, int x, int y, int width, int height) {
            elements.add(new SimpleUIElement(type, x, y, width, height, "HORIZONTAL"));
            return this;
        }

        /**
         * Adds a UI element with animation direction.
         */
        public LayoutBuilder element(String type, int x, int y, int width, int height, String animDir) {
            elements.add(new SimpleUIElement(type, x, y, width, height, animDir));
            return this;
        }

        /**
         * Builds the UI layout.
         */
        public TUILayout build() {
            return new TUILayout()
            {
                private final List<TUIElement> elementsCopy = new ArrayList<>(elements);

                @Override
                public int getWidth() {
                    return width;
                }

                @Override
                public int getHeight() {
                    return height;
                }

                @Override
                public List<TUIElement> getElements() {
                    return elementsCopy;
                }
            };
        }
    }

    /**
     * Simple UI element implementation.
     */
    public record SimpleUIElement(String type, int x, int y, int width, int height,
                                  String animDirection) implements TUIElement
    {
    }
}
