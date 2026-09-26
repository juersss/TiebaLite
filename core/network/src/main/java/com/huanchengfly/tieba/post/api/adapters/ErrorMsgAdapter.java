package com.huanchengfly.tieba.post.api.adapters;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

public class ErrorMsgAdapter implements JsonDeserializer<String> {
    @Override
    public String deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        if (json.isJsonPrimitive()) {
            return json.getAsString();
        } else if (json.isJsonObject()) {
            // 对象形态的 error_msg:键可能缺失、值可能是 JsonNull——两者都会抛异常
            // 打断整次反序列化(十余个端点的 CommonResponse 走这里),按 null 降级
            JsonElement element = json.getAsJsonObject().get("errmsg");
            return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
        }
        return null;
    }
}
