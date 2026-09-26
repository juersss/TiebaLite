package com.huanchengfly.tieba.post.utils

import android.content.Context
import com.huanchengfly.tieba.post.core.network.model.protos.MyAgreeOp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 赞踩记录**文件后端**测试（2026-09-26，配合"从 SharedPreferences 搬到 files/oprecords/"）。
 *
 * 这次搬迁的目的只有一个：让带 uid 的按账号记录文件**能被备份规则排除掉**。
 * SharedPreferences 目录是平铺的、规则不支持通配符，所以带 uid 的文件排不掉；
 * `files/` 下目录可以整体排除。因此这里必须守住三件事：
 *
 * 1. **搬迁不丢数据**：SharedPreferences 时代的记录要完整出现在文件里；
 * 2. **旧 prefs 必须清空**：只留墓碑是不够的——旧文件还带着 uid 文件名，留着仍会被备份带走，
 *    等于没搬（这条是本次改动的全部意义所在）；
 * 3. **文件确实落在 `files/oprecords/` 下**：路径写错（比如写回 shared_prefs）同样会让目的落空。
 *
 * 另外覆盖文件格式本身：行式 `key=value`、坏行跳过、值里带 `=` 时按**第一个**等号切分。
 *
 * 用 Robolectric 是因为需要真实 `filesDir`；`@Config(application=android.app.Application::class)`
 * 同其余 Room/prefs 测试——绕开清单里 Hilt App 的初始化链（Application 未就绪时抛 NPE）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OpRecordFileBackendTest {

    // app 模块测试类路径上没有 androidx.test.core，用 Robolectric 自带的取 Application
    private val context: Context = RuntimeEnvironment.getApplication()

    /** 按账号文件名（与 [accountPrefsName] 同构，测试里写死以免把被测逻辑当参照物） */
    private val uidFile = "agree_op_records_u_1001"

    private fun recordsFile(name: String) = File(File(context.filesDir, OP_RECORDS_DIR), name)

    @Test
    fun putAllThenReadBack_roundTrips() {
        val st = FileBackend(context, uidFile)

        st.putAll(
            mapOf(
                "my_3_42" to MyAgreeOp.AGREE.name,
                "srv_3_42" to MyAgreeOp.NONE.name,
                "inf_3_42" to "1",
            )
        )

        assertEquals(MyAgreeOp.AGREE.name, st.get("my_3_42"))
        assertEquals("1", st.get("inf_3_42"))
        assertNull("不存在的键必须返回 null", st.get("my_9_999"))
        assertEquals(3, st.all().size)
    }

    @Test
    fun dataSurvivesNewInstance() {
        FileBackend(context, uidFile).putAll(mapOf("my_3_42" to MyAgreeOp.DISAGREE.name))

        // 新实例 = 模拟进程重启后重新读盘
        val reopened = FileBackend(context, uidFile)

        assertEquals(
            "重启后必须能从文件里读回来（搬迁后持久性不能退化）",
            MyAgreeOp.DISAGREE.name,
            reopened.get("my_3_42"),
        )
    }

    @Test
    fun migratesFromSharedPreferencesAndClearsTheOldFile() {
        // 造出"SharedPreferences 时代"的按账号落盘
        context.getSharedPreferences(uidFile, Context.MODE_PRIVATE).edit()
            .putString("my_3_42", MyAgreeOp.AGREE.name)
            .putString("srv_3_42", MyAgreeOp.AGREE.name)
            .commit()

        val st = FileBackend(context, uidFile)

        assertEquals("记录必须完整搬到文件里", MyAgreeOp.AGREE.name, st.get("my_3_42"))
        assertEquals("srv_ 也要一起搬", MyAgreeOp.AGREE.name, st.get("srv_3_42"))
        assertTrue(
            "文件必须落在 files/oprecords/ 下（否则备份规则排的是别的目录，等于没搬）",
            recordsFile(uidFile).isFile,
        )
        assertFalse(
            "SharedPreferences 时代的旧文件必须**被删除**（只清空会留下一个空 .xml，" +
                "它仍带着 uid 文件名、仍会被云备份带走）",
            File(File(context.filesDir.parentFile, "shared_prefs"), "$uidFile.xml").exists(),
        )
    }

    @Test
    fun migrationIsIdempotentAndDoesNotOverwriteExistingFileData() {
        FileBackend(context, uidFile).putAll(mapOf("my_3_42" to MyAgreeOp.DISAGREE.name))
        // 旧 prefs 里"残留"一条别的记录（正常流程会被清空；这里模拟迁移后才出现的脏数据）
        context.getSharedPreferences(uidFile, Context.MODE_PRIVATE).edit()
            .putString("my_9_999", MyAgreeOp.AGREE.name)
            .commit()

        val reopened = FileBackend(context, uidFile)

        assertEquals("文件已有内容时不得被 prefs 覆盖", MyAgreeOp.DISAGREE.name, reopened.get("my_3_42"))
        assertNull("文件已有内容时不再执行搬迁", reopened.get("my_9_999"))
        assertFalse(
            "没有执行搬迁就不该去清旧 prefs",
            context.getSharedPreferences(uidFile, Context.MODE_PRIVATE).all.isEmpty(),
        )
    }

    @Test
    fun skipsMalformedLinesAndKeepsFirstEqualsSplit() {
        val file = recordsFile(uidFile)
        file.parentFile?.mkdirs()
        file.writeText(
            """
            my_3_42=AGREE
            这一行没有等号
            =值但没键
            my_3_43=DIS=AGREE
            """.trimIndent(),
        )

        val all = FileBackend(context, uidFile).all()

        assertEquals("坏行必须跳过而不是整份作废", 2, all.size)
        assertEquals(
            "值里带 = 时按第一个等号切分",
            "DIS=AGREE",
            all["my_3_43"],
        )
    }

    @Test
    fun emptyOrMissingFileReadsAsEmpty() {
        val st = FileBackend(context, "agree_op_records_u_1002")

        assertTrue("文件不存在时必须当空表处理（不能抛）", st.all().isEmpty())
        assertNull(st.get("my_1_1"))
    }
}
