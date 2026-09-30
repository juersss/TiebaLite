package com.huanchengfly.tieba.post.api.interfaces.impls

import com.huanchengfly.tieba.post.api.session.SessionProviders
import android.os.Build
import android.text.TextUtils
import android.util.Log
import com.huanchengfly.tieba.post.api.AgreeParams
import com.huanchengfly.tieba.post.api.OpResponseLog
import com.huanchengfly.tieba.post.api.V22ImageGateSentinel
import com.huanchengfly.tieba.post.api.ClientVersion
import com.huanchengfly.tieba.post.api.ForumSortType
import com.huanchengfly.tieba.post.api.Param
import com.huanchengfly.tieba.post.api.SearchThreadFilter
import com.huanchengfly.tieba.post.api.SearchThreadOrder
import com.huanchengfly.tieba.post.api.booleanToString
import com.huanchengfly.tieba.post.api.buildAdParam
import com.huanchengfly.tieba.post.api.buildAppPosInfo
import com.huanchengfly.tieba.post.api.buildCommonRequest
import com.huanchengfly.tieba.post.api.buildProtobufRequestBody
import com.huanchengfly.tieba.post.api.getScreenHeight
import com.huanchengfly.tieba.post.api.getScreenWidth
import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import com.huanchengfly.tieba.post.api.models.AgreeBean
import com.huanchengfly.tieba.post.api.models.CheckReportBean
import com.huanchengfly.tieba.post.api.models.CollectDataBean
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.ForumGuideBean
import com.huanchengfly.tieba.post.api.models.FollowBean
import com.huanchengfly.tieba.post.api.models.FollowListBean
import com.huanchengfly.tieba.post.api.models.ForumPageBean
import com.huanchengfly.tieba.post.api.models.ForumRecommend
import com.huanchengfly.tieba.post.api.models.GetForumListBean
import com.huanchengfly.tieba.post.api.models.GetUserBlackInfoBean
import com.huanchengfly.tieba.post.api.models.InitNickNameBean
import com.huanchengfly.tieba.post.api.models.LikeForumResultBean
import com.huanchengfly.tieba.post.api.models.LoginBean
import com.huanchengfly.tieba.post.api.models.MSignBean
import com.huanchengfly.tieba.post.api.models.MessageListBean
import com.huanchengfly.tieba.post.api.models.MsgBean
import com.huanchengfly.tieba.post.api.models.NewCollectDataBean
import com.huanchengfly.tieba.post.api.models.PermissionListBean
import com.huanchengfly.tieba.post.api.models.PersonalizedBean
import com.huanchengfly.tieba.post.api.models.PicPageBean
import com.huanchengfly.tieba.post.api.models.Profile
import com.huanchengfly.tieba.post.api.models.ProfileBean
import com.huanchengfly.tieba.post.api.models.SearchForumBean
import com.huanchengfly.tieba.post.api.models.SearchPostBean
import com.huanchengfly.tieba.post.api.models.SearchThreadBean
import com.huanchengfly.tieba.post.api.models.SearchUserBean
import com.huanchengfly.tieba.post.api.models.SignResultBean
import com.huanchengfly.tieba.post.api.models.SubFloorListBean
import com.huanchengfly.tieba.post.api.models.Sync
import com.huanchengfly.tieba.post.api.models.ThreadContentBean
import com.huanchengfly.tieba.post.api.models.ThreadStoreBean
import com.huanchengfly.tieba.post.api.models.TopicDetailBean
import com.huanchengfly.tieba.post.api.models.UserLikeForumBean
import com.huanchengfly.tieba.post.api.models.UserPostBean
import com.huanchengfly.tieba.post.api.models.WebReplyResultBean
import com.huanchengfly.tieba.post.api.models.WebUploadPicBean
import com.huanchengfly.tieba.post.core.network.model.protos.addPost.AddPostRequest
import com.huanchengfly.tieba.post.core.network.model.protos.addPost.AddPostRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.addPost.AddPostResponse
import com.huanchengfly.tieba.post.core.network.model.protos.addPollPost.AddPollPostReponse
import com.huanchengfly.tieba.post.core.network.model.protos.addPollPost.AddPollPostRequest
import com.huanchengfly.tieba.post.core.network.model.protos.addPollPost.AddPollPostRequestDate
import com.huanchengfly.tieba.post.core.network.model.protos.addThread.AddThreadRequest
import com.huanchengfly.tieba.post.core.network.model.protos.addThread.AddThreadRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.addThread.AddThreadResponse
import com.huanchengfly.tieba.post.core.network.model.protos.forumGuide.ForumGuideRequest
import com.huanchengfly.tieba.post.core.network.model.protos.forumGuide.ForumGuideRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.forumGuide.ForumGuideResponse
import com.huanchengfly.tieba.post.core.network.model.protos.forumRecommend.ForumRecommendRequest
import com.huanchengfly.tieba.post.core.network.model.protos.forumRecommend.ForumRecommendRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.forumRecommend.ForumRecommendResponse
import com.huanchengfly.tieba.post.core.network.model.protos.forumRuleDetail.ForumRuleDetailRequest
import com.huanchengfly.tieba.post.core.network.model.protos.forumRuleDetail.ForumRuleDetailRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.forumRuleDetail.ForumRuleDetailResponse
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.FrsPageRequest
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.FrsPageRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.GeneralTabList.GeneralTabListRequest
import com.huanchengfly.tieba.post.core.network.model.protos.GeneralTabList.GeneralTabListRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.GeneralTabList.GeneralTabListResponse
import com.huanchengfly.tieba.post.core.network.model.protos.frsPage.FrsPageResponse
import com.huanchengfly.tieba.post.core.network.model.protos.getBawuInfo.GetBawuInfoRequest
import com.huanchengfly.tieba.post.core.network.model.protos.getBawuInfo.GetBawuInfoRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.getBawuInfo.GetBawuInfoResponse
import com.huanchengfly.tieba.post.core.network.model.protos.getForumDetail.GetForumDetailRequest
import com.huanchengfly.tieba.post.core.network.model.protos.getForumDetail.GetForumDetailRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.getForumDetail.GetForumDetailResponse
import com.huanchengfly.tieba.post.core.network.model.protos.getHistoryForum.GetHistoryForumRequest
import com.huanchengfly.tieba.post.core.network.model.protos.getHistoryForum.GetHistoryForumRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.getHistoryForum.GetHistoryForumResponse
import com.huanchengfly.tieba.post.core.network.model.protos.getLevelInfo.GetLevelInfoRequest
import com.huanchengfly.tieba.post.core.network.model.protos.getLevelInfo.GetLevelInfoRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.getLevelInfo.GetLevelInfoResponse
import com.huanchengfly.tieba.post.core.network.model.protos.getMemberInfo.GetMemberInfoRequest
import com.huanchengfly.tieba.post.core.network.model.protos.getMemberInfo.GetMemberInfoRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.getMemberInfo.GetMemberInfoResponse
import com.huanchengfly.tieba.post.core.network.model.protos.getUserInfo.GetUserInfoRequest
import com.huanchengfly.tieba.post.core.network.model.protos.getUserInfo.GetUserInfoRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.getUserInfo.GetUserInfoResponse
import com.huanchengfly.tieba.post.core.network.model.protos.hotThreadList.HotThreadListRequest
import com.huanchengfly.tieba.post.core.network.model.protos.hotThreadList.HotThreadListRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.hotThreadList.HotThreadListResponse
import com.huanchengfly.tieba.post.core.network.model.protos.pbFloor.PbFloorRequest
import com.huanchengfly.tieba.post.core.network.model.protos.pbFloor.PbFloorRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.pbFloor.PbFloorResponse
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageRequest
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.pbPage.PbPageResponse
import com.huanchengfly.tieba.post.core.network.model.protos.personalized.PersonalizedRequest
import com.huanchengfly.tieba.post.core.network.model.protos.personalized.PersonalizedRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.personalized.PersonalizedResponse
import com.huanchengfly.tieba.post.core.network.model.protos.profile.ProfileRequest
import com.huanchengfly.tieba.post.core.network.model.protos.profile.ProfileRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.profile.ProfileResponse
import com.huanchengfly.tieba.post.core.network.model.protos.searchSug.SearchSugRequest
import com.huanchengfly.tieba.post.core.network.model.protos.searchSug.SearchSugRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.searchSug.SearchSugResponse
import com.huanchengfly.tieba.post.core.network.model.protos.threadList.AdParam
import com.huanchengfly.tieba.post.core.network.model.protos.threadList.ThreadListRequest
import com.huanchengfly.tieba.post.core.network.model.protos.threadList.ThreadListRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.threadList.ThreadListResponse
import com.huanchengfly.tieba.post.core.network.model.protos.topicList.TopicListRequest
import com.huanchengfly.tieba.post.core.network.model.protos.topicList.TopicListRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.topicList.TopicListResponse
import com.huanchengfly.tieba.post.core.network.model.protos.userLike.UserLikeRequest
import com.huanchengfly.tieba.post.core.network.model.protos.userLike.UserLikeRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.userLike.UserLikeResponse
import com.huanchengfly.tieba.post.core.network.model.protos.userPost.UserPostRequest
import com.huanchengfly.tieba.post.core.network.model.protos.userPost.UserPostRequestData
import com.huanchengfly.tieba.post.core.network.model.protos.userPost.UserPostResponse
import com.huanchengfly.tieba.post.api.models.web.ForumBean
import com.huanchengfly.tieba.post.api.models.web.ForumHome
import com.huanchengfly.tieba.post.api.models.web.HotMessageListBean
import com.huanchengfly.tieba.post.api.retrofit.ApiResult
import com.huanchengfly.tieba.post.api.retrofit.RetrofitTiebaApi
import com.huanchengfly.tieba.post.api.retrofit.body.MyMultipartBody
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaException
import com.huanchengfly.tieba.post.api.urlEncode
import com.huanchengfly.tieba.post.api.models.DislikeBean
import com.huanchengfly.tieba.post.api.models.MyInfoBean
import com.huanchengfly.tieba.post.api.models.PhotoInfoBean
import com.huanchengfly.tieba.post.core.common.toJson
import com.huanchengfly.tieba.post.api.params.CuidUtils
import com.huanchengfly.tieba.post.api.internal.ApiEncoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import android.os.SystemClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Call
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.URLEncoder

private const val TAG = "MixedTiebaApiImpl"

/**
 * 全量同步关注吧列表时的最大翻页页数上限
 *
 * 兜底防止服务端持续返回 has_more = 1 造成无限翻页（死循环 + 流量/WAF 风险）
 */
private const val MAX_FORUM_GUIDE_PAGES = 60

/** 贴吧服务端"tbs 无效/过期"数字错误码(opAgree 路径,经数字→异常转换到达) */
private const val TIEBA_TBS_INVALID_CODE = 110001

object MixedTiebaApiImpl : ITiebaApi {

    override fun personalized(loadType: Int, page: Int): Call<PersonalizedBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.personalized(loadType, page)

    override fun personalizedAsync(
        loadType: Int,
        page: Int
    ): Deferred<ApiResult<PersonalizedBean>> =
        RetrofitTiebaApi.MINI_TIEBA_API.personalizedAsync(loadType, page)

    override fun personalizedFlow(loadType: Int, page: Int): Flow<PersonalizedBean> {
        return RetrofitTiebaApi.OFFICIAL_TIEBA_API.personalizedFlow(loadType, page)
    }

    override fun personalizedProtoFlow(loadType: Int, page: Int): Flow<PersonalizedResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.personalizedFlow(
            buildProtobufRequestBody(
                data = PersonalizedRequest(
                    PersonalizedRequestData(
                        app_pos = buildAppPosInfo(),
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        load_type = loadType,
                        pn = page,
                        need_tags = 0,
                        page_thread_count = 11,
                        pre_ad_thread_count = 0,
                        sug_count = 0,
                        tag_code = 0,
                        q_type = 1,
                        need_forumlist = 0,
                        new_net_type = 1,
                        new_install = 0,
                        request_times = 0,
                        invoke_source = "",
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        scr_h = getScreenHeight(),
                        scr_w = getScreenWidth()
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12
            )
        )
    }

    override fun myProfileAsync(): Deferred<ApiResult<com.huanchengfly.tieba.post.api.models.web.Profile>> =
        RetrofitTiebaApi.WEB_TIEBA_API.myProfileAsync("json", "", "")

    override fun opAgree(
        threadId: String,
        postId: String,
        opType: Int
    ): Call<AgreeBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.agree(postId, threadId, op_type = opType)

    override fun disagree(
        threadId: String,
        postId: String,
        opType: Int
    ): Call<AgreeBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.disagree(postId, threadId, op_type = opType)

    /**
     * 给 opAgree 响应挂调试日志（OpResponseLog）：记录服务端返回的 error_code
     * 与操作后计数 score。objId 与 UI 诊断对齐——OBJ_THREAD 用 threadId，
     * 其余用 postId。日志失败绝不影响主流程，runCatching 兜住一切。
     *
     * 成功走 onEach；被 FailureResponseInterceptor 转成异常的失败走 catch
     * （记录后原样重抛，不改变下游语义），否则"取消被拒"这类响应在诊断里隐形。
     */
    private fun Flow<AgreeBean>.logOpResponse(
        threadId: String,
        postId: String,
        objType: Int,
        agreeType: Int,
        opType: Int,
    ): Flow<AgreeBean> =
        onEach { bean ->
            runCatching {
                val objId = if (objType == AgreeParams.OBJ_THREAD) threadId else postId
                OpResponseLog.record(objType, objId.toLong(), agreeType, opType, bean)
            }
        }.catch { e ->
            runCatching {
                if (e is TiebaException) {
                    val objId = if (objType == AgreeParams.OBJ_THREAD) threadId else postId
                    OpResponseLog.recordFailure(
                        objType, objId.toLong(), agreeType, opType,
                        errorCode = e.code.toString(),
                        errorMsg = e.message,
                    )
                }
            }
            throw e
        }

    /**
     * opAgree 基础请求:显式 tbs(自愈重试传入新值)缺省时退回登录缓存值,
     * 与原 MiniTiebaApi 默认参数行为一致。
     */
    private fun baseOpAgreeFlow(
        threadId: String,
        postId: String,
        opType: Int,
        objType: Int,
        agreeType: Int,
        tbs: String? = null,
        forumId: String? = null,
    ): Flow<AgreeBean> {
        // "0"=无楼层约定值(主帖赞踩官方不发 post_id)→null 让 Retrofit 跳过字段;
        // f facade ITiebaApi 的 postId 保持 String,调用方零改动
        val wirePostId = postId.takeIf { it != "0" }
        // 同理拦截 forum_id="0":0 不是合法吧 ID(真实 forumId 恒 >0),多为页面
        // 上下文缺失时的约定兜底值(如话题详情置顶兜底帖)——按"不携带"处理,
        // 与官方"缺失就不发"的字段语义一致,不把 0 发给服务端
        val wireForumId = forumId?.takeIf { it != "0" }
        // debug 线上参数日志(isDebug 门控,release 无此日志):验证 post_id 装载
        if (SessionProviders.clientConfig.isDebug) {
            Log.i(
                "OpAgreeWire",
                "opAgree req: threadId=$threadId postId=${wirePostId ?: "<omitted>"} " +
                    "forumId=${wireForumId ?: "<omitted>"} objType=$objType opType=$opType agreeType=$agreeType",
            )
        }
        return RetrofitTiebaApi.MINI_TIEBA_API.opAgreeFlow(
            threadId,
            wirePostId,
            opType = opType,
            objType = objType,
            agreeType = agreeType,
            tbs = tbs ?: SessionProviders.credential.getTbs(),
            forumId = wireForumId,
        ).logOpResponse(threadId, postId, objType, agreeType, opType)
    }

    /**
     * tbs 失效自愈(§七.6,09-06):opAgree 的 tbs 是登录时缓存值,账号长期不重登
     * 可能失效——服务端回数字 error_code=110001,被 FailureResponseInterceptor 转成
     * TiebaApiException。仅此码走自愈:仿 OKSigner 用 fetchAccountFlow 刷新一次
     * (单飞互斥已内建)并重试;刷新成功顺带同步内存缓存的单字段,避免此后每次操作
     * 都重复登录往返。其他错误原样重抛;重试仍失败按既有语义落回普通失败路径
     * (revertPending 回滚),不会递归。成功路径零额外往返。
     */
    private fun Flow<AgreeBean>.healInvalidTbs(
        retry: suspend (String?) -> Flow<AgreeBean>,
    ): Flow<AgreeBean> =
        catch { e ->
            if (e is TiebaApiException && e.code == TIEBA_TBS_INVALID_CODE) {
                val freshTbs = runCatching {
                    SessionProviders.credential.fetchAccountTbs()
                }.getOrNull()
                freshTbs?.let { SessionProviders.credential.setTbs(it) }
                emitAll(retry(freshTbs ?: SessionProviders.credential.getTbs()))
            } else {
                throw e
            }
        }

    override fun opAgreeFlow(
        threadId: String,
        postId: String,
        opType: Int,
        objType: Int,
        agreeType: Int,
        forumId: String?,
    ): Flow<AgreeBean> =
        baseOpAgreeFlow(threadId, postId, opType, objType, agreeType, forumId = forumId)
            .healInvalidTbs { tbs ->
                baseOpAgreeFlow(threadId, postId, opType, objType, agreeType, tbs, forumId)
            }

    override fun disagreeFlow(
        threadId: String,
        postId: String,
        opType: Int
    ): Flow<AgreeBean> {
        // "0"=无楼层约定值→不发送(与 baseOpAgreeFlow 同约定)
        val wirePostId = postId.takeIf { it != "0" }
        if (SessionProviders.clientConfig.isDebug) {
            Log.i(
                "OpAgreeWire",
                "disagree req: threadId=$threadId postId=${wirePostId ?: "<omitted>"} opType=$opType",
            )
        }
        return RetrofitTiebaApi.MINI_TIEBA_API.disagreeFlow(
            wirePostId, threadId, op_type = opType
        )
    }

    override fun opDisagreeFlow(
        threadId: String,
        postId: String,
        objType: Int,
        opType: Int,
        forumId: String?,
    ): Flow<AgreeBean> =
        baseOpAgreeFlow(
            threadId, postId, opType, objType,
            AgreeParams.TYPE_DISAGREE, forumId = forumId
        )
            .healInvalidTbs { tbs ->
                baseOpAgreeFlow(
                    threadId, postId, opType, objType,
                    AgreeParams.TYPE_DISAGREE, tbs, forumId
                )
            }

    override fun forumRecommend(): Call<ForumRecommend> =
        RetrofitTiebaApi.MINI_TIEBA_API.forumRecommend()

    override fun forumRecommendAsync(): Deferred<ApiResult<ForumRecommend>> =
        RetrofitTiebaApi.MINI_TIEBA_API.forumRecommendAsync()

    override fun forumRecommendFlow(): Flow<ForumRecommend> =
        RetrofitTiebaApi.MINI_TIEBA_API.forumRecommendFlow()

    override fun forumPage(
        forumName: String, page: Int, sortType: ForumSortType, goodClassifyId: String?
    ): Call<ForumPageBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.forumPage(forumName, page, sortType.value, goodClassifyId)

    override fun forumPageAsync(
        forumName: String,
        page: Int,
        sortType: ForumSortType,
        goodClassifyId: String?
    ): Deferred<ApiResult<ForumPageBean>> =
        RetrofitTiebaApi.MINI_TIEBA_API.forumPageAsync(
            forumName,
            page,
            sortType.value,
            goodClassifyId
        )

    override fun floor(
        threadId: String, page: Int, postId: String?, subPostId: String?
    ): Call<SubFloorListBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.floor(threadId, page, postId, subPostId)

    override fun forumHomeAsync(sortType: Int, page: Int): Deferred<ApiResult<ForumHome>> {
        return RetrofitTiebaApi.WEB_TIEBA_API.getForumHomeAsync(
            sortType,
            page,
            20,
            "",
            ""
        )
    }

    override fun userLikeForum(
        uid: String, page: Int
    ): Call<UserLikeForumBean> {
        val myUid = SessionProviders.credential.getUid()
        return RetrofitTiebaApi.MINI_TIEBA_API.userLikeForum(
            page = page,
            uid = myUid,
            friendUid = if (!TextUtils.equals(uid, myUid)) uid else null,
            is_guest = if (!TextUtils.equals(uid, myUid)) "1" else null

        )
    }

    override fun userPost(
        uid: String, page: Int, isThread: Boolean
    ): Call<UserPostBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.userPost(uid, page, if (isThread) 1 else 0)

    override fun picPage(
        forumId: String,
        forumName: String,
        threadId: String,
        seeLz: Boolean,
        picId: String,
        picIndex: String,
        objType: String,
        prev: Boolean
    ): Call<PicPageBean> = RetrofitTiebaApi.MINI_TIEBA_API.picPage(
        forumId,
        forumName,
        threadId,
        picId,
        picIndex,
        objType,
        prev = if (prev) 10 else 0,
        next = if (prev) 0 else 10,
        not_see_lz = if (seeLz) 0 else 1
    )

    override fun picPageFlow(
        forumId: String,
        forumName: String,
        threadId: String,
        seeLz: Boolean,
        picId: String,
        picIndex: String,
        objType: String,
        prev: Boolean
    ): Flow<PicPageBean> = RetrofitTiebaApi.MINI_TIEBA_API.picPageFlow(
        forumId,
        forumName,
        threadId,
        picId,
        picIndex,
        objType,
        prev = if (prev) 10 else 0,
        next = if (prev) 0 else 10,
        not_see_lz = if (seeLz) 0 else 1
    )

    override fun profile(uid: String): Call<ProfileBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.profile(uid)

    override fun profileFlow(uid: String): Flow<Profile> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.profileFlow(uid)

    override fun unlikeForum(
        forumId: String,
        forumName: String,
        tbs: String
    ): Call<CommonResponse> = RetrofitTiebaApi.MINI_TIEBA_API.unlikeForum(forumId, forumName, tbs)

    override fun unlikeForumFlow(
        forumId: String,
        forumName: String,
        tbs: String
    ): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.unfavolike(forumId, forumName, tbs)

    override fun likeForum(
        forumId: String, forumName: String, tbs: String
    ): Call<LikeForumResultBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.likeForum(forumId, forumName, tbs)

    override fun likeForumFlow(
        forumId: String,
        forumName: String,
        tbs: String
    ): Flow<LikeForumResultBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.likeForumFlow(forumId, forumName, tbs)

    override fun signAsync(forumName: String, tbs: String): Deferred<ApiResult<SignResultBean>> =
        RetrofitTiebaApi.MINI_TIEBA_API.signAsync(forumName, tbs)

    override fun signFlow(forumId: String, forumName: String, tbs: String): Flow<SignResultBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.signFlow(forumId, forumName, tbs)

    override fun delThread(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String
    ): Call<CommonResponse> =
        RetrofitTiebaApi.MINI_TIEBA_API.delThread(forumId, forumName, threadId, tbs)

    override fun delThreadFlow(
        forumId: Long,
        forumName: String,
        threadId: Long,
        tbs: String?,
        delMyThread: Boolean,
        isHide: Boolean,
    ): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API
            .delThreadFlow(
                forumId,
                forumName,
                threadId,
                tbs,
                deleteMyThread = if (delMyThread) 1 else 0,
                isFrsMask = if (isHide) 1 else 0
            )

    override fun delPost(
        forumId: String,
        forumName: String,
        threadId: String,
        postId: String,
        tbs: String,
        isFloor: Boolean,
        delMyPost: Boolean
    ): Call<CommonResponse> =
        RetrofitTiebaApi.MINI_TIEBA_API.delPost(
            forumId,
            forumName,
            threadId,
            postId,
            tbs,
            is_floor = if (isFloor) 1 else 0,
            src = if (isFloor) 3 else 1,
            is_vip_del = if (delMyPost) 0 else 1,
            delete_my_post = if (delMyPost) 1 else 0
        )

    override fun delPostFlow(
        forumId: Long,
        forumName: String,
        threadId: Long,
        postId: Long,
        tbs: String?,
        isFloor: Boolean,
        delMyPost: Boolean
    ): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API
            .delPostFlow(
                forumId,
                forumName,
                threadId,
                postId,
                isFloor = if (isFloor) 1 else 0,
                src = if (isFloor) 3 else 1,
                isVipDel = if (delMyPost) 0 else 1,
                deleteMyPost = if (delMyPost) 1 else 0,
                tbs = tbs
            )

    override fun searchPost(
        keyword: String,
        forumName: String,
        onlyThread: Boolean,
        sortMode: Int,
        page: Int,
        pageSize: Int
    ): Call<SearchPostBean> = RetrofitTiebaApi.MINI_TIEBA_API.searchPost(
        keyword,
        forumName,
        page,
        pageSize,
        only_thread = if (onlyThread) 1 else 0,
        sortMode = sortMode
    )

    override fun searchPostAsync(
        keyword: String,
        forumName: String,
        onlyThread: Boolean,
        sortMode: Int,
        page: Int,
        pageSize: Int
    ): Deferred<ApiResult<SearchPostBean>> = RetrofitTiebaApi.MINI_TIEBA_API.searchPostAsync(
        keyword,
        forumName,
        page,
        pageSize,
        only_thread = if (onlyThread) 1 else 0,
        sortMode = sortMode
    )

    override fun searchUser(keyword: String): Call<SearchUserBean> =
        RetrofitTiebaApi.MINI_TIEBA_API.searchUser(keyword)

    override fun searchUserFlow(keyword: String): Flow<SearchUserBean> =
        RetrofitTiebaApi.HYBRID_TIEBA_API.searchUserFlow(keyword)

    override fun msg(): Call<MsgBean> = RetrofitTiebaApi.NEW_TIEBA_API.msg()

    override fun msgFlow(): Flow<MsgBean> = RetrofitTiebaApi.NEW_TIEBA_API.msgFlow()

    override fun threadStore(page: Int, pageSize: Int): Call<ThreadStoreBean> =
        RetrofitTiebaApi.NEW_TIEBA_API.threadStore(
            pageSize,
            pageSize * page,
            SessionProviders.credential.getUid()
        )

    override fun threadStoreFlow(page: Int, pageSize: Int): Flow<ThreadStoreBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.threadStoreFlow(
            pageSize,
            pageSize * page
        )

    override fun removeStore(threadId: String, tbs: String): Call<CommonResponse> =
        RetrofitTiebaApi.NEW_TIEBA_API.removeStore(threadId, tbs)

    override fun removeStoreFlow(
        threadId: Long,
        forumId: Long,
        tbs: String?
    ): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.removeStoreFlow(
            threadId.toString(),
            forumId.toString(),
            tbs ?: SessionProviders.credential.getTbs()!!
        )

    override fun removeStoreFlow(threadId: String): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.removeStoreFlow(threadId)

    override fun addStore(threadId: String, postId: String, tbs: String): Call<CommonResponse> =
        RetrofitTiebaApi.NEW_TIEBA_API.addStore(
            listOf(
                CollectDataBean(
                    threadId,
                    postId,
                    "0",
                    "0"
                )
            ).toJson(),
            tbs
        )

    override fun addStoreAsync(threadId: Long, postId: Long): Deferred<ApiResult<CommonResponse>> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.addStoreAsync(
            listOf(
                NewCollectDataBean(
                    threadId.toString(),
                    postId.toString(),
                    status = 1
                )
            ).toJson()
        )

    override fun addStoreFlow(threadId: Long, postId: Long): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.addStoreFlow(
            listOf(
                NewCollectDataBean(
                    threadId.toString(),
                    postId.toString(),
                    status = 1
                )
            ).toJson()
        )


    override fun replyMe(page: Int): Call<MessageListBean> =
        RetrofitTiebaApi.NEW_TIEBA_API.replyMe(page)

    override fun replyMeAsync(page: Int): Deferred<ApiResult<MessageListBean>> =
        RetrofitTiebaApi.NEW_TIEBA_API.replyMeAsync(page)

    override fun replyMeFlow(page: Int): Flow<MessageListBean> =
        RetrofitTiebaApi.NEW_TIEBA_API.replyMeFlow(page)

    override fun atMe(page: Int): Call<MessageListBean> = RetrofitTiebaApi.NEW_TIEBA_API.atMe(page)

    override fun atMeAsync(page: Int): Deferred<ApiResult<MessageListBean>> =
        RetrofitTiebaApi.NEW_TIEBA_API.atMeAsync(page)

    override fun atMeFlow(page: Int): Flow<MessageListBean> =
        RetrofitTiebaApi.NEW_TIEBA_API.atMeFlow(page)

    override fun agreeMe(page: Int): Call<MessageListBean> =
        RetrofitTiebaApi.NEW_TIEBA_API.agreeMe(page)

    override fun threadContent(
        threadId: String, page: Int, seeLz: Boolean, reverse: Boolean
    ): Call<ThreadContentBean> = RetrofitTiebaApi.OFFICIAL_TIEBA_API.threadContent(
        threadId,
        page,
        last = if (reverse) "1" else null,
        r = if (reverse) "1" else null,
        lz = if (seeLz) 1 else 0
    )

    override fun threadContent(
        threadId: String, postId: String?, seeLz: Boolean, reverse: Boolean
    ): Call<ThreadContentBean> = RetrofitTiebaApi.OFFICIAL_TIEBA_API.threadContent(
        threadId,
        postId,
        last = if (reverse) "1" else null,
        r = if (reverse) "1" else null,
        lz = if (seeLz) 1 else 0
    )

    override fun threadContentAsync(
        threadId: String,
        page: Int,
        seeLz: Boolean,
        reverse: Boolean
    ): Deferred<ApiResult<ThreadContentBean>> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.threadContentAsync(
            threadId,
            page,
            last = if (reverse) "1" else null,
            r = if (reverse) "1" else null,
            lz = if (seeLz) 1 else 0
        )

    override fun threadContentAsync(
        threadId: String,
        postId: String?,
        seeLz: Boolean,
        reverse: Boolean
    ): Deferred<ApiResult<ThreadContentBean>> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.threadContentAsync(
            threadId,
            postId,
            last = if (reverse) "1" else null,
            r = if (reverse) "1" else null,
            lz = if (seeLz) 1 else 0
        )

    override fun submitDislike(
        dislikeBean: DislikeBean,
        stoken: String
    ): Call<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.submitDislike(
            listOf(dislikeBean).toJson(),
            stoken = stoken
        )

    override fun submitDislikeFlow(dislikeBean: DislikeBean): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.submitDislikeFlow(listOf(dislikeBean).toJson())

    override fun follow(
        portrait: String, tbs: String
    ): Call<CommonResponse> = RetrofitTiebaApi.WEB_TIEBA_API.follow(
        "https://tieba.baidu.com/i/?portrait=${
            URLEncoder.encode(
                portrait,
                "UTF-8"
            )
        }&cuid=&auth=&uid=&ssid=&from=&uid=&pu=&bd_page_type=2&auth=&originid=&mo_device=1&tbs=${tbs}&action=follow&op=follow"
    )

    override fun unfollow(
        portrait: String,
        tbs: String
    ): Call<CommonResponse> = RetrofitTiebaApi.WEB_TIEBA_API.follow(
        "https://tieba.baidu.com/i/?portrait=${
            URLEncoder.encode(
                portrait,
                "UTF-8"
            )
        }&cuid=&auth=&uid=&ssid=&from=&uid=&pu=&bd_page_type=2&auth=&originid=&mo_device=1&tbs=${tbs}&action=follow&op=unfollow"
    )

    override fun followFlow(
        portrait: String,
        tbs: String
    ): Flow<FollowBean> = RetrofitTiebaApi.OFFICIAL_TIEBA_API.followFlow(portrait, tbs)

    override fun unfollowFlow(
        portrait: String,
        tbs: String
    ): Flow<CommonResponse> = RetrofitTiebaApi.OFFICIAL_TIEBA_API.unfollowFlow(portrait, tbs)

    override fun followListFlow(page: Int, uid: Long?): Flow<FollowListBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.followListFlow(page, uid)

    override fun getAllFollowFlow(uid: Long?): Flow<FollowListBean> = flow {
        var currentPage = 1
        var hasMore = true
        var finalBean: FollowListBean? = null
        val allUsers = mutableListOf<FollowListBean.FollowUserBean>()

        while (hasMore) {
            val response = followListFlow(currentPage, uid).first()
            if (finalBean == null) {
                finalBean = response
            }
            allUsers.addAll(response.followList)
            hasMore = response.hasMore == 1
            currentPage++
        }

        finalBean?.apply {
            this.followList = allUsers
        }?.let {
            emit(it)
        }
    }.flowOn(Dispatchers.IO)

    override fun hotMessageList(): Call<HotMessageListBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.hotMessageList()

    override fun myInfo(cookie: String): Call<MyInfoBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.myInfo(cookie)

    override fun myInfoAsync(cookie: String): Deferred<ApiResult<MyInfoBean>> =
        RetrofitTiebaApi.WEB_TIEBA_API.myInfoAsync(cookie)

    override fun myInfoFlow(cookie: String): Flow<MyInfoBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.myInfoFlow(cookie)

    override fun searchForum(keyword: String): Call<SearchForumBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.searchForum(keyword)

    override fun searchForumFlow(keyword: String): Flow<SearchForumBean> =
        RetrofitTiebaApi.HYBRID_TIEBA_API.searchForumFlow(keyword)

    override fun searchThread(
        keyword: String, page: Int, order: SearchThreadOrder, filter: SearchThreadFilter,
    ): Call<SearchThreadBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.searchThread(
            keyword,
            page,
            order.toString(),
            filter.toString()
        )

    override fun searchThreadFlow(
        keyword: String, page: Int, sort: Int,
    ): Flow<SearchThreadBean> =
        RetrofitTiebaApi.HYBRID_TIEBA_API.searchThreadFlow(
            keyword,
            page,
            sort
        )

    override fun topicDetailFlow(
        topicId: String,
        topicName: String,
        isNew: Int,
        isShare: Int,
        page: Int,
        pageSize: Int,
        offset: Int,
        lastId: String
    ): Flow<TopicDetailBean> =
        RetrofitTiebaApi.HYBRID_TIEBA_API.topicDetailFlow(
            topicId,
            topicName,
            isNew,
            isShare,
            page,
            pageSize,
            offset,
            lastId
        )

    override fun searchPostFlow(
        keyword: String,
        forumName: String,
        forumId: Long,
        sortType: Int,
        filterType: Int,
        page: Int,
        pageSize: Int,
    ): Flow<SearchThreadBean> =
        RetrofitTiebaApi.HYBRID_TIEBA_API.searchThreadFlow(
            keyword,
            page,
            sortType,
            filterType,
            pageSize,
            forumName,
            ct = 2,
            isUseZonghe = null,
            clientVersion = ClientVersion.TIEBA_V12.version,
            referer = "https://tieba.baidu.com/mo/q/hybrid-usergrow-search/searchGlobal?entryPage=frs&loadingSignal=1&forumName=${forumName.urlEncode()}&forumId=$forumId&customfullscreen=1&nonavigationbar=1&cuid=${CuidUtils.getNewCuid()}&cuid_galaxy2=${CuidUtils.getNewCuid()}&cuid_gid=&timestamp=${System.currentTimeMillis()}&_client_version=${ClientVersion.TIEBA_V12.version}&_client_type=2"
        )

    override fun webUploadPic(photoInfoBean: PhotoInfoBean): Call<WebUploadPicBean> {
        var base64: String? = null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            base64 = ApiEncoding.imageToBase64(photoInfoBean.file)
        } else {
            try {
                SessionProviders.appContext.contentResolver.openAssetFileDescriptor(
                    photoInfoBean.fileUri,
                    "r"
                )?.use { afd ->
                    base64 =
                        ApiEncoding.imageToBase64(FileInputStream(afd.parcelFileDescriptor.fileDescriptor))
                }
            } catch (e: IOException) {
                e.printStackTrace()
                base64 = null
            }
        }
        return RetrofitTiebaApi.WEB_TIEBA_API.webUploadPic(base64)
    }

    override fun webReply(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String,
        content: String,
        imgInfo: String?,
        nickName: String,
        pn: String,
        bsk: String
    ): Call<WebReplyResultBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.webReply(
            content = content,
            imgInfo = imgInfo ?: "",
            forumId = forumId,
            forumName = forumName,
            tbs = tbs,
            threadId = threadId,
            nickName = nickName,
            bsk = bsk,
            referer = "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=$pn&"
        )

    override fun webReply(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String,
        content: String,
        imgInfo: String?,
        nickName: String,
        postId: String,
        floor: String,
        pn: String,
        bsk: String
    ): Call<WebReplyResultBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.webReply(
            content = content,
            imgInfo = imgInfo ?: "",
            forumId = forumId,
            forumName = forumName,
            tbs = tbs,
            threadId = threadId,
            nickName = nickName,
            postId = postId,
            floor = floor,
            bsk = bsk,
            referer = "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=$pn&"
        )

    override fun webReply(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String,
        content: String,
        imgInfo: String?,
        nickName: String,
        postId: String,
        replyPostId: String,
        floor: String,
        pn: String,
        bsk: String
    ): Call<WebReplyResultBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.webReply(
            content = content,
            imgInfo = imgInfo ?: "",
            forumId = forumId,
            forumName = forumName,
            tbs = tbs,
            threadId = threadId,
            nickName = nickName,
            postId = postId,
            replyPostId = replyPostId,
            floor = floor,
            bsk = bsk,
            referer = "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=$pn&"
        )

    override fun webReplyAsync(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String,
        content: String,
        imgInfo: String?,
        nickName: String,
        pn: String,
        bsk: String
    ): Deferred<ApiResult<WebReplyResultBean>> =
        RetrofitTiebaApi.WEB_TIEBA_API.webReplyAsync(
            content = content,
            imgInfo = imgInfo ?: "",
            forumId = forumId,
            forumName = forumName,
            tbs = tbs,
            threadId = threadId,
            nickName = nickName,
            bsk = bsk,
            referer = "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=$pn&"
        )

    override fun webReplyAsync(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String,
        content: String,
        imgInfo: String?,
        nickName: String,
        postId: String,
        floor: String,
        pn: String,
        bsk: String
    ): Deferred<ApiResult<WebReplyResultBean>> =
        RetrofitTiebaApi.WEB_TIEBA_API.webReplyAsync(
            content = content,
            imgInfo = imgInfo ?: "",
            forumId = forumId,
            forumName = forumName,
            tbs = tbs,
            threadId = threadId,
            nickName = nickName,
            postId = postId,
            floor = floor,
            bsk = bsk,
            referer = "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=$pn&"
        )

    override fun webReplyAsync(
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String,
        content: String,
        imgInfo: String?,
        nickName: String,
        postId: String,
        replyPostId: String,
        floor: String,
        pn: String,
        bsk: String
    ): Deferred<ApiResult<WebReplyResultBean>> =
        RetrofitTiebaApi.WEB_TIEBA_API.webReplyAsync(
            content = content,
            imgInfo = imgInfo ?: "",
            forumId = forumId,
            forumName = forumName,
            tbs = tbs,
            threadId = threadId,
            nickName = nickName,
            postId = postId,
            replyPostId = replyPostId,
            floor = floor,
            bsk = bsk,
            referer = "https://tieba.baidu.com/p/$threadId?lp=5028&mo_device=1&is_jingpost=0&pn=$pn&"
        )


    override fun webForumPage(
        forumName: String,
        page: Int,
        goodClassifyId: String?,
        sortType: ForumSortType,
        pageSize: Int
    ): Call<ForumBean> =
        RetrofitTiebaApi.WEB_TIEBA_API.frs(
            forumName,
            (page - 1) * pageSize,
            sortType.value,
            goodClassifyId
        )

    override fun webForumPageAsync(
        forumName: String,
        page: Int,
        goodClassifyId: String?,
        sortType: ForumSortType,
        pageSize: Int
    ): Deferred<ApiResult<ForumBean>> =
        RetrofitTiebaApi.WEB_TIEBA_API.frsAsync(
            forumName,
            (page - 1) * pageSize,
            sortType.value,
            goodClassifyId
        )

    override fun checkReportPost(postId: String): Call<CheckReportBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.checkReport(
            category = "1",
            reportParam = mapOf(
                "pid" to postId
            )
        )

    override fun checkReportPostAsync(postId: String): Deferred<ApiResult<CheckReportBean>> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.checkReportAsync(
            category = "1",
            reportParam = mapOf(
                "pid" to postId
            )
        )

    override fun initNickNameFlow(): Flow<InitNickNameBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.initNickNameFlow()

    override fun initNickNameFlow(bduss: String, sToken: String): Flow<InitNickNameBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.initNickNameFlow(bduss, sToken)

    override fun loginFlow(): Flow<LoginBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.loginFlow()

    override fun loginFlow(bduss: String, sToken: String): Flow<LoginBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.loginFlow("$bduss|", sToken, null)

    override fun profileModifyFlow(
        birthdayShowStatus: Boolean,
        birthdayTime: String,
        intro: String,
        sex: String,
        nickName: String,
    ): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.profileModify(
            birthdayShowStatus.booleanToString(),
            birthdayTime,
            intro,
            sex,
            nickName
        )

    override fun imgPortrait(file: File): Flow<CommonResponse> {
        return RetrofitTiebaApi.OFFICIAL_TIEBA_API.imgPortrait(
            MyMultipartBody.Builder("--------7da3d81520810*").apply {
                setType(MyMultipartBody.FORM)
                addFormDataPart(Param.CLIENT_VERSION, ClientVersion.TIEBA_V12.version)
                addFormDataPart("pic", "file", file.asRequestBody())
            }.build()
        )
    }

    override fun getForumListFlow(): Flow<GetForumListBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.getForumListFlow()

    override fun mSign(
        forumIds: String,
        tbs: String
    ): Flow<MSignBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.mSignFlow(forumIds, tbs)

    override fun userLikeFlow(
        pageTag: String,
        lastRequestUnix: Long,
        loadType: Int
    ): Flow<UserLikeResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_API.userLikeFlow(
            buildProtobufRequestBody(
                UserLikeRequest(
                    UserLikeRequestData(
                        common = buildCommonRequest(),
                        pageTag = pageTag,
                        lastRequestUnix = lastRequestUnix,
                        followType = 1,
                        loadType = loadType
                    )
                )
            )
        )
    }

    override fun hotThreadListFlow(tabCode: String): Flow<HotThreadListResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_API.hotThreadListFlow(
            buildProtobufRequestBody(
                HotThreadListRequest(
                    HotThreadListRequestData(
                        common = buildCommonRequest(),
                        tabCode = tabCode,
                        tabId = "1"
                    )
                )
            )
        )
    }

    override fun topicListFlow(): Flow<TopicListResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_API.topicListFlow(
            buildProtobufRequestBody(
                TopicListRequest(
                    TopicListRequestData(
                        common = buildCommonRequest(),
                        call_from = "newbang",
                        list_type = "all",
                        need_tab_list = "0",
                        fid = 0L
                    )
                )
            )
        )
    }

    override fun forumRecommendNewFlow(
        sortType: Int
    ): Flow<ForumRecommendResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_API.forumRecommendFlow(
            buildProtobufRequestBody(
                ForumRecommendRequest(
                    ForumRecommendRequestData(
                        common = buildCommonRequest(),
                        like_forum = 1,
                        recommend = 1,
                        sort_type = sortType,
                        topic = 0
                    )
                )
            )
        )
    }

    override fun forumGuideNewFlow(
        sortType: Int,
    ): Flow<ForumGuideResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_API.forumGuideFlow(
            buildProtobufRequestBody(
                ForumGuideRequest(
                    ForumGuideRequestData(
                        sort_type = sortType,
                        call_from = 0
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12
            ),
        )
    }

    override fun frsPage(
        forumName: String,
        page: Int,
        loadType: Int,
        sortType: Int,
        goodClassifyId: Int?
    ): Flow<FrsPageResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.frsPageFlow(
            buildProtobufRequestBody(
                FrsPageRequest(
                    FrsPageRequestData(
                        ad_param = buildAdParam(),
                        app_pos = buildAppPosInfo(),
                        call_from = 0,
                        category_id = 0,
                        cid = goodClassifyId ?: 0,
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        ctime = 0,
                        data_size = 0,
                        hot_thread_id = 0,
                        is_default_navtab = 0,
                        is_good = if (goodClassifyId != null) 1 else 0,
                        is_selection = 0,
                        kw = forumName.urlEncode(),
                        last_click_tid = 0,
                        load_type = loadType,
                        net_error = 0,
                        pn = page,
                        q_type = 2,
                        rn = 90,
                        rn_need = 30,
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        scr_h = getScreenHeight(),
                        scr_w = getScreenWidth(),
                        sort_type = sortType,
                        st_param = 0,
                        st_type = "recom_flist",
                        up_schema = "",
                        with_group = 1,
                        yuelaou_locate = ""
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12
            ),
            forumName = forumName.urlEncode()
        )
    }

    override fun threadList(
        forumId: Long,
        forumName: String,
        page: Int,
        sortType: Int,
        threadIds: String
    ): Flow<ThreadListResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.threadListFlow(
            buildProtobufRequestBody(
                ThreadListRequest(
                    ThreadListRequestData(
                        ad_param = AdParam(3, 0, null),
                        app_pos = buildAppPosInfo(),
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        scr_h = getScreenHeight(),
                        scr_w = getScreenWidth(),
                        forum_id = forumId,
                        forum_name = forumName,
                        pn = page,
                        q_type = 2,
                        user_id = SessionProviders.credential.getUid()?.toLongOrNull(),
                        thread_ids = threadIds,
                        sort_type = sortType,
                        need_abstract = 0,
                        st_type = 0,
                        last_click_tid = 0
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12
            )
        )
    }

    override fun generalTabList(
        forumId: Long,
        forumName: String,
        tabId: Int,
        tabType: Int,
        tabName: String,
        isGeneralTab: Int,
        pn: Int,
        sortType: Int,
        lastThreadId: Long,
        isDefaultNavTab: Int,
    ): Flow<GeneralTabListResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_POST_API.generalTabListFlow(
            buildProtobufRequestBody(
                GeneralTabListRequest(
                    GeneralTabListRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        tab_id = tabId,
                        forum_id = forumId,
                        pn = pn,
                        rn = 30,
                        scr_w = getScreenWidth(),
                        scr_h = getScreenHeight(),
                        scr_dip = SessionProviders.deviceInfo.density.toInt(),
                        last_thread_id = lastThreadId,
                        is_default_navtab = isDefaultNavTab,
                        tab_name = tabName,
                        is_general_tab = isGeneralTab,
                        sort_type = sortType,
                        tab_type = tabType,
                        ad_ext_params = "",
                        ad_bear_context = "",
                        has_ad_bear = 0,
                        ad_bear_sid = "",
                        ad_bear_sid_price = 0.0,
                        request_times = 0,
                        frs_common_info = "",
                        is_newfrs = 1,
                        is_video_doublerow = 0,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12
            )
        )
    }

    override fun syncFlow(clientId: String?): Flow<Sync> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.sync(clientId)

    override fun addPostFlow(
        content: String,
        forumId: String,
        forumName: String,
        threadId: String,
        tbs: String?,
        nameShow: String?,
        postId: String?,
        subPostId: String?,
        replyUserId: String?
    ): Flow<AddPostResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_POST_API
            .addPostFlow(
                buildProtobufRequestBody(
                    AddPostRequest(
                        AddPostRequestData(
                            anonymous = "1",
                            barrage_time = "0".takeIf { postId.isNullOrEmpty() },
                            can_no_forum = "0",
                            common = buildCommonRequest(
                                clientVersion = ClientVersion.TIEBA_V12_POST,
                                tbs = tbs ?: SessionProviders.credential.getTbs()
                            ),
                            content = content,
                            entrance_type = "0",
                            fid = forumId,
                            floor_num = "0",
                            kw = forumName,
                            is_ad = "0",
                            is_addition = "0",
                            is_barrage = "0",
                            is_feedback = "0",
                            is_giftpost = "0",
                            is_pictxt = "0",
                            is_show_bless = 0,
                            is_twzhibo_thread = "0",
                            name_show = nameShow ?: SessionProviders.credential.getNameShow()
                                .orEmpty(),
                            new_vcode = "1",
                            post_from = if (postId.isNullOrEmpty() && subPostId.isNullOrEmpty()) "13" else if (subPostId.isNullOrEmpty()) "0" else null,
                            quote_id = postId,
                            reply_uid = replyUserId.takeIf { !postId.isNullOrEmpty() },
                            repostid = postId,
                            sub_post_id = subPostId,
                            show_custom_figure = 0,
                            takephoto_num = "0",
                            tid = threadId,
                            v_fid = "".takeIf { postId.isNullOrEmpty() },
                            v_fname = "".takeIf { postId.isNullOrEmpty() },
                            vcode_tag = "12",
                        )
                    ),
                    clientVersion = ClientVersion.TIEBA_V12_POST
                )
            )
    }

    override fun userProfileFlow(uid: Long): Flow<ProfileResponse> {
        val selfUid = SessionProviders.credential.getUid()?.toLongOrNull()
        val isSelf = selfUid == uid
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.profileFlow(
            buildProtobufRequestBody(
                ProfileRequest(
                    ProfileRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        friend_uid = uid.takeIf { !isSelf },
                        friend_uid_portrait = "",
                        has_plist = 1,
                        is_from_usercenter = 1,
                        is_guest = if (isSelf) 0 else 1,
                        need_post_count = 1,
                        page = 1,
                        pn = 1,
                        q_type = 0,
                        rn = 20,
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        scr_h = getScreenHeight(),
                        scr_w = getScreenWidth(),
                        uid = selfUid,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12
            )
        )
    }

    override fun pbPageFlow(
        threadId: Long,
        page: Int,
        postId: Long,
        seeLz: Boolean,
        back: Boolean,
        sortType: Int,
        forumId: Long?,
        stType: String,
        mark: Int,
        lastPostId: Long?,
    ): Flow<PbPageResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V22_API.pbPageFlow(
            buildProtobufRequestBody(
                PbPageRequest(
                    PbPageRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V22),
                        kz = threadId,
                        pid = postId,
                        pn = page,
                        r = sortType,
                        lz = if (seeLz) 1 else 0,
                        forum_id = forumId ?: 0,
                        ad_param = com.huanchengfly.tieba.post.core.network.model.protos.pbPage.AdParam(
                            load_count = 0,
                            refresh_count = 1,
                            is_req_ad = 1
                        ),
                        mark = mark,
                        last_pid = lastPostId ?: 0,
                        app_pos = buildAppPosInfo(),
                        back = if (back) 1 else 0,
                        banner = 0,
                        broadcast_id = 0,
                        floor_rn = 4,
                        floor_sort_type = 1,
                        from_push = 0,
                        from_smart_frs = 0,
                        immersion_video_comment_source = 0,
                        is_comm_reverse = 0,
                        is_fold_comment_req = 0,
                        is_jumpfloor = 0,
                        jumpfloor_num = 0,
                        need_repost_recommend_forum = 0,
                        obj_locate = "",
                        obj_param1 = "10",
                        obj_source = "",
                        ori_ugc_type = 0,
                        pb_rn = 0,
                        q_type = 2,
                        request_times = 0,
                        rn = 15,
                        s_model = 0,
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        scr_h = getScreenHeight(),
                        scr_w = getScreenWidth(),
                        similar_from = 0,
                        source_type = 2,
                        st_type = stType,
                        thread_type = 0,
                        weipost = 0,
                        with_floor = 1
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V22
            )
        ).onEach { response ->
            // V22 门控哨兵(只取证不处置):楼中楼图退回 '[图片]' 占位时打 WARN,见 V22ImageGateSentinel
            runCatching {
                val subs = response.data_?.post_list.orEmpty()
                    .flatMap { it.sub_post_list?.sub_post_list.orEmpty() }
                V22ImageGateSentinel.report("pb/page", subs)
            }
        }
    }

    override fun pbFloorFlow(
        threadId: Long,
        postId: Long,
        forumId: Long,
        page: Int,
        subPostId: Long
    ): Flow<PbFloorResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V22_API.pbFloorFlow(
            buildProtobufRequestBody(
                PbFloorRequest(
                    PbFloorRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V22),
                        forum_id = forumId,
                        kz = threadId,
                        pid = postId,
                        pn = page,
                        spid = subPostId,
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        scr_h = getScreenHeight(),
                        scr_w = getScreenWidth(),
                        is_comm_reverse = 0,
                        ori_ugc_type = 0
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V22,
                needSToken = false
            )
        ).onEach { response ->
            // V22 门控哨兵(只取证不处置),同 pbPageFlow
            runCatching {
                V22ImageGateSentinel.report("pb/floor", response.data_?.subpost_list.orEmpty())
            }
        }
    }

    override fun searchSuggestionsFlow(keyword: String, isForum: Boolean): Flow<SearchSugResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.searchSugFlow(
            buildProtobufRequestBody(
                SearchSugRequest(
                    SearchSugRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        word = keyword,
                        isforum = isForum.booleanToString()
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun getForumDetailFlow(forumId: Long): Flow<GetForumDetailResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.getForumDetailFlow(
            buildProtobufRequestBody(
                GetForumDetailRequest(
                    GetForumDetailRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        forum_id = forumId,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun getBawuInfoFlow(forumId: Long): Flow<GetBawuInfoResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.getBawuInfoFlow(
            buildProtobufRequestBody(
                GetBawuInfoRequest(
                    GetBawuInfoRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        forum_id = forumId,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun getLevelInfoFlow(forumId: Long): Flow<GetLevelInfoResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.getLevelInfoFlow(
            buildProtobufRequestBody(
                GetLevelInfoRequest(
                    GetLevelInfoRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        forum_id = forumId,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun getMemberInfoFlow(forumId: Long): Flow<GetMemberInfoResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.getMemberInfoFlow(
            buildProtobufRequestBody(
                GetMemberInfoRequest(
                    GetMemberInfoRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        forum_id = forumId,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun forumRuleDetailFlow(forumId: Long): Flow<ForumRuleDetailResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.forumRuleDetailFlow(
            buildProtobufRequestBody(
                ForumRuleDetailRequest(
                    ForumRuleDetailRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        forum_id = forumId,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun userPostFlow(uid: Long, page: Int, isThread: Boolean): Flow<UserPostResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.userPostFlow(
            buildProtobufRequestBody(
                UserPostRequest(
                    UserPostRequestData(
                        uid = uid,
                        rn = 20,
                        is_thread = if (isThread) 1 else 0,
                        need_content = 1,
                        pn = page,
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        scr_w = getScreenWidth(),
                        scr_h = getScreenHeight(),
                        scr_dip = SessionProviders.deviceInfo.density.toDouble(),
                        q_type = 1,
                        is_view_card = if (isThread) 1 else 0,
                        subtype = 0.takeUnless { isThread },
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun userLikeForumFlow(uid: String, page: Int): Flow<UserLikeForumBean> {
        val myUid = SessionProviders.credential.getUid()
        return RetrofitTiebaApi.OFFICIAL_TIEBA_API.userLikeForumFlow(
            page = page,
            uid = myUid,
            friendUid = if (!TextUtils.equals(uid, myUid)) uid else null,
            is_guest = if (!TextUtils.equals(uid, myUid)) "1" else null
        )
    }

    override fun getUserInfoFlow(): Flow<GetUserInfoResponse> {
        return getUserInfoFlow(SessionProviders.credential.getUid()!!.toLong(), null, null)
    }

    override fun getUserInfoFlow(
        uid: Long,
        bduss: String?,
        sToken: String?,
    ): Flow<GetUserInfoResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.getUserInfoFlow(
            buildProtobufRequestBody(
                GetUserInfoRequest(
                    GetUserInfoRequestData(
                        common = buildCommonRequest(
                            clientVersion = ClientVersion.TIEBA_V12,
                            bduss = bduss,
                            stoken = sToken
                        ),
                        uid = uid,
                        scr_w = getScreenWidth()
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun getHistoryForumFlow(history: String): Flow<GetHistoryForumResponse> {
        return RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_V12_API.getHistoryForumFlow(
            buildProtobufRequestBody(
                GetHistoryForumRequest(
                    GetHistoryForumRequestData(
                        common = buildCommonRequest(clientVersion = ClientVersion.TIEBA_V12),
                        history = history,
                    )
                ),
                clientVersion = ClientVersion.TIEBA_V12,
                needSToken = true
            )
        )
    }

    override fun addThreadFlow(
        threadContent: String,
        kw: String,
        fid: String,
        title: String,
        isHide: Int,
        isTitle: Int
    ): Flow<AddThreadResponse> =
        RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_POST_API
            .addThreadFlow(
                buildProtobufRequestBody(
                    AddThreadRequest(
                        AddThreadRequestData(
                            anonymous = "1",
                            can_no_forum = "0",
                            common = buildCommonRequest(
                                clientVersion = ClientVersion.TIEBA_V12_POST,
                                tbs = SessionProviders.credential.getTbs()
                            ),
                            content = threadContent,
                            entrance_type = "0",
                            fid = fid,
                            is_hide = isHide.toString(),
                            is_ntitle = isTitle.toString(),
                            is_pictxt = "0",
                            is_show_bless = 0,
                            kw = kw,
                            name_show = SessionProviders.credential.getNameShow().orEmpty(),
                            new_vcode = "1",
                            show_custom_figure = 0,
                            takephoto_num = "0",
                            title = title,
                            vcode_tag = "12",
                        )
                    ),
                    clientVersion = ClientVersion.TIEBA_V12_POST
                )
            )

    override fun setUserBlackFlow(
        blackUid: Long,
        tbs: String,
        permList: PermissionListBean
    ): Flow<CommonResponse> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.setUserBlackFlow(
            blackUid,
            tbs,
            permList.toJson()
        )

    override fun getUserBlackInfoFlow(
        blackUid: Long
    ): Flow<GetUserBlackInfoBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.getUserBlackFlow(
            blackUid
        )

    override fun forumGuideFlow(
        sortType: Int?,
        callFrom: Int?,
        pageNo: Int,
        resNum: Int,
        topForumNum: Int?,
    ): Flow<ForumGuideBean> =
        RetrofitTiebaApi.OFFICIAL_TIEBA_API.forumGuideFlow(
            sortType,
            callFrom,
            pageNo,
            resNum,
            topForumNum
        )

    /**
     * 关注吧列表
     * @param sortType 排序方式
     * @param callFrom 1来自主页?(包含热搜数据),3 来自签到页?
     */
    override fun allForumGuideFlow(
        sortType: Int?,
        callFrom: Int?,
    ): Flow<ForumGuideBean> = flow {
        // A8 single-flight(2026-09-12):首页慢路径与 OKSigner 各调一次全量同步,
        // 关注吧多时各自发起 50+ 个串行分页请求(实测 2700 吧 = 54 页/次),两次全量
        // 完全重复还互相抢带宽。这里在 30s 新鲜度窗口内复用最近一次完整结果:
        // - 并发场景(启动时首页与开机自签同时触发):后到者排队等锁,等锁期间前者
        //   已完成并写入 lastResult,double-check 命中 → 全量只执行一次;
        // - 30s 窗口外(手动刷新/用户刚关注了新吧再签到)重新拉取,保证数据新鲜;
        // - 窗口内复用意味着"刚关注的新吧最长 30s 内不出现在签到列表",下次自愈,可接受。
        //
        // 缓存键 = (uid, sortType, callFrom):
        // - uid:关注吧列表是账号私有数据。切账号入口(switchAccount/exit)不经过本类,
        //   若不把 uid 编进键,A 的全量结果会在 30s 窗口内被 B 的首页/自签命中——
        //   B 的首页整体替换成 A 的关注吧(FollowedForumsCache.updateAll 连带污染),
        //   这是 2026-09-12 审查确认的回归面;
        // - sortType/callFrom:当前调用方全走默认值 (3,3),但接口签名接受任意值,
        //   不编进键就是"未来换参拿错数据"的哑弹。
        val uid = SessionProviders.credential.getUid().orEmpty()
        val cacheKey = "$uid|$sortType|$callFrom"
        fun freshLocked(): Pair<String, ForumGuideBean>? =
            lastGuideSyncResult?.takeIf {
                it.first == cacheKey &&
                    SystemClock.elapsedRealtime() - lastGuideSyncAt < ALL_GUIDE_FRESH_WINDOW_MS
            }?.let { it.first to it.second }
        val hit = synchronized(allGuideSyncLock) { freshLocked() }
        if (hit != null) {
            emit(hit.second.copy())
            return@flow
        }
        allGuideSyncMutex.withLock {
            val cached = synchronized(allGuideSyncLock) { freshLocked() }
            if (cached != null) {
                emit(cached.second.copy())
                return@flow
            }
            val bean = rawAllForumGuideFlow(sortType, callFrom).first()
            // raw 流保证至少发射一次(首页请求失败会直接抛出,不会空完成),first() 非空
            synchronized(allGuideSyncLock) {
                lastGuideSyncResult = cacheKey to bean.copy()
                lastGuideSyncAt = SystemClock.elapsedRealtime()
            }
            // 下发独立副本:缓存实例与 emit 实例分离,防止任何调用方就地改 var 字段
            // 反向污染缓存(ForumGuideBean 字段全是 var,raw 流还会原地改 likeForum)
            emit(bean.copy())
        }
    }

    /** 全量同步共享状态的锁(保护 [lastGuideSyncResult] / [lastGuideSyncAt]) */
    private val allGuideSyncLock = Any()

    /** 全量同步互斥:同窗口内只允许一个执行体真正发起分页拉取 */
    private val allGuideSyncMutex = Mutex()

    /** 最近一次全量同步结果:(cacheKey, bean 快照)。键含 uid,切账号即失效,见 allForumGuideFlow 注释 */
    @Volatile
    private var lastGuideSyncResult: Pair<String, ForumGuideBean>? = null

    @Volatile
    private var lastGuideSyncAt = 0L

    /** 全量同步结果的新鲜度窗口;窗口内的并发/连续调用复用同一次执行(A8) */
    private const val ALL_GUIDE_FRESH_WINDOW_MS = 30_000L

    private fun rawAllForumGuideFlow(
        sortType: Int?,
        callFrom: Int?,
    ): Flow<ForumGuideBean> = flow {
        var currentPage = 1
        var hasMore = true
        var finalBean: ForumGuideBean? = null
        val allLikeForums = mutableListOf<ForumGuideBean.LikeForum>()

        // 上限含 60 页(常量口径:60 页 = 3000 吧,与文档"翻页上限(60 页/3000 吧)"一致)。
        // 此处此前是 `currentPage < MAX_FORUM_GUIDE_PAGES`——从 1 起只走到 59,
        // 常量与日志宣称的 60 页永远达不到,恰好在 3000 吧边缘(第 60 页)的用户
        // 会被无谓标记 truncated 并跳过缓存整体替换。
        while (hasMore && currentPage <= MAX_FORUM_GUIDE_PAGES) {
            val response = forumGuideFlow(
                sortType = sortType,
                callFrom = callFrom,
                pageNo = currentPage,
                resNum = 50,
                topForumNum = 0
            ).first()
            if (finalBean == null) {
                finalBean = response
            }
            // 空列表保护:服务端一直返回 has_more = 1 却不给数据时直接退出,避免空转刷接口
            if (response.likeForum.isEmpty()) {
                break
            }
            response.likeForum.let { allLikeForums.addAll(it) }
            hasMore = response.likeForumHasMore == true
            currentPage++
        }
        if (hasMore) {
            Log.w(
                TAG,
                "allForumGuideFlow: 全量同步未完整拉取(达最大翻页上限 $MAX_FORUM_GUIDE_PAGES" +
                    "或服务端异常中断),结果已标记 truncated,下游禁止整体替换缓存"
            )
        }

        finalBean?.apply {
            // toList() 快照,避免下游持有可变列表引用
            this.likeForum = allLikeForums.toList()
            // 截断标记(外部审查 1.2):hasMore 为 true 意味着因页数上限或服务端异常中断,
            // 结果不完整——下游禁止用它整体替换缓存/首页列表,防止静默丢吧
            this.truncated = hasMore
        }?.let {
            emit(it)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 关注吧列表（并行拉取前 [pageCount] 页，每页 50 个）
     *
     * 供首页等只需要少量关注吧数据的场景使用：
     * 全量分页拉取（allForumGuideFlow）在关注吧数量很大时会发起大量串行请求，
     * 严重阻塞页面加载。这里改为并行请求固定的前几页，任一页 hasMore = false 即截断。
     */
    override fun forumGuideFirstPagesFlow(
        sortType: Int?,
        callFrom: Int?,
        pageCount: Int,
    ): Flow<ForumGuideBean> = flow {
        // 先拉第 1 页,仅当还有更多时才并发拉后续页,避免关注吧较少时发出空请求
        val firstPage = forumGuideFlow(
            sortType = sortType,
            callFrom = callFrom,
            pageNo = 1,
            resNum = 50,
            topForumNum = 0
        ).first()

        val restPages = if (firstPage.likeForumHasMore == true && pageCount > 1) {
            coroutineScope {
                (2..pageCount).map { page ->
                    async {
                        try {
                            forumGuideFlow(
                                sortType = sortType,
                                callFrom = callFrom,
                                pageNo = page,
                                resNum = 50,
                                topForumNum = 0
                            ).first()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.map { it.await() }
            }
        } else {
            emptyList()
        }

        val allLikeForums = mutableListOf<ForumGuideBean.LikeForum>()
        allLikeForums.addAll(firstPage.likeForum)
        // restPages 下标 0 对应第 2 页,故页码为 index + 2
        for ((index, response) in restPages.withIndex()) {
            if (response == null) {
                // 单页失败不再中断:后续页可能已成功取回,跳过本页继续合并,避免静默丢数据
                Log.w(TAG, "forumGuideFirstPagesFlow: 第 ${index + 2} 页拉取失败,跳过")
                continue
            }
            allLikeForums.addAll(response.likeForum)
            if (response.likeForumHasMore != true) break
        }

        // toList() 快照,避免下游持有可变列表引用
        emit(firstPage.copy(likeForum = allLikeForums.toList()))
    }.flowOn(Dispatchers.IO)

    override fun addPollPost(forumId: Long?, threadId: Long, option: String): Flow<CommonResponse> =
        RetrofitTiebaApi.HYBRID_TIEBA_API.addPollPost(
            forumId,
            threadId,
            option
        )

    override fun addPollPostProtobuf(
        forumId: Long?,
        threadId: Long,
        option: String
    ): Flow<AddPollPostReponse> =
        RetrofitTiebaApi.OFFICIAL_PROTOBUF_TIEBA_POST_API.addPollPostProtobuf(
            buildProtobufRequestBody(
                AddPollPostRequest(
                    AddPollPostRequestDate(
                        forum_id = forumId ?: 0L,
                        thread_id = threadId,
                        options = option,
                    )
                ),
                // 此前漏传落到默认 V11:请求同时写出 _client_version=11.10.8.6 表单字段
                // 与 V12_POST 端点身份,同请求双版本号自相矛盾(protobuf 调用点唯一漏网),
                // 与同端点 addPostFlow 口径对齐
                clientVersion = ClientVersion.TIEBA_V12_POST
            )
        )
}
