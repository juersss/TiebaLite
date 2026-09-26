package com.huanchengfly.tieba.post.core.data

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * core:data 的 Hilt 装配（Phase 5 建，Phase 6.5 改为 `@Provides` 走进程级单例工厂）。
 *
 * 为什么不再是 `@Binds`：`SettingsRepository` 现在必须与 `Context.appPreferences`
 * （同步读点走的那条路）**是同一个实例**——否则两条路径各持一份同步读缓存，
 * "写后立即可见"会在两套缓存间失配。见 [settingsRepository]。
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = settingsRepository(context)
}
