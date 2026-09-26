package com.huanchengfly.tieba.post.api.adapters;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.huanchengfly.tieba.post.api.models.SearchUserBean;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UserFuzzyMatchAdapter implements JsonDeserializer<List<SearchUserBean.UserBean>> {
    @Override
    public List<SearchUserBean.UserBean> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        List<SearchUserBean.UserBean> userBeans = new ArrayList<>();
        if (json.isJsonArray()) {
            JsonArray jsonArray = json.getAsJsonArray();
            for (JsonElement element : jsonArray) {
                if (element.isJsonObject()) {
                    userBeans.add(getUserBean(element.getAsJsonObject()));
                }
            }
        } else if (json.isJsonObject()) {
            JsonObject jsonObject = json.getAsJsonObject();
            for (Map.Entry<String, JsonElement> elementEntry : jsonObject.entrySet()) {
                JsonElement jsonElement = elementEntry.getValue();
                if (jsonElement.isJsonObject()) {
                    userBeans.add(getUserBean(jsonElement.getAsJsonObject()));
                }
            }
        }
        return userBeans;
    }

    @Nullable
    private String getNonNullString(@Nullable JsonElement jsonElement) {
        // 缺键时 get() 返回 null、值类型异常(对象/数组)时 getAsString 抛异常:
        // 模糊匹配是服务端任意条目集合,按缺省值降级而不是让整次搜索失败
        return jsonElement != null && jsonElement.isJsonPrimitive() ? jsonElement.getAsString() : null;
    }

    private int getIntOrDefault(JsonObject jsonObject, String key, int defaultValue) {
        JsonElement element = jsonObject.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsInt() : defaultValue;
    }

    private SearchUserBean.UserBean getUserBean(JsonObject jsonObject) {
        return new SearchUserBean.UserBean(
                getNonNullString(jsonObject.get("id")),
                getNonNullString(jsonObject.get("intro")),
                getNonNullString(jsonObject.get("user_nickname")),
                getNonNullString(jsonObject.get("show_nickname")),
                getNonNullString(jsonObject.get("name")),
                getNonNullString(jsonObject.get("portrait")),
                getNonNullString(jsonObject.get("fans_num")),
                getIntOrDefault(jsonObject, "has_concerned", 0)
        );
    }
}
