<div align="center">

# TiebaLite · 个人版

轻量第三方百度贴吧客户端 · Kotlin + Jetpack Compose + MVI

个人自用 fork（分支 `4.0-dev`）｜基于 [zzc10086/TiebaLite](https://github.com/zzc10086/TiebaLite)，其上游为 [HuanCheng65/TiebaLite](https://github.com/HuanCheng65/TiebaLite)

![Forked from](https://img.shields.io/badge/forked%20from-zzc10086%2FTiebaLite-blue)
![Unit tests & lint](https://github.com/juersss/TiebaLite/actions/workflows/test.yml/badge.svg)
![License](https://img.shields.io/badge/license-GPL--3.0-green)

</div>

> **本仓库的全部修改由一个（可能是数个）不太聪明的 AI 自动编写。所有者只做浏览、不发帖不回帖。**
> 成色如何请直接审代码与提交历史，本页文字可信度堪忧。

---

## 这个 fork 做了什么

相对上游 `2be35936`（本 fork 的分叉点）：**本 fork 53 个提交、784 个文件、+32,495 / −4,536 行**（`git diff --shortstat 2be35936 HEAD`；`git rev-list --count` 在该区间数出 54 笔，多出的一笔是上游自己的 `9701bfb6`）。已于 2026-09-30 把上游 `9701bfb6`（发帖换官方 protobuf）合并记入祖先并按 `core:network` 布局落位，故 fork 页不再显示 behind。下面按模块列改动。

## 一、工程骨架：单模块 → 多模块 + 约定插件

| 变化 | 说明 |
|---|---|
| 新增 `build-logic` | 独立 included build，提供 7 个约定插件：`tblite.android.application(.compose)` / `tblite.android.library(.compose)` / `tblite.android.test` / `tblite.hilt` / `tblite.jvm.library`；SDK 档位、Kotlin/Java 档位、Compose、Robolectric 单测口径、依赖锁定全部收在这里 |
| 拆出 4 个 core 模块 | `core:common`（纯 JVM 通用件）/ `core:network`（321 个 proto + api 树）/ `core:database`（Room 整层）/ `core:data`（DataStore + 设置） |
| 依赖方向 | **core 不得依赖 app**；core 之间只允许依赖 `core:common`；包名必须在本项目名下；`core` 不得声明 `:app` 项目依赖 |
| 机器化约束 | `ArchitectureRulesTest`（7 个用例）把上面四条 + api 树不得 import app 私有包 +「`painterResource` 不得指向自适应图标」（API 26+ 必崩）做成测试，另有两个防假绿用例，违规直接红 |
| 门禁 | `cleanTestDebugUnitTest testDebugUnitTest --no-build-cache` 多模块聚合，外加「测试结果 XML 必须晚于本次启动」的防假绿断言 |
| 签名外置 | `app/signing.gradle`（Groovy）+ 凭据解析顺序：环境变量 → `~/.tieba-personal.properties` → 仓库内 `keystore.properties`；口令全缺时 release **fail-closed**（不静默降级到 debug 签名） |
| CI | `test.yml`：单测 + `lintDebug`（Error 阻断；app 与三个 core 模块各挂一份 `lint-baseline.xml` 收存量）；`build.yml`：签名校验执行期化 + Actions 固定 SHA |

**数据落盘名是不变式**（改了会丢用户数据）：`tblite.db` / `accountData` / `app_preferences`。

## 二、会话、依赖注入与设置层

- **`api/session` Provider 接缝**：`CredentialProvider` / `DeviceInfoProvider` / `AppContextProvider` / `ResourceProvider` / `OAIDProvider` / `ClientConfigProvider` + `ClientIdStore`，由 `SessionProviders`（Hilt `@EntryPoint` + 懒解析）取用——原因是 Retrofit 接口方法的 Kotlin 默认参数没有实例、无法注入，而改成显式参数要动上百处调用点、且请求字段写错单测抓不到。
- **`SessionManager`**：账号态（`currentAccount` / `allAccounts` 为 `StateFlow`）与切号/退出唯一钩子点；`AccountUtil` 降为转发门面。
- **依赖注入收敛**：`TiebaApi.getInstance()` 静态调用 86 处 → 25 处（`app/src/main` 口径，其余在按用途划定的白名单里，逐条留痕）。
- **设置层单一实现**：`SettingsKeys` 是**键名唯一事实源**（62 键），`SettingsRepository` 是唯一实现（同步 `value` + JobQueue 串行化写）；上游时代的 `AppPreferencesUtils` 单例已删除，41 个文件里 122 处 `appPreferences.*` 调用点改为「访问器换类型」（`app/src/main` 口径，现 128 处读/写点）。组合期读设置走 `Settings.state` 订阅（`App.onCreate` 调一次 `warmUp()` 预热缓存），组合期不再有磁盘 I/O。

## 三、赞踩：改成「本地差分账本 + 服务端整体覆盖」

上游把态度直接挂在页面数据对象上、不持久化；本 fork 引入 `OpRecord(my, server, inFlight)` 持久化账本：

- 显示 = 服务端基准 + `delta(my) − delta(server)`；`inFlight` 区分「请求未返回」与「已 Ok 但基准未刷」，决定 rebase 时跳过还是强制对齐。
- 点亮判定 = **`hasAgree && agreeType == N`**（官方语义：`has_agree` 单独看是「已表态」，裸读会把「已踩」显示成「已赞」）。
- 记录落在 **`filesDir/oprecords/`**（按账号分文件；该目录已在两份备份规则里排除，不进云备份/换机迁移）。
- `opAgree` 必带 `forum_id` / `z_id` / `needSig=1`；主帖不发 `post_id`；客户端侧仍保留 3s/对象 + 10/min 的限流。

## 四、账号与数据（含 Room 迁移）

- **Room 版本 42**，迁移链 `39→40→41→42` 全部收在 `DatabaseModule.allMigrations(context)` 这个单一事实源里。
- **六张表按账号隔离**（迁移 41→42）：`history` / `topforum` / `block` / `draft` / `searchhistory` / `searchposthistory` 加 `owner_uid`；未登录归「空串桶」。**代价（有意接受）**：切到小号后看不到主号的草稿/历史/黑名单。
- **关注吧落盘**（迁移 40→41）：`followed_forum` 复合主键 `(uid, forum_id)`，写穿透（慢路径全量替换 / 快路径只覆盖传入项）+ 冷启动与切号 preload（只填空不覆盖）+ 跨账号守卫。
- **凭据与账号状态加固**：接口全量 HTTPS、明文凭据拦截、Cookie Secure、退出登录拒绝空账号。
- **多进程写者收口**：`:oksign` 是独立进程，而 `tblite.db` 与 DataStore 都不是多进程安全的 —— 账号刷新改为**只 update 不 insert**（杜绝「退出删号 + 在途刷新」复活账号）；客户端标识的联网同步与落盘只在主进程做。

## 五、网络与图片

- 伪装 **V22 身份**（`22.10.1.0`）：pb 接口只在 `_client_version ≥ 22.8.5.0` 时对楼中楼下发真实图片内容；赞踩/签到走 V12（协议常量跨版本零变化）；发帖/回复/投票走 `TIEBA_V12_POST`——2026-09-30 随上游 `9701bfb6` 由 12.35.1.0 升到 12.52.1.0（哨兵 `ClientVersionTest` 同步锁定）。
- **默认禁明文**：全局 `cleartextTrafficPermitted=false`，仅两个无凭据静态资源域按域名最小例外；图片地址、视频播放地址与视频封面地址在联网前**统一升 https**（修「整屏纯黑」与「视频点开黑屏」）；播放失败会提示原因并退回封面，不再静默黑屏。
- 大图 **URL 三级解析**（展示 = displayUrl→originUrl→url；下载/分享 = originUrl→displayUrl→url）、楼中楼多图翻页崩溃修复、选图上传链容错、表情/语音/视频生命周期与上传 MD5+尺寸校验。

## 六、界面与列表修复

首页双路加载、浏览进度锚点恢复、话题页置顶内容防御性建模、ThreadStore 代际门控与翻页终态、`LocalContext` 取资源存量清偿（`drawable→painterResource`）、空值/解析/生命周期守卫群、深链与导航、并发私有锁、一键签到可靠性与截断明示。

## 七、调试用自检面板（仅 debug）

`app/src/debug` 源码集内的 `SelfCheckActivity`：一键跑 23 条不变量（纯逻辑 / 数据红线 / 安全策略 / 网络只读 / 图片链路 / UI 导航）。
约束：**只读**，不触碰任何写操作；release 包不含该 Activity。

## 八、与上游一致、刻意不动的地方

- **应用标识与落盘文件名与上游完全一致**：包名 `com.huanchengfly.tieba.post.*`（搬进 `core` 的那几棵树也不改包名）、Room 库 `tblite.db`、账号 SharedPreferences `accountData`、DataStore `app_preferences`——这几个名字动一个就是用户数据事故。
- **自动化测试只覆盖只读路径与本地账本**：不含任何写操作（发帖 / 回复 / 楼中楼回复 / 带图回复 / 投票提交），涉及写操作的用例只有赞/踩。
- **依赖版本钉死在版本目录里**：XXPermissions 28.0、immersionbar 3.3.3（降级到 `com.gyf.immersionbar:3.0.0` 会在打开「设置 → 字体大小」时 `NoClassDefFoundError` 崩溃）；Jetifier 关闭。
- **跟进上游不照抄**：上游提交一律**按 `core:network` 布局重新落位**（不把旧路径的副本并进来）；上游对两个 JSON 接口 UA / `_client_version` 的版本号变更（12.35.1.0 / 12.41.7.1 → 12.52.1.0）**未跟随**——那两个面承载签到 / 收藏 / 关注 / 图片上传 / 搜索，按测试红线其写操作不可实测，且与本次发帖修复无功能关系；`app/build.gradle.kts` 里的 `kotlin { compilerOptions { jvmTarget } }` 也**未跟随**（本 fork 由约定插件统一锁定）。

## 九、构建

```bash
# JDK 17；Gradle wrapper 8.14.5 / AGP 8.13.2 / Kotlin 2.3.21
./gradlew.bat cleanTestDebugUnitTest testDebugUnitTest --no-build-cache   # 门禁
./gradlew.bat assembleDebug assembleRelease                                # 出包
```
签名凭据按 §一 的顺序解析（环境变量 → `~/.tieba-personal.properties` → 仓库内 `keystore.properties`）；仓库不含任何签名凭据（`keystore.properties` 与 `local.properties` 由 `.gitignore` 兜住、均未被跟踪），凭据缺失时 release 直接失败，不会静默降级到 debug 签名。

## 许可

GPL-3.0（随附 `LICENSE`）。上游：`zzc10086/TiebaLite`，其上游为 `HuanCheng65/TiebaLite`。
