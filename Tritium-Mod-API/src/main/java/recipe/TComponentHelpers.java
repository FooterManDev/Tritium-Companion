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
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public Slot(String slotType, String slotId, boolean isInput, long maxCapacity, String displayName) {
            this(slotType, slotId, isInput, maxCapacity, displayName, 0, 0, 18, 18);
        }

        public Slot(
                String slotType,
                String slotId,
                boolean isInput,
                long maxCapacity,
                String displayName,
                int x,
                int y,
                int width,
                int height
        ) {
            this.slotType = slotType;
            this.slotId = slotId;
            this.isInput = isInput;
            this.maxCapacity = maxCapacity;
            this.displayName = displayName;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
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
            return new Slot(slotType, slotId, isInput, maxCapacity, name, x, y, width, height);
        }

        /**
         * Sets render bounds on the recipe background.
         */
        public Slot at(int x, int y) {
            return new Slot(slotType, slotId, isInput, maxCapacity, displayName, x, y, width, height);
        }

        /**
         * Sets render bounds on the recipe background.
         */
        public Slot bounds(int x, int y, int width, int height) {
            return new Slot(slotType, slotId, isInput, maxCapacity, displayName, x, y, width, height);
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

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }
    }

    /**
     * Generic Energy component impl.
     */
    public static class GenericEnergy implements TEnergyComponent
    {
        private final String energyType;
        private final long amount;
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public GenericEnergy(String energyType, long amount) {
            this(energyType, amount, 0, 0, 18, 18);
        }

        public GenericEnergy(String energyType, long amount, int x, int y, int width, int height) {
            this.energyType = energyType;
            this.amount = amount;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
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

        public GenericEnergy bounds(int x, int y, int width, int height) {
            return new GenericEnergy(energyType, amount, x, y, width, height);
        }

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }
    }

        /**
         * Generic Duration component impl.
         */
        public record GenericDuration(int duration, int x, int y, int width, int height) implements TDurationComponent
        {
            public GenericDuration(int duration) {
                this(duration, 0, 0, 18, 18);
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

            public GenericDuration bounds(int x, int y, int width, int height) {
                return new GenericDuration(duration, x, y, width, height);
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
        private final int x;
        private final int y;
        private final int width;
        private final int height;

        public Custom(String id, String category) {
            this(id, category, 0, 0, 18, 18);
        }

        public Custom(String id, String category, int x, int y, int width, int height) {
            this.id = id;
            this.category = category;
            this.data = new HashMap<>();
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
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

        public Custom bounds(int x, int y, int width, int height) {
            Custom custom = new Custom(id, category, x, y, width, height);
            custom.data.putAll(data);
            return custom;
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

        @Override
        public int x() {
            return x;
        }

        @Override
        public int y() {
            return y;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
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
