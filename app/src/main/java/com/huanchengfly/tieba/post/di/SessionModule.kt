package com.huanchengfly.tieba.post.di

import com.huanchengfly.tieba.post.api.session.AppContextProvider
import com.huanchengfly.tieba.post.api.session.ClientConfigProvider
import com.huanchengfly.tieba.post.api.session.ClientIdStore
import com.huanchengfly.tieba.post.api.session.CredentialProvider
import com.huanchengfly.tieba.post.api.session.DeviceInfoProvider
import com.huanchengfly.tieba.post.api.session.OAIDProvider
import com.huanchengfly.tieba.post.api.session.ResourceProvider
import com.huanchengfly.tieba.post.components.ClientConfigManager
import com.huanchengfly.tieba.post.session.SessionManager
import com.huanchengfly.tieba.post.session.impl.AccountCredentialProvider
import com.huanchengfly.tieba.post.session.impl.AppClientIdStore
import com.huanchengfly.tieba.post.session.impl.AppContextProviderImpl
import com.huanchengfly.tieba.post.session.impl.AppDeviceInfoProvider
import com.huanchengfly.tieba.post.session.impl.AppOAIDProvider
import com.huanchengfly.tieba.post.session.impl.AppResourceProvider
import com.huanchengfly.tieba.post.session.impl.SessionManagerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 把 app 侧实现绑到 api 层的 Provider 接口与会话/客户端配置实体上
 * ——结构大改 Phase 3a（Provider 开缝）+ Phase 6（SessionManager/ClientConfigManager）。
 *
 * 绑成 [Singleton]：Provider 是无状态委托，入口点每次取值都走这里，
 * 单例可避免重复构造（Hilt 默认每次注入新实例）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
    @Binds
    @Singleton
    abstract fun bindSessionManager(impl: SessionManagerImpl): SessionManager

    @Binds
    @Singleton
    abstract fun bindCredentialProvider(impl: AccountCredentialProvider): CredentialProvider

    @Binds
    @Singleton
    abstract fun bindDeviceInfoProvider(impl: AppDeviceInfoProvider): DeviceInfoProvider

    @Binds
    @Singleton
    abstract fun bindAppContextProvider(impl: AppContextProviderImpl): AppContextProvider

    @Binds
    @Singleton
    abstract fun bindResourceProvider(impl: AppResourceProvider): ResourceProvider

    @Binds
    @Singleton
    abstract fun bindOAIDProvider(impl: AppOAIDProvider): OAIDProvider

    @Binds
    @Singleton
    abstract fun bindClientConfigProvider(impl: ClientConfigManager): ClientConfigProvider

    @Binds
    @Singleton
    abstract fun bindClientIdStore(impl: AppClientIdStore): ClientIdStore
}
