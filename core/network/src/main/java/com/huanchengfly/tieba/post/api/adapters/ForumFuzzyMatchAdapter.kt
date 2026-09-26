package com.huanchengfly.tieba.post.api.adapters

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.huanchengfly.tieba.post.api.models.SearchForumBean
import java.lang.reflect.Type

class ForumFuzzyMatchAdapter : JsonDeserializer<List<SearchForumBean.ForumInfoBean>> {
    @Throws(JsonParseException::class)
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext,
    ): List<SearchForumBean.ForumInfoBean> {
        val forumInfoBeans: MutableList<SearchForumBean.ForumInfoBean> = ArrayList()
        if (json.isJsonArray) {
            val jsonArray = json.asJsonArray
            for (element in jsonArray) {
                if (element.isJsonObject) {
                    forumInfoBeans.add(getForumInfoBean(element.asJsonObject))
                }
            }
        } else if (json.isJsonObject) {
            val jsonObject = json.asJsonObject
            for ((_, jsonElement) in jsonObject.entrySet()) {
                if (jsonElement.isJsonObject) {
                    forumInfoBeans.add(getForumInfoBean(jsonElement.asJsonObject))
                }
            }
        }
        return forumInfoBeans
    }

    // 缺键时 jsonObject[k] 为 null、值类型异常(对象/数组)时 asString 抛异常:
    // 模糊匹配是服务端任意条目集合,按缺省值降级而不是让整次搜索失败
    private fun getNonNullString(jsonElement: JsonElement?): String? {
        return if (jsonElement != null && jsonElement.isJsonPrimitive) jsonElement.asString else null
    }

    private fun getLongOrDefault(jsonObject: JsonObject, key: String, default: Long): Long =
        jsonObject[key]?.takeIf { it.isJsonPrimitive }?.asLong ?: default

    private fun getIntOrDefault(jsonObject: JsonObject, key: String, default: Int): Int =
        jsonObject[key]?.takeIf { it.isJsonPrimitive }?.asInt ?: default

    private fun getForumInfoBean(jsonObject: JsonObject): SearchForumBean.ForumInfoBean {
        return SearchForumBean.ForumInfoBean(
            forumId = getLongOrDefault(jsonObject, "forum_id", 0L),
            forumName = getNonNullString(jsonObject["forum_name"]),
            forumNameShow = getNonNullString(jsonObject["forum_name_show"]),
            avatar = getNonNullString(jsonObject["avatar"]),
            postNum = getNonNullString(jsonObject["post_num"]) ?: "0",
            concernNum = getNonNullString(jsonObject["concern_num"]) ?: "0",
            hasConcerned = getIntOrDefault(jsonObject, "has_concerned", 0)
        )
    }
}
