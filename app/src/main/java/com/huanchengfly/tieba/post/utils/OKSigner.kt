package com.huanchengfly.tieba.post.utils

import android.content.Context
import android.util.Log
import com.huanchengfly.tieba.post.core.data.appPreferences
import com.huanchengfly.tieba.post.api.TiebaApi
import com.huanchengfly.tieba.post.api.models.CommonResponse
import com.huanchengfly.tieba.post.api.models.MSignBean
import com.huanchengfly.tieba.post.api.models.SignResultBean
import com.huanchengfly.tieba.post.api.retrofit.exception.TiebaApiException
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorCode
import com.huanchengfly.tieba.post.api.retrofit.exception.getErrorMessage
import com.huanchengfly.tieba.post.models.SignDataBean
import com.huanchengfly.tieba.post.models.database.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom
import kotlin.properties.Delegates

abstract class IOKSigner(
    context: Context
) {
    private val contextWeakReference: WeakReference<Context> = WeakReference(context)

    val context: Context
        get() = contextWeakReference.get()!!

    abstract suspend fun start(): Boolean

    fun signFlow(signDataBean: SignDataBean): Flow<SignResultBean> {
        return TiebaApi.getInstance()
            .signFlow(signDataBean.forumId, signDataBean.forumName, signDataBean.tbs)
    }

    fun getSignDelay(): Long {
        return if (context.appPreferences.oksignSlowMode.value) {
            ThreadLocalRandom.current().nextInt(3500, 8000).toLong()
        } else {
            // 固定间隔是可被服务端识别的规律,加入随机抖动(移植自 PC 端脚本思路)
            ThreadLocalRandom.current().nextInt(1500, 2500).toLong()
        }
    }

    /**
     * 签到结果分类(移植自 PC 端 Tiebasign 脚本):
     * - error_code 为空/0/160002(今日已签)视为成功
     * - 其余错误码转换为 TiebaApiException,由调用方按致命/瞬态分类处理
     */
    protected fun classifySignFlow(signDataBean: SignDataBean): Flow<SignResultBean> =
        signFlow(signDataBean).map { bean ->
            val code = bean.errorCode
            if (code.isNullOrEmpty() || code == "0" || code == SIGN_ALREADY_SIGNED) {
                bean
            } else {
                throw TiebaApiException(
                    CommonResponse(
                        errorCode = code.toIntOrNull() ?: CommonResponse.ERROR_CODE_UNKNOWN,
                        errorMsg = bean.errorMsg ?: "签到失败"
                    )
                )
            }
        }

    companion object {
        /** 今日已签:按成功处理 */
        const val SIGN_ALREADY_SIGNED = "160002"

        /** 致命错误码:重试无意义(吧被封禁/贴吧不存在/tbs 失效) */
        val SIGN_FATAL_CODES = setOf("340006", "340008", "300004", "110001")
    }
}

/*
class MultiAccountSigner(
        context: Context
) : IOKSigner(context) {
    private val accounts: MutableList<Account> = mutableListOf()

    override suspend fun start() {
        accounts.clear()
        accounts.addAll(AccountUtil.allAccounts)
    }

    interface ProgressListener {
        fun onStart(
                total: Int
        )

        fun onProgressStart(
                signDataBean: SignDataBean,
                current: Int,
                total: Int
        )

        fun onProgressFinish(
                signResultBean: SignResultBean,
                current: Int,
                total: Int
        )

        fun onFinish(
                success: Boolean,
                signedCount: Int,
                total: Int
        )

        fun onFailure(
                current: Int,
                total: Int,
                errorCode: Int,
                errorMsg: String
        )
    }
}
*/

class SingleAccountSigner(
    context: Context,
    private val account: Account
) : IOKSigner(context) {
    companion object {
        const val TAG = "SingleAccountSigner"
    }

    private val signData: MutableList<SignDataBean> = mutableListOf()
    private var position = 0
    private var successCount = 0
    private var totalCount = 0
    private var mSignCount = 0

    var lastFailure: Throwable? = null

    /**
     * 关注吧列表是否被截断(外部审查-截断提示):截断时签到列表缺尾部,
     * "成功 N 个"不能当作完整结果——完成通知会据此追加"列表不完整"提示。
     */
    var listTruncated: Boolean = false
        private set

    private var mProgressListener: ProgressListener? = null

    fun setProgressListener(listener: ProgressListener?): SingleAccountSigner {
        mProgressListener = listener
        return this
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun start(): Boolean {
        var result = false
        signData.clear()
        var userName: String by Delegates.notNull()
        var tbs: String by Delegates.notNull()
        Log.i(TAG, "start")
        AccountUtil.fetchAccountFlow(account)
            .flatMapConcat { account ->
                userName = account.name
                tbs = account.tbs
                TiebaApi.getInstance().getForumListFlow()
            }
            .zip(
                TiebaApi.getInstance().allForumGuideFlow()
            ) { getForumListBean, forumGuideBean ->
                if (forumGuideBean.truncated) {
                    // 截断保护(外部审查 v2-R1):全量同步被截断时,签到列表缺尾部——
                    // 照常签已有部分,但必须留日志避免静默漏签
                    Log.w(TAG, "全量同步被截断(${forumGuideBean.likeForum.size} 个吧),本次签到列表不完整")
                    listTruncated = true
                }
                val useMSign = context.appPreferences.oksignUseOfficialOksign.value
                // 非空声明无默认值,缺键留运行期 null:toIntOrNull 降级(mSign 门槛缺失=不启用
                // mSign 走逐条,level 缺失=0 不拦人),不得让整个签到在 catch 里静默中止
                val mSignLevel = getForumListBean.level?.toIntOrNull() ?: 0
                val mSignMax = getForumListBean.msignStepNum?.toIntOrNull() ?: 0
                signData.addAll(
                    forumGuideBean.likeForum
                        .filter { it.isSign != 1 }
                        .map {
                            SignDataBean(
                                it.forumName,
                                it.forumId.toString(),
                                userName,
                                tbs,
                                it.levelId.toInt() >= mSignLevel && signData.size < mSignMax
                            )
                        }
                )
                totalCount = signData.size
                mSignCount = 0
                (if (useMSign) {
                    val mSignData = signData.filter { it.canUseMSign }
                    TiebaApi.getInstance().mSign(mSignData.joinToString(",") { it.forumId }, tbs)
                        .map { it.info }
                } else {
                    flow { emit(emptyList()) }
                })
                    .onStart {
                        withContext(Dispatchers.Main) {
                            mProgressListener?.onStart(totalCount)
                        }
                    }
                    .catch { e ->
                        // 静默降级为逐条签到前必须留痕:mSign 失败(如 tbs 失效 110001)后
                        // 剩余吧会逐条各自失败刷屏,根因只在这里可见(SIGN_FATAL_CODES 含
                        // 110001,逐条路径不会重试,也不会再报 tbs 语义)
                        Log.w(
                            TAG,
                            "mSign 失败,降级为逐条签到: ${e.getErrorCode()} ${e.getErrorMessage()}"
                        )
                        emit(emptyList())
                    }
            }
            .flattenConcat()
            .flatMapConcat { mSignInfo ->
                val newSignData = if (mSignInfo.isNotEmpty()) {
                    val mSignInfoMap = mutableMapOf<String, MSignBean.Info>()
                    mSignInfo.forEach {
                        mSignInfoMap[it.forumId] = it
                    }
                    val signedCount = mSignInfo.filter { it.signed == "1" }.size
                    successCount += signedCount
                    signData
                        .filter { !it.canUseMSign || mSignInfoMap[it.forumId]?.signed != "1" }
                } else {
                    signData.toList()
                }
                mSignCount = totalCount - newSignData.size
                newSignData
                    .asFlow()
                    .onEach {
                        position = signData.indexOf(it)
                        withContext(Dispatchers.Main) {
                            mProgressListener?.onProgressStart(
                                it,
                                position,
                                signData.size
                            )
                        }
                    }
                    .onEmpty {
                        withContext(Dispatchers.Main) {
                            mProgressListener?.onFinish(
                                successCount == totalCount,
                                successCount,
                                totalCount
                            )
                        }
                        result = true
                    }
                    .flatMapConcat {
                        if (!context.appPreferences.oksignFailAutoStop.value) {
                            classifySignFlow(it)
                                .retryWhen { cause, attempt ->
                                    // 瞬态服务端错误重试 2 次;致命错误与网络异常交由外层报告。
                                    // 间隔必须落在 retryWhen 内:重试若无间隔就是对同一接口的
                                    // 无抖动三连发,正是 getSignDelay 注释要避免的可识别规律
                                    val shouldRetry = cause is TiebaApiException
                                        && cause.code.toString() !in SIGN_FATAL_CODES
                                        && attempt < 2
                                    if (shouldRetry) delay(getSignDelay())
                                    shouldRetry
                                }
                                .catch { e ->
                                result = false
                                lastFailure = e
                                withContext(Dispatchers.Main) {
                                    mProgressListener?.onFailure(
                                        position,
                                        totalCount,
                                        e.getErrorCode(),
                                        e.getErrorMessage()
                                    )
                                }
                                delay(getSignDelay())
                            }
                        } else classifySignFlow(it)
                    }
            }
            .catch { e ->
                result = false
                lastFailure = e
                withContext(Dispatchers.Main) {
                    mProgressListener?.onFailure(
                        position,
                        totalCount,
                        e.getErrorCode(),
                        e.getErrorMessage()
                    )
                }
                delay(getSignDelay())
            }
            .onCompletion {
                withContext(Dispatchers.Main) {
                    mProgressListener?.onFinish(
                        successCount == totalCount,
                        successCount,
                        totalCount
                    )
                }
            }
            .collect {
                result = true
                successCount += 1
                mProgressListener?.onProgressFinish(
                    signData[position],
                    it,
                    position,
                    totalCount
                )
                delay(getSignDelay())
            }
        return result
    }
}

interface ProgressListener {
    fun onStart(
        total: Int
    )

    fun onProgressStart(
        signDataBean: SignDataBean,
        current: Int,
        total: Int
    )

    fun onProgressFinish(
        signDataBean: SignDataBean,
        signResultBean: SignResultBean,
        current: Int,
        total: Int
    )

    fun onFinish(
        success: Boolean,
        signedCount: Int,
        total: Int
    )

    fun onFailure(
        current: Int,
        total: Int,
        errorCode: Int,
        errorMsg: String
    )
}
