package com.huanchengfly.tieba.post.ui.models

import androidx.compose.runtime.Immutable
import com.huanchengfly.tieba.post.App
import com.huanchengfly.tieba.post.core.network.model.protos.ThreadInfo
import com.huanchengfly.tieba.post.core.network.model.protos.personalized.ThreadPersonalized
import com.huanchengfly.tieba.post.arch.ImmutableHolder
import com.huanchengfly.tieba.post.utils.BlockManager.shouldBlock
import com.huanchengfly.tieba.post.core.data.appPreferences
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

@Immutable
data class ThreadItemData(
    val thread: ImmutableHolder<ThreadInfo>,
    val blocked: Boolean = thread.get { shouldBlock() },
    val personalized: ImmutableHolder<ThreadPersonalized>? = null,
    val hidden: Boolean = blocked && App.INSTANCE.appPreferences.hideBlockedContent.value,
)

fun List<ThreadItemData>.distinctById(): ImmutableList<ThreadItemData> {
    return distinctBy {
        it.thread.get {
            id
        }
    }.toImmutableList()
}