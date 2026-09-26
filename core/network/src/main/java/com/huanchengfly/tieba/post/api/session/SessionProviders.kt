package com.huanchengfly.tieba.post.api.session

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt 入口点：让"无法参与依赖注入的静态上下文"拿到 Provider。
 *
 * 典型场景是 Retrofit 接口方法的 Kotlin 默认参数——接口没有实例、无法注入，
 * 默认值里却要读 tbs/stoken/scr_dip。这类地方统一经本入口点取值，
 * 而不是直接调 `AccountUtil`/`App.ScreenInfo`（后者会把 api 钉死在 app 上）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SessionEntryPoint {
    fun credentialProvider(): CredentialProvider

    fun deviceInfoProvider(): DeviceInfoProvider

    fun appContextProvider(): AppContextProvider

    fun resourceProvider(): ResourceProvider

    fun oaidProvider(): OAIDProvider

    fun clientConfigProvider(): ClientConfigProvider

    fun clientIdStore(): ClientIdStore
}

/**
 * 静态访问点。必须在 Application.onCreate 里先 [install]，之后才能取用
 * （首个网络请求远晚于 onCreate，时序上安全）。
 */
object SessionProviders {
    @Volatile
    private var installedContext: Context? = null

    fun install(context: Context) {
        installedContext = context.applicationContext
    }

    private val entryPoint: SessionEntryPoint
        get() = EntryPointAccessors.fromApplication(
            checkNotNull(installedContext) { "SessionProviders 未初始化：需在 Application.onCreate 调用 install()" },
            SessionEntryPoint::class.java,
        )

    val credential: CredentialProvider get() = entryPoint.credentialProvider()

    val deviceInfo: DeviceInfoProvider get() = entryPoint.deviceInfoProvider()

    /** 应用 Context：给必须拿 Context 的静态位置（如 Retrofit 默认参数）用 */
    val appContext: Context get() = entryPoint.appContextProvider().appContext

    val strings: ResourceProvider get() = entryPoint.resourceProvider()

    val oaid: OAIDProvider get() = entryPoint.oaidProvider()

    val clientConfig: ClientConfigProvider get() = entryPoint.clientConfigProvider()

    val clientIds: ClientIdStore get() = entryPoint.clientIdStore()
}
