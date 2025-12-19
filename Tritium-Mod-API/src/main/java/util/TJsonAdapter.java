package util;

import com.google.gson.JsonElement;

import java.util.Optional;

public interface TJsonAdapter
{
    JsonElement serialize(Object obj);

    default Optional<Object> deserialize(JsonElement json) { return Optional.empty(); }
}
