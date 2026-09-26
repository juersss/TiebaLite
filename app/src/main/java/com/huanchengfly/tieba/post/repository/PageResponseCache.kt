package com.huanchengfly.tieba.post.repository

/**
 * 列表端点响应的会话级缓存,用于替代此前散落在各 Repository 的
 * `lastHash` / `lastResponse` 两个全局字段。
 *
 * 旧实现的三个缺陷:
 * 1. **串味** —— 只有一个缓存槽,并发请求(吧页 + 快捷预览 + 其它入口)下后来者
 *    覆盖先到者,读取方可能拿到另一个吧/另一分区的响应;
 * 2. **撕裂读** —— 两个字段都非 volatile,`lastHash == hash` 与 `lastResponse!!`
 *    之间存在被其它线程改写的窗口;
 * 3. **失败污染** —— 旧代码在**发起请求前**就写入 `lastHash`,请求失败后 hash 已
 *    更新而 response 仍是上一次的旧对象,下一次相同请求会命中并返回**错误的页**。
 *
 * 本实现按请求 hash 索引、读写整体加锁、只在成功响应落地时写入,并按容量上限
 * 淘汰最久未使用的条目,避免持有无界数量的响应对象。
 */
internal class PageResponseCache<T : Any>(private val maxSize: Int = 4) {
    private val lock = Any()
    private val entries = LinkedHashMap<String, T>()

    fun get(hash: String): T? = synchronized(lock) { entries[hash] }

    fun put(hash: String, value: T) = synchronized(lock) {
        // 重新插入以刷新"最近使用"顺序
        entries.remove(hash)
        entries[hash] = value
        while (entries.size > maxSize) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
    }

    /** 仅供单测:清空缓存,避免用例间互相污染(本类的持有者是进程级单例) */
    fun clearForTest() = synchronized(lock) { entries.clear() }
}
