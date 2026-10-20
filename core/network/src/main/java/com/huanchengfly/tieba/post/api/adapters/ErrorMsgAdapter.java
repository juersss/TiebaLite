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
            // 打断整次反序列化(十余个端点的 CommonResponse 走这里),按空串降级
            //
            // 2026-10-20 修复:此前这里 return null。`CommonResponse.errorMsg` 是 Kotlin 非空
            // `String`,Gson 反射会照样把 null 塞进去;随后 TiebaApiException 对 null 调
            // `isNotEmpty()` → NPE。该 NPE 在应用拦截器里抛出,而 OkHttp 对非 IOException
            // 是「onFailure(IOException("canceled due to ...")) 之后原样 rethrow」,重抛落在
            // Dispatcher 线程 = 进程 FATAL(整条错误码/错误文案也一并丢失)。
            JsonElement element = json.getAsJsonObject().get("errmsg");
            return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
        }
        return "";
    }
}
