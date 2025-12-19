package recipe;

public interface TUIElement
{
    String getType();

    int getX();

    int getY();

    int getWidth();

    int getHeight();

    default String getAnimDirection() {
        return "HORIZONTAL";
    }
}
