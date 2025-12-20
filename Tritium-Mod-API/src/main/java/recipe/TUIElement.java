package recipe;

/**
 * A single UI element within a recipe GUI.
 *
 * @see TUILayout
 */
public interface TUIElement
{
    /**
     * Gets the type identifier for this UI element.
     * @return The element type string
     */
    String type();

    /**
     * Gets the X coordinate of this element on the texture.
     * @return The X coordinate in pixels
     */
    int x();

    /**
     * Gets the Y coordinate of this element on the texture.
     * @return The Y coordinate in pixels
     */
    int y();

    /**
     * Gets the width of this element in pixels.
     *
     * @return The width in pixels
     */
    int width();

    /**
     * Gets the height of this element in pixels.
     *
     * @return The height in pixels
     */
    int height();

    /**
     * Gets the animation direction for this element.
     * @return The animation direction
     */
    default String animDirection() {
        return "HORIZONTAL";
    }
}
