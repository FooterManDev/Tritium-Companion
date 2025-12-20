package recipe;

import java.util.List;

/**
 * Represents the UI layout for a recipe type.
 * <p>
 * This describes the dimensions and visual elements of a recipe's GUI,
 * including texture size and positions of interactive elements like progress
 * arrows.
 * </p>
 *
 * @see TUIElement
 */
public interface TUILayout
{
    /**
     * Gets the width of the UI texture in pixels.
     * @return The width in pixels
     */
    int getWidth();

    /**
     * Gets the height of the UI texture in pixels.
     * @return The height in pixels
     */
    int getHeight();

    /**
     * Gets the list of UI elements that should be rendered.
     * These can represent interactive or animated components of the GUI
     * such as progress arrows, glyphs, etc.
     *
     * @return A list of UI elements
     * @see TUIElement
     */
    List<TUIElement> getElements();
}
