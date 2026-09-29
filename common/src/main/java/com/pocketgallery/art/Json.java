package com.pocketgallery.art;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Null-tolerant JSON accessors shared by the API parsers (missing or oddly typed fields read as null / 0). */
final class Json {
    private Json() {
    }

    /** Parses an object, or throws an {@link IllegalArgumentException} naming the site. */
    static JsonObject object(String json, String site) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) throw new IllegalArgumentException("Unexpected answer from " + site);
            return root.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new IllegalArgumentException(site + " returned invalid data", e);
        }
    }

    static JsonObject obj(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static JsonArray arr(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    static String str(JsonObject o, String key) {
        if (o == null) return null;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
        return e.getAsString();
    }

    static boolean bool(JsonObject o, String key) {
        if (o == null) return false;
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    static int integer(JsonObject o, String key) {
        if (o == null) return 0;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return 0;
        try {
            return (int) Math.round(Double.parseDouble(e.getAsString().trim()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** The first element of an array as an object, or null. */
    static JsonObject first(JsonArray a) {
        return a != null && !a.isEmpty() && a.get(0).isJsonObject() ? a.get(0).getAsJsonObject() : null;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
