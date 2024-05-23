package com.logicmonitor.logs.azure;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Map;
import org.apache.commons.lang3.StringEscapeUtils;

public class JsonParsingUtils {
    protected static JsonElement parseJsonSafely(String jsonData) {
        Gson gson = new Gson();
        JsonElement element;
        try{
            element = JsonParser.parseString(jsonData);
            return processElement(element);
        } catch (JsonSyntaxException | IllegalStateException e) {
            return new JsonObject();
        }
    }

    private static JsonElement processElement(JsonElement jsonElement){
        if(jsonElement.isJsonObject()) {
         return processObject(jsonElement.getAsJsonObject());
        } else if(jsonElement.isJsonArray()){
         return processArray(jsonElement.getAsJsonArray());
        } else {
            return jsonElement;
        }
    }

    private static JsonObject processObject(JsonObject jsonObject){
        JsonObject result = new JsonObject();
        for(Map.Entry<String, JsonElement> entry : jsonObject.entrySet()) {
            try {
                JsonElement processedElement = processElement(entry.getValue());
                result.add(entry.getKey(), processedElement);
            } catch (Exception e){

            }
        }
        return result;
    }

    private static JsonArray processArray(JsonArray jsonArray){
        JsonArray result = new JsonArray();
        for(JsonElement element : jsonArray){
            try {
                JsonElement processedElement = processElement(element);
                result.add(processedElement);
            } catch (Exception e){

            }
        }
        return result;
    }

    protected static String removeQuotesAndUnescape(String uncleanJson) {
        String noQuotes = uncleanJson.replaceAll("^\"|\"$", "");
        return StringEscapeUtils.unescapeJava(noQuotes);
    }

}
