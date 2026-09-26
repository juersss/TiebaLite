package com.huanchengfly.tieba.post.api.adapters;

import androidx.annotation.NonNull;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.huanchengfly.tieba.post.api.internal.AvatarUrl;

import java.lang.reflect.Type;

public class PortraitAdapter implements JsonDeserializer<String> {
    @Override
    public String deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        return AvatarUrl.of(getNonNullString(json));
    }

    @NonNull
    private String getNonNullString(JsonElement jsonElement) {
        return jsonElement != null && !jsonElement.isJsonNull() ? jsonElement.getAsString() : "";
    }
}
