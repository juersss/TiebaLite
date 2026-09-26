package com.huanchengfly.tieba.post.ui.page.main.explore.hot

import com.huanchengfly.tieba.post.api.interfaces.ITiebaApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * ViewModel 构造级测试（单测盲区补丁，2026-09-12）。
 *
 * 背景：现有套件全部只测 producer/reducer/store（纯 JVM，不构造 ViewModel），
 * 而 BaseViewModel 的构造顺序缺陷（uiState 在基类构造期立即求值 → 经
 * createPartialChangeProducer() 动态分发读取尚未赋值的子类构造注入属性）
 * 只会在**真实构造**时爆炸——2026-09-12 热榜页实机 NPE 即漏过全绿门禁。
 * 本类用最小成本把"构造"纳入测试覆盖：fake 走 JDK 动态代理（ITiebaApi 127 个
 * 方法零手写），Main dispatcher 由 kotlinx-coroutines-test 提供。
 *
 * 锁定语义：BaseViewModel.uiState 必须 by lazy——构造注入属性在 super 构造期
 * 尚未赋值，若恢复为属性初始化器立即求值，本用例将抛 non-null NPE（红）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HotViewModelConstructionTest {

    /**
     * 动态代理 stub：任何 API 方法被真正调用即抛错——构造测试只允许组装
     * flow 链，不允许触网。flow 均为冷流且无 intent 发射，代理不应被触达。
     */
    private fun stubTiebaApi(): ITiebaApi = Proxy.newProxyInstance(
        ITiebaApi::class.java.classLoader,
        arrayOf(ITiebaApi::class.java),
    ) { _, method, _ ->
        error("构造测试不应触达 API 方法: ${method.name}")
    } as ITiebaApi

    @Before
    fun setUp() {
        // 基类 stateIn 挂 viewModelScope(Dispatchers.Main.immediate):纯 JVM 无
        // Android Main,必须由 test dispatcher 顶替;不 advance 调度,收集协程
        // 不执行,配合无 intent 发射保证 stub 零触达
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun constructionWithInjectedApiCompletesAndExposesInitialState() {
        val viewModel = HotViewModel(stubTiebaApi())

        // uiState 首次访问触发 lazy 求值:创建 producer + 组装 flow 链,
        // 构造注入的 tiebaApi 此时已完成字段赋值,不得 NPE
        val state = viewModel.uiState.value

        assertEquals(HotUiState(), state)
    }

    @Test
    fun repeatedConstructionIsStable() {
        // 两次构造跨 lazy 实例边界,防"首实例特殊路径"式假绿
        val first = HotViewModel(stubTiebaApi())
        val second = HotViewModel(stubTiebaApi())

        assertEquals(first.uiState.value, second.uiState.value)
    }
}
