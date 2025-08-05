package com.logicmonitor.logs.azure;

import com.google.gson.*;

import java.lang.reflect.Type;

public class LogEventPropertiesDeserializer implements JsonDeserializer<LogEventProperties> {

    private static final Gson vanillaGson = new Gson();

    @Override
    public LogEventProperties deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context)
            throws JsonParseException {
        try {
            JsonObject propertiesObj;

            if (json.isJsonPrimitive() && json.getAsJsonPrimitive().isString()) {
                propertiesObj = JsonParser.parseString(json.getAsString()).getAsJsonObject();
            } else if (json.isJsonObject()) {
                propertiesObj = json.getAsJsonObject();
            } else {
                throw new JsonParseException("Unexpected format for 'properties'");
            }

            // Use the fallback Gson to avoid recursive deserialization
            return vanillaGson.fromJson(propertiesObj, LogEventProperties.class);

        } catch (Exception e) {
            throw new JsonParseException("Failed to deserialize 'properties'", e);
        }
    }
}
