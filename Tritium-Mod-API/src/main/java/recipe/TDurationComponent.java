package recipe;

import java.util.HashMap;
import java.util.Map;

/**
 * Component representing recipe duration.
 */
public interface TDurationComponent extends TRecipeComponent
{

    int duration();

    @Override
    default String getCategory() {
        return "DURATION";
    }

    @Override
    default String getId() {
        return "duration";
    }

    @Override
    default Map<String, Object> getData() {
        Map<String, Object> data = new HashMap<>();
        data.put("duration", duration());
        return data;
    }
}
