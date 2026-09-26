package com.huanchengfly.tieba.post.session.impl

import com.huanchengfly.tieba.post.api.session.CredentialProvider
import com.huanchengfly.tieba.post.session.SessionManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * [CredentialProvider] 的 app 侧实现（Phase 6 起改走注入的 [SessionManager]，
 * 不再直达 `AccountUtil` 门面）——取值逐字等价于迁移前的 `AccountUtil`。
 */
class AccountCredentialProvider @Inject constructor(
    private val sessionManager: SessionManager,
) : CredentialProvider {
    override fun getUid(): String? = sessionManager.getUid()

    override fun getBduss(): String? = sessionManager.getBduss()

    override fun getSToken(): String? = sessionManager.getSToken()

    override fun getTbs(): String? = sessionManager.getTbs()

    override fun setTbs(tbs: String) {
        sessionManager.setTbs(tbs)
    }

    override suspend fun fetchAccountTbs(): String? = sessionManager.fetchAccountFlow().first().tbs

    override fun getZId(): String = sessionManager.getZId()

    override fun getCookie(): String? = sessionManager.getCookie()

    override fun isLoggedIn(): Boolean = sessionManager.isLoggedIn()

    override fun getNameShow(): String? = sessionManager.getNameShow()
}