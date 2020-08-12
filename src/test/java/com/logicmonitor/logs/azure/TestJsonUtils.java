package com.logicmonitor.logs.azure;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class TestJsonUtils {

    private static final Gson GSON = new GsonBuilder().create();

    public static JsonArray getArray(String resourceName) {
        try (Reader reader = new InputStreamReader(
                TestJsonUtils.class.getResourceAsStream(resourceName))) {
            return GSON.fromJson(reader, JsonArray.class);
        } catch (IOException e) {
            return null;
        }
    }

    public static JsonObject getFirstObject(String resourceName) {
        return StreamSupport.stream(getArray(resourceName).spliterator(), true)
            .filter(JsonElement::isJsonObject)
            .findFirst()
            .map(JsonElement::getAsJsonObject)
            .orElse(null);
    }

    public static JsonObject getFirstLogEvent(String resourceName) {
        JsonObject object = getFirstObject(resourceName);
        return Optional.ofNullable(object)
            .map(o -> o.get(LogEventAdapter.AZURE_RECORDS_PROPERTY))
            .map(JsonElement::getAsJsonArray)
            .map(array -> array.get(0))
            .map(JsonElement::getAsJsonObject)
            .orElse(object);
    }

    public static String toString(JsonElement element) {
        return GSON.toJson(element);
    }

    public static JsonArray mergeArrays(String... resourceNames) {
        return Stream.of(resourceNames)
            .map(TestJsonUtils::getArray)
            .reduce(new JsonArray(), (result, element) -> {
                result.addAll(element);
                return result;
            });
    }

}
