package com.huanchengfly.tieba.post.utils

import com.huanchengfly.tieba.post.api.models.ForumGuideBean.LikeForum
import com.huanchengfly.tieba.post.models.database.FollowedForum

/**
 * `ForumGuideBean.LikeForum`（core:network 的服务端模型）↔ `FollowedForum`（core:database 的
 * Room 实体）互转。
 *
 * 为什么不把 `@Entity` 直接打在 `LikeForum` 上：那会让 core:network 依赖 Room/注解，
 * 违反"core 之间只依赖 core:common"与"网络层不认识持久层"的分层（`ArchitectureRulesTest` 会红）。
 * 转换放在 app 组合层。
 */
internal fun LikeForum.toFollowedForum(uid: String) = FollowedForum(
    uid = uid,
    forumId = forumId,
    forumName = forumName,
    avatar = avatar,
    levelId = levelId,
    levelName = levelName,
    isSign = isSign,
    isForbidden = isForbidden,
    isOfficialForum = isOfficialForum,
    hotNum = hotNum,
    memberCount = memberCount,
    threadNum = threadNum,
    dayThreadNum = dayThreadNum,
    topSortValue = topSortValue,
)

internal fun FollowedForum.toLikeForum() = LikeForum(
    forumName = forumName,
    hotNum = hotNum,
    memberCount = memberCount,
    threadNum = threadNum,
    levelName = levelName,
    forumId = forumId,
    dayThreadNum = dayThreadNum,
    avatar = avatar,
    isSign = isSign,
    topSortValue = topSortValue,
    levelId = levelId,
    isForbidden = isForbidden,
    isOfficialForum = isOfficialForum,
)
