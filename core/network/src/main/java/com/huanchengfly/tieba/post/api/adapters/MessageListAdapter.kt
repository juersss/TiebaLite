package com.huanchengfly.tieba.post.api.adapters

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.huanchengfly.tieba.post.api.models.MessageListBean.MessageInfoBean
import java.lang.reflect.Type

class MessageListAdapter : JsonDeserializer<List<MessageInfoBean>> {
    @Throws(JsonParseException::class)
    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): List<MessageInfoBean> {
        // 白名单制:reply_list/at_list 服务端会下发对象形态(非数组),本适配器存在的
        // 意义就是容忍它——除数组外的任何形态一律空列表,不抛 JsonSyntaxException
        return if (json.isJsonArray) {
            context.deserialize(json, typeOfT)
        } else ArrayList()
    }
}