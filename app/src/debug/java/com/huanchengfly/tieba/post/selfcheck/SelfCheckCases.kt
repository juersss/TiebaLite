package com.huanchengfly.tieba.post.selfcheck

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.huanchengfly.tieba.post.core.data.SettingsKeys
import com.huanchengfly.tieba.post.core.data.appPreferences
import com.huanchengfly.tieba.post.core.data.dataStore
import com.huanchengfly.tieba.post.core.data.settingsRepository
import com.huanchengfly.tieba.post.database.AppDatabaseEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.huanchengfly.tieba.post.core.network.model.protos.MyAgreeOp
import com.huanchengfly.tieba.post.core.network.model.protos.OpRecord
import com.huanchengfly.tieba.post.core.network.model.protos.adoptAuthoritative
import com.huanchengfly.tieba.post.core.network.model.protos.agreeCountDelta
import com.huanchengfly.tieba.post.core.network.model.protos.displayDelta
import com.huanchengfly.tieba.post.core.network.model.protos.reverted
import com.huanchengfly.tieba.post.core.common.gzipCompress
import com.huanchengfly.tieba.post.core.common.resolvePhotoViewDisplayUrl
import com.huanchengfly.tieba.post.core.common.resolvePhotoViewDownloadUrl
import com.huanchengfly.tieba.post.core.common.upgradeImageUrlToHttps
import com.huanchengfly.tieba.post.utils.AccountUtil
import com.huanchengfly.tieba.post.utils.DatabaseUtil
import com.huanchengfly.tieba.post.utils.FollowedForumsCache
import com.huanchengfly.tieba.post.utils.HistoryUtil
import com.huanchengfly.tieba.post.utils.ProcessUtil
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * 自检用例总清单。
 *
 * 分组与分工（都跑在 debug 源码集里，release 包连 class 都没有）：
 * - 纯逻辑：不依赖设备状态的纯函数断言（进不来单测的 Compose/Android 侧逻辑除外）；
 * - 数据红线：落盘名/键名/可读性不变式（结构大改全程都要守）；
 * - 安全策略：红线不能被悄悄放开（明文流量等）；
 * - 网络只读：吧页 / 帖子页 / 搜索的真实只读链路；
 * - 图片链路：真实下载落盘 + 分享 URI 可达；
 * - UI 导航：路由表完整性、深链可解析、页面可达性 + 崩溃残留检测。
 *
 * ★ 红线：自检**绝不触碰写操作**（发帖/回复/楼中楼回复/带图回复/投票提交一律不测；
 *   设置写入、Room 写入同样不测——全是只读用例）；赞踩只跑本地差分模型，不发真实请求。
 */
/**
 * **主进程身份判定不变式**（2026-09-26 新增，守 §8.7 候选 4 的跨进程写收口）。
 *
 * 为什么必要：`App.onCreate` 在**每个**进程都会跑，而 `OKSignService` 声明了
 * `android:process=":oksign"`（独立进程）。`tblite.db` 与 `app_preferences` 都不是
 * 多进程安全的，所以收口后"客户端标识的联网同步 + 落盘"只允许主进程做
 * （`ClientUtils.init(syncEnabled = ProcessUtil.isMainProcess(...))`）。
 *
 * 这条守的是收口的**判据本身**：若 `isMainProcess()` 在主进程误判为 false，主进程会
 * 静默跳过 client_id 落盘 → 全部请求缺 `client_id`、设置不生效——**不崩、不报错、
 * logcat 无线索**。本用例在主进程里跑，断言它必须为 true。
 *
 * 只读：不写任何持久层。
 */
object ProcessIdentityCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "主进程身份判定（跨进程写收口）"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val name = ProcessUtil.currentProcessName(context)
        expect(name == context.packageName) {
            "进程名解析异常：$name（期望 ${context.packageName}）"
        }
        expect(ProcessUtil.isMainProcess(context)) {
            "主进程被判为非主进程（进程名=$name，包名=${context.packageName}）——" +
                "客户端标识将不再落盘、设置同步会被跳过"
        }
        // 判定结果进程内缓存，不得翻转（否则同一进程内前后行为不一致）
        expect(ProcessUtil.isMainProcess(context)) { "二次判定结果不一致（缓存失效）" }
        return pass()
    }
}

/**
 * **账号隔离 · 赞踩记录按 uid 分文件**（2026-09-26 新增）。
 *
 * 为什么必要：赞踩记录按账号 UID 分文件持久化（`agree_op_records_u_<uid>.xml`，
 * 见 `OpRecordStore.accountPrefsName`）。**uid 为空时会回落到不区分账号的旧单文件
 * `agree_op_records.xml`**——那是迁移期兜底；若在"已登录"状态下发生，A 的赞踩记录就会
 * 写进共享文件、被 B 读到（串号，正是"账号隔离六处"要防的事）。
 *
 * 守两件事（都只读）：
 * 1. 已登录时 uid 必须是非空、且可安全用于文件名的串——否则记录会落到共享文件；
 * 2. 不得存在 `agree_op_records_u_.xml` / `agree_op_records_u_null.xml` 这类坏名文件
 *    （说明 uid 取值链路上出现过空值/字面量 null）。
 */
object AccountIsolationCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "账号隔离 · 赞踩记录按 uid 分文件"

    /** 文件名安全字符集：贴吧 uid 是数字串，留字母/下划线/连字符以防将来带前缀 */
    private val SAFE_UID = Regex("[0-9A-Za-z_-]+")

    override suspend fun check(context: Context): SelfCheckOutcome {
        val uid = AccountUtil.getUid()
        if (uid.isNullOrBlank()) return skip("未登录——无账号维度的记录文件可判定")
        expect(SAFE_UID.matches(uid)) {
            "已登录但 uid 不能安全用作文件名（\"$uid\"）—— 记录会落到不区分账号的 " +
                "共享文件 agree_op_records.xml，切号后串号"
        }

        val spDir = File(context.filesDir.parentFile, "shared_prefs")
        val badNames = (spDir.listFiles() ?: emptyArray())
            .map { it.name }
            .filter { it.startsWith("agree_op_records_u_") && it.endsWith(".xml") }
            .filter { name ->
                val suffix = name.removePrefix("agree_op_records_u_").removeSuffix(".xml")
                !SAFE_UID.matches(suffix)
            }
        expect(badNames.isEmpty()) {
            "存在坏命名的账号记录文件（uid 取值链路上出现过空值/非法串）：$badNames"
        }
        return pass()
    }
}

/**
 * **关注吧落盘副本不变式**（2026-09-26 新增，守 §8.7 候选 5 的落盘链路）。
 *
 * 为什么必要：关注吧缓存此前是**纯内存**单例，冷启动/切号后要等首页慢路径那次
 * 54 请求的全量同步才有数据，期间 `isFollowed()` 按空表判（"只看关注吧"会被过滤成空）。
 * 落盘（`followed_forum` 表，迁移 40→41）之后由 `FollowedForumsCache.preload` 预载。
 *
 * 这条守两件事（都只读）：
 * 1. **表可读**——迁移没生效/列名写错会直接抛（真机上"用户升级即崩"的早期发现点）；
 * 2. **写穿透确实落库**——内存里有关注吧，表里却一行都没有，说明写穿透断了；
 *    此时冷启动会退回空表判定，正是本次要修的那个问题。
 */
object FollowedForumsPersistedCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "关注吧落盘副本（followed_forum）"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val uid = AccountUtil.getUid()
        if (uid.isNullOrBlank()) {
            return skip("未登录——关注吧是账号私有数据，没有副本可判定")
        }

        val rows = try {
            withTimeout(10_000) { DatabaseUtil.getFollowedForums(uid) }
        } catch (t: Throwable) {
            return fail("followed_forum 表不可读（迁移 40→41 未生效或列不匹配）—— ${t.message}")
        }

        val inMemory = FollowedForumsCache.getAllFollowedForums().size
        if (rows.isEmpty() && inMemory == 0) {
            return skip("该账号还没有关注吧副本（本次会话尚未同步过关注吧）")
        }
        expect(rows.isNotEmpty()) {
            "内存缓存有 $inMemory 个关注吧，但 followed_forum 里 uid=$uid 一行都没有 —— " +
                "写穿透没生效；冷启动会退回空表判定（本次要修的就是这个）"
        }
        // 落盘行的 uid 必须都是当前账号（漏 uid / 串号的直接表现）
        val foreign = rows.count { it.uid != uid }
        expect(foreign == 0) { "followed_forum 里混进了 $foreign 行非当前账号(uid=$uid)的数据" }
        return pass()
    }
}

/**
 * **赞踩记录已迁出 SharedPreferences**（2026-09-26 新增，守挂账第 3 条的 B 方案）。
 *
 * 为什么必要：带 uid 的按账号记录文件 `agree_op_records_u_<uid>.xml` 留在 `shared_prefs/` 里
 * **排不掉**——Android 备份规则只支持写死路径、不支持通配符，所以它们会被云备份 / 换机迁移
 * 带走，与规则里"赞踩记录不该离机"的本意正好相反。搬迁后记录落在 `files/oprecords/`
 * （该目录已在两份备份规则里**按目录整体排除**）。
 *
 * 这条守的是"搬迁真的生效、且旧文件真的清干净了"——只留墓碑是不够的，旧文件还带着 uid
 * 文件名，留着仍会被备份带走。
 *
 * 只读：不写任何文件。
 */
object OpRecordStorageRelocatedCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "赞踩记录已迁出 SharedPreferences"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val uid = AccountUtil.getUid()
        if (uid.isNullOrBlank()) {
            return skip("未登录——赞踩记录是账号私有数据，没有文件可判定")
        }

        val fileName = "agree_op_records_u_$uid"
        val legacy = File(File(context.filesDir.parentFile, "shared_prefs"), "$fileName.xml")
        val relocated = File(File(context.filesDir, "oprecords"), fileName)

        if (!relocated.isFile && !legacy.isFile) {
            return skip("该账号还没有赞踩记录落盘（本次会话尚未赞踩过，或记录已按空表收口）")
        }
        expect(!legacy.isFile) {
            "旧位置 shared_prefs/$fileName.xml 仍在——它带 uid 文件名，备份规则排不掉，" +
                "会被云备份/换机迁移带走（说明搬迁没把旧文件清掉）"
        }
        expect(relocated.isFile) {
            "赞踩记录既不在新位置 files/oprecords/ 也不在旧位置——记录丢了？"
        }
        return pass()
    }
}

/**
 * **按账号隔离不变式**（2026-09-26 新增，守六张表按账号隔离的落地，挂账 §三-8 的产品决策）。
 *
 * 为什么必要：`history`/`draft`/`block`/`topforum`/`searchhistory`/`searchposthistory`
 * 自本次起带 `owner_uid`，迁移 41→42 会把**既有数据回填成"当前登录账号"**。回填取错账号时，
 * 数据一条没丢，但用户看到的是"**历史/草稿/黑名单全没了**"——最难自查的一类故障。
 *
 * 这条断言的就是那个症状：**表里有数据，但当前账号一条都查不到** → 判失败。
 * （DAO 已按 `owner_uid` 过滤，所以"查不到"只可能是归属错了。）
 *
 * 只读：只发 SELECT。
 */
object OwnerUidIsolationCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "按账号隔离 · 六张表归属当前账号"

    /** 表名 → "当前账号查得到几条" 的取值器 */
    private suspend fun mineOf(table: String): Int = when (table) {
        "history" -> DatabaseUtil.getAllHistory().size
        "draft" -> DatabaseUtil.getAllDrafts().size
        "block" -> DatabaseUtil.getAllBlocks().size
        "topforum" -> DatabaseUtil.getTopForums().size
        "searchhistory" -> DatabaseUtil.getAllSearchHistories().size
        "searchposthistory" -> DatabaseUtil.getAllSearchPostHistories().size
        else -> error("unknown table $table")
    }

    override suspend fun check(context: Context): SelfCheckOutcome {
        val uid = AccountUtil.getUid()
        if (uid.isNullOrBlank()) {
            return skip("未登录——此刻读到的是'未登录桶'，归属无可判定")
        }

        val db = EntryPointAccessors
            .fromApplication(context, AppDatabaseEntryPoint::class.java)
            .appDatabase()
        val raw = db.openHelper.readableDatabase

        val orphaned = mutableListOf<String>()
        for (table in TABLES) {
            val total = withTimeout(10_000) {
                raw.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }
            }
            val mine = withTimeout(10_000) { mineOf(table) }
            if (total > 0 && mine == 0) orphaned += "$table(全表 $total 条，当前账号 0 条)"
        }

        expect(orphaned.isEmpty()) {
            "这些表里有数据但当前账号(uid=$uid)一条都查不到 → 迁移回填归属错了，" +
                "用户看到的就是\"历史/草稿/黑名单全丢\"：$orphaned"
        }

        // 反向也守一下：查出来的每一条都必须真的属于当前账号（防"过滤没接上、串了号"）
        val historyUids = withTimeout(10_000) { DatabaseUtil.getAllHistory() }
            .map { it.ownerUid }
            .distinct()
        expect(historyUids.all { it == uid }) {
            "历史里混进了非当前账号($uid)的行：$historyUids"
        }
        return pass()
    }

    private val TABLES = listOf(
        "history", "draft", "block", "topforum", "searchhistory", "searchposthistory",
    )
}

val defaultSelfCheckCases: List<SelfCheckCase> = listOf(
    // 纯逻辑
    E2DiffModelCase,
    ImageUrlCase,
    GzipRoundTripCase,
    // 数据红线
    DataFileInvariantCase,
    DataStoreKeyInvariantCase,
    SettingsSyncReadInvariantCase,
    RoomReadableCase,
    ProcessIdentityCase,
    AccountIsolationCase,
    FollowedForumsPersistedCase,
    OpRecordStorageRelocatedCase,
    OwnerUidIsolationCase,
    // 安全策略
    CleartextPolicyCase,
    // 网络只读
    ForumPageReadCase,
    ThreadPageReadCase,
    SearchReadCase,
    // 图片链路
    RemoteImageDownloadCase,
    ShareUriCase,
    SubPostImageChainCase,
    // UI 导航（残留检查必须排在本组最前：早于真正发起导航的用例）
    NavResidualCase,
    NavRouteTableCase,
    DeepLinkResolvableCase,
    NavAppFontSizeCase,
)

// ── 纯逻辑 ────────────────────────────────────────────────────────────────

/** E2 赞踩差分模型：显示值、回滚、权威采纳、赞轴专用差分。对应 README §7.1。 */
object E2DiffModelCase : SelfCheckCase {
    override val group = "纯逻辑"
    override val name = "E2 赞踩差分模型"

    override suspend fun check(context: Context): SelfCheckOutcome {
        // 显示偏移 = delta(my) - delta(server)
        expectEquals(
            OpRecord(my = MyAgreeOp.AGREE, server = MyAgreeOp.NONE).displayDelta(), 1L,
            "未确认的赞应 +1",
        )
        expectEquals(
            OpRecord(my = MyAgreeOp.DISAGREE, server = MyAgreeOp.NONE).displayDelta(), -1L,
            "未确认的踩应 -1",
        )
        expectEquals(
            OpRecord(my = MyAgreeOp.AGREE, server = MyAgreeOp.AGREE).displayDelta(), 0L,
            "已对齐后偏移应为 0",
        )

        // 失败回滚：回到服务端基准，且必须清在途（不清则此后每次 rebase 都跳过，永不自愈）
        val reverted = OpRecord(
            my = MyAgreeOp.AGREE,
            server = MyAgreeOp.NONE,
            inFlight = true,
        ).reverted()
        expectEquals(reverted.my, MyAgreeOp.NONE, "回滚后 my 应回到 server")
        expect(!reverted.inFlight) { "回滚必须清 inFlight（E2 不变式）" }

        // 权威采纳：服务端说"你已赞过" → my 与 server 一并对齐
        val adopted = OpRecord(my = MyAgreeOp.NONE, server = MyAgreeOp.NONE, inFlight = true)
            .adoptAuthoritative(MyAgreeOp.AGREE)
        expectEquals(adopted.my, MyAgreeOp.AGREE, "权威采纳后 my")
        expectEquals(adopted.server, MyAgreeOp.AGREE, "权威采纳后 server")
        expect(!adopted.inFlight) { "权威采纳是终结态，必须清 inFlight" }

        // 列表卡赞数只按赞轴走：在途的踩不得让赞数 -1
        expectEquals(
            OpRecord(my = MyAgreeOp.DISAGREE, server = MyAgreeOp.NONE).agreeCountDelta(), 0L,
            "踩轴变化不得影响列表卡赞数",
        )
        expectEquals(
            OpRecord(my = MyAgreeOp.AGREE, server = MyAgreeOp.NONE).agreeCountDelta(), 1L,
            "未确认的赞在列表卡应 +1",
        )
        return pass()
    }
}

/** 图片 URL：http 升 https、明文白名单例外、三级解析优先级。对应 README §7.2。 */
object ImageUrlCase : SelfCheckCase {
    override val group = "纯逻辑"
    override val name = "图片 URL 解析与升级"

    override suspend fun check(context: Context): SelfCheckOutcome {
        expectEquals(
            upgradeImageUrlToHttps("http://tiebapic.baidu.com/forum/pic/item/a.jpg"),
            "https://tiebapic.baidu.com/forum/pic/item/a.jpg",
            "http 图片应升级为 https（否则整屏纯黑）",
        )
        // 白名单域名证书不覆盖自身，升级会把它弄坏，必须原样返回
        expectEquals(
            upgradeImageUrlToHttps("http://tb.himg.baidu.com/a.jpg"),
            "http://tb.himg.baidu.com/a.jpg",
            "明文白名单域名不得升级",
        )
        expectEquals(upgradeImageUrlToHttps(""), "", "空串应原样返回")
        expectEquals(
            upgradeImageUrlToHttps("https://tiebapic.baidu.com/a.jpg"),
            "https://tiebapic.baidu.com/a.jpg",
            "已是 https 应原样返回",
        )

        // 展示：display → origin → url；下载：origin → display → url
        expectEquals(
            resolvePhotoViewDisplayUrl("https://d/a.jpg", "https://o/a.jpg", "https://p/a.jpg"),
            "https://d/a.jpg", "展示应优先 displayUrl",
        )
        expectEquals(
            resolvePhotoViewDisplayUrl(null, "https://o/a.jpg", "https://p/a.jpg"),
            "https://o/a.jpg", "展示无 display 时应取 origin",
        )
        // 有原图：下载/分享用原图保质量
        expectEquals(
            resolvePhotoViewDownloadUrl("https://d/a.jpg", "http://tiebapic.baidu.com/a.jpg", null),
            "https://tiebapic.baidu.com/a.jpg", "下载应优先 originUrl（并升级 https）",
        )
        // 原图缺失：回落内联 URL，避免下载按钮静默失效
        expectEquals(
            resolvePhotoViewDownloadUrl("https://d/a.jpg", "", "https://p/a.jpg"),
            "https://d/a.jpg", "下载无 origin 时应回落 display",
        )
        // 原图与内联都缺：回落图页大图
        expectEquals(
            resolvePhotoViewDownloadUrl(null, "  ", "https://p/a.jpg"),
            "https://p/a.jpg", "origin 与 display 都缺时回落 page url",
        )
        return pass()
    }
}

/** GZIP 压缩往返：上传分片前的压缩链路不能丢字节。 */
object GzipRoundTripCase : SelfCheckCase {
    override val group = "纯逻辑"
    override val name = "GZIP 压缩往返"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val raw = "{\"zid\":\"1234567890abcdef\",\"payload\":\"中文与 emoji \uD83D\uDE00 混排\"}"
        val zipped = raw.gzipCompress()
        expect(zipped.isNotEmpty()) { "压缩结果不应为空" }
        val unzipped = GZIPInputStream(ByteArrayInputStream(zipped)).readBytes()
            .toString(Charsets.UTF_8)
        expectEquals(unzipped, raw, "解压后应与原文一致")
        return pass()
    }
}

// ── 数据红线 ──────────────────────────────────────────────────────────────

/**
 * 数据落盘名不变式：DB 文件名 `tblite.db`、SP 名 `accountData`、DataStore 名
 * `app_preferences` 一字都不能动——改名 = 用户历史/账号/设置全丢（README 红线）。
 * 结构大改全程都要守住这条，所以放进自检，随时可点。
 */
object DataFileInvariantCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "数据落盘名不变式"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val db = context.getDatabasePath("tblite.db")
        expect(db.parentFile?.isDirectory == true) { "数据库目录不可用：${db.parent}" }
        if (!db.exists()) {
            // 全新安装还没建库不算失败，但要把结论说清楚，避免"假绿"
            return skip("数据库尚未创建（全新安装且未触发建库）——如已使用过应用则属异常")
        }
        expect(db.length() > 0) { "数据库文件为空：${db.absolutePath}" }

        val spDir = java.io.File(context.filesDir.parentFile, "shared_prefs")
        val accountDataExists = java.io.File(spDir, "accountData.xml").exists()
        if (!accountDataExists) {
            return skip("accountData.xml 尚不存在（全新安装）——如已登录过则属异常")
        }
        return pass()
    }
}

/**
 * `app_preferences` **键名不变式**（Phase 6.5 新增）。
 *
 * 为什么必要：6.5 把旧 `AppPreferencesUtils` 门面删了、设置层整体换实现。**只要有一个键名在
 * 迁移中被写错，用户那一项设置就会静默回到默认值**——不崩、不报错、logcat 也没有线索。
 * JVM 单测能锁住"代码里的 62 个键常量"（SettingsKeysAlignmentTest），但锁不住"设备上真正
 * 落盘的键"；这一条读真实 DataStore 兜底。
 *
 * 只读：不写任何键。
 */
object DataStoreKeyInvariantCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "设置键名不变式（app_preferences）"

    /** 非设置项：ClientUtils/AppClientIdStore 的设备标识（Phase 3a 起就共用这个 DataStore） */
    private val nonSettingKeys = setOf("client_id", "sample_id", "baidu_id", "active_timestamp")

    override suspend fun check(context: Context): SelfCheckOutcome {
        val settingsKeys = SettingsKeys::class.java.fields
            .filter { it.name.startsWith("KEY_") && it.type == String::class.java }
            .mapNotNull { it.get(null) as? String }
        expect(settingsKeys.size == 62) {
            "SettingsKeys.KEY_* 数量异常：${settingsKeys.size}（期望 62）"
        }

        val persisted = withTimeout(10_000) {
            context.dataStore.data.first().asMap().keys.map { it.name }
        }
        if (persisted.isEmpty()) return skip("app_preferences 尚无任何键（全新安装未写过设置）")

        val allowed = settingsKeys.toSet() + nonSettingKeys
        val unknown = persisted.filter { name ->
            // `<吧名>_sort_type`：按吧的排序偏好，动态键族（ForumPage 读写的唯一动态键）
            name !in allowed && !name.endsWith("_sort_type")
        }
        expect(unknown.isEmpty()) {
            "app_preferences 出现未知键：$unknown —— 键名被改名/新增（该项设置会静默回默认值）。" +
                "若确为新增设置项，请同步更新 SettingsKeys 与 tools/settings-baseline.json"
        }
        return pass()
    }
}

/**
 * 设置**同步读**不变式（Phase 6.5 新增）。
 *
 * 6.5 给 `Settings<T>` 加了同步 `value`（进程级缓存 + 首次访问阻塞预热 + `set` 乐观更新）。
 * 若缓存预热或取值有缺陷，典型症状是 `value` **恒定返回默认值**（用户设过的值被无视）——
 * 本项目 `App.getResources()` 在 `onCreate` 之前就读 `fontScale`，正是这条路径，出问题会
 * 表现为"启动瞬间字号/主题被重置"。
 *
 * 断言：① 访问器与工厂**同实例**（否则两套缓存，"写后立即可见"会失配）；
 *      ② 五种类型的代表项，`value` 必须等于"落盘值或缺省"；
 *      ③ `value` 与挂起读 `snapshot()` 同源。
 * 只读：不写任何键。
 */
object SettingsSyncReadInvariantCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "设置同步读与落盘一致"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val repo = settingsRepository(context)
        expect(context.appPreferences === repo) {
            "Context.appPreferences 与 settingsRepository() 不是同一实例 —— 两条路径各持一份同步缓存，" +
                "“写后立即可见”会失配"
        }
        val prefs = withTimeout(10_000) { context.dataStore.data.first() }

        expectEquals(
            repo.hideReply.value,
            prefs[booleanPreferencesKey(SettingsKeys.KEY_HIDE_REPLY)] ?: false,
            "hideReply 同步读",
        )
        expectEquals(
            repo.radius.value,
            prefs[intPreferencesKey(SettingsKeys.KEY_RADIUS)] ?: 8,
            "radius 同步读",
        )
        expectEquals(
            repo.fontScale.value,
            prefs[floatPreferencesKey(SettingsKeys.KEY_FONT_SCALE)] ?: 1.0f,
            "fontScale 同步读（App.getResources 早读的那一项）",
        )
        expectEquals(
            repo.userLikeLastRequestUnix.value,
            prefs[longPreferencesKey(SettingsKeys.KEY_USER_LIKE_LAST_REQUEST_UNIX)] ?: 0L,
            "userLikeLastRequestUnix 同步读",
        )
        // 可空字符串：落盘无键 = null（旧 string 代理把 null 存成"删键"）
        expectEquals(
            repo.littleTail.value,
            prefs[stringPreferencesKey(SettingsKeys.KEY_LITTLE_TAIL)],
            "littleTail 同步读",
        )
        expectEquals(
            repo.theme.value,
            prefs[stringPreferencesKey(SettingsKeys.KEY_THEME)] ?: SettingsKeys.THEME_DEFAULT,
            "theme 同步读",
        )

        // 同步读与挂起读必须同源：任一取默认值而另一取真实值即失败
        expectEquals(repo.fontScale.value, repo.fontScale.snapshot(), "fontScale：value 与 snapshot")
        expectEquals(repo.theme.value, repo.theme.snapshot(), "theme：value 与 snapshot")
        return pass()
    }
}

/**
 * **Room 真机只读可读**（Phase 6.5 新增）。
 *
 * JVM 侧 Room 跑在 Robolectric 上（19 例），但"真机上那份 `tblite.db` 能否被迁移后的 DAO 打开
 * 并读出来"是另一回事：schema 版本、迁移链、WAL、destroyed-open 只在真机路径暴露。
 *
 * 只做**读**查询（账号 / 历史首页 / 屏蔽），断言不抛异常；**不写任何表**。
 */
object RoomReadableCase : SelfCheckCase {
    override val group = "数据红线"
    override val name = "Room 真机只读可读"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val db = context.getDatabasePath("tblite.db")
        if (!db.exists()) return skip("tblite.db 尚未创建（全新安装且未触发建库）")

        val failure = runCatching {
            withTimeout(15_000) {
                DatabaseUtil.getAllAccounts()
                DatabaseUtil.getHistoryByType(HistoryUtil.TYPE_THREAD, pageSize = 1)
                DatabaseUtil.getAllBlocks()
            }
        }.exceptionOrNull()
        if (failure != null) return fail("Room 只读查询失败：${failure.shortMessage()}")
        return pass()
    }
}

// ── 安全策略 ──────────────────────────────────────────────────────────────

/**
 * 明文流量策略不变式（Phase 6.5 新增）。
 *
 * 红线：**禁明文 + 图片 URL 一律升 https**（不采纳 0ranko0P 的 `usesCleartextTraffic=true`）。
 * manifest 的 `android:usesCleartextTraffic="false"` 会反映到 `ApplicationInfo` 的
 * `FLAG_USES_CLEARTEXT_TRAFFIC` 位——这条把"哪天有人为了省事打开它"变成面板上的一条红。
 * 只读系统信息。
 */
object CleartextPolicyCase : SelfCheckCase {
    override val group = "安全策略"
    override val name = "明文流量策略未放开"

    override suspend fun check(context: Context): SelfCheckOutcome {
        val flags = context.applicationInfo.flags
        expect(flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC == 0) {
            "ApplicationInfo 带 FLAG_USES_CLEARTEXT_TRAFFIC —— 明文流量被放开，违反项目红线"
        }
        return pass()
    }
}
