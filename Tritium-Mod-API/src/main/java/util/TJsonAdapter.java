package util;

import com.google.gson.JsonElement;

import java.util.Optional;

/**
 * Used for custom JSON serialization and deserialization adapters.
 * This is generally for converting Minecraft CODECS.
 */
public interface TJsonAdapter
{
    /**
     * Serialize the given object into a JSON element.
     * @param obj The object to serialize
     * @return A JsonElement representing the serialized object
     */
    JsonElement serialize(Object obj);

    /**
     * Deserializes a JSON element into an object.
     * @param json The JSON element to deserialize
     * @return An Optional containing the deserialized object, or nothing on failure.
     */
    default Optional<Object> deserialize(JsonElement json) { return Optional.empty(); }
}
