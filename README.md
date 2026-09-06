# Tieba Lite · 个人版

<p align="center">
    <a href="https://github.com/zzc10086/TiebaLite">
        <img alt="Forked from" src="https://img.shields.io/badge/forked%20from-zzc10086%2FTiebaLite-blue">
    </a>
    <img alt="Version" src="https://img.shields.io/badge/version-4.0.0--personal.24-orange">
    <img alt="Tests" src="https://img.shields.io/badge/unit%20tests-129%20passing-brightgreen">
    <img alt="AI maintained" src="https://img.shields.io/badge/maintenance-AI%20assisted-informational">
</p>

> **本仓库的全部修改由一个（可能是数个）不太聪明（白嫖来的）的 AI 自动编写与推送，所有者什么也不懂。**
> AI 能力有限，拉出的大便给您造成的观感不适或任何损失，深表歉意。
> 实际成色如何，请直接审代码与提交历史——本页文字可信度只能用堪忧来形容。
>
> 本仓库是 [zzc10086/TiebaLite](https://github.com/zzc10086/TiebaLite) 的公开 fork
> （zzc10086 本身是 [HuanCheng65/TiebaLite](https://github.com/HuanCheng65/TiebaLite) 的活跃维护分支），
> 在 GPL-3.0 下继承上游全部许可与署名，完整上游历史就在本仓库提交记录中。

贴吧 Lite 是一个**非官方**的百度贴吧客户端。本仓库为个人自用版，不发布安装包。

**本软件及源码仅供学习交流使用，严禁用于商业用途。**

## 个人版改动一览

相对上游 `4.0-dev` 的全部改动按主题分为七个提交（在提交历史中可逐一审阅）：

| 提交 | 内容 |
|---|---|
| `feat:赞踩差分模型与点踩功能` | 以差分计数模型重写赞踩状态机（显示 = 服务端基准 + delta(my) − delta(server)），新增点踩（主帖/楼层/楼中楼），7 个列表页与帖子页共享进程级记录表，服务端回显播种 + 权威错误码自愈，客户端限流（3s/对象 + 10 次/分），点踩设置开关（`show_disagree_btn`），tbs 失效自愈（110001 时刷新凭据重试），吧内刷新连带刷新吧头（签到状态随下拉/FAB 刷新更新） |
| `feat:楼中楼图片显示(V22 客户端身份)` | pb page/floor 读接口以 22.x 客户端身份请求（服务端自 22.8.5.0 起才对楼中楼下发真实图片），帖子页内联缩略图 + 详情页大图 + 多图翻页，门控哨兵（log-only），楼中楼带图回复入口（服务端是否接受未验证） |
| `perf:首页双路加载与签到可靠性` | 首页并行拉取前 N 页关注吧（2700+ 吧首屏秒开），一键签到错误码分类、瞬态重试、随机抖动、tbs 自动刷新 |
| `fix:安全加固——WebView intent 注入缓解/凭据云备份排除/搜索高亮正则转义` | WebView `intent://` 组件注入缓解，账号凭据库排除出云备份/设备迁移，搜索高亮正则转义防崩溃 |
| `fix:稳定性与生命周期加固` | 广播注册/注销配对，裸协程异常兜底，流句柄治理，上传临时文件清理与超大图降采样，并发原语修正，删除应用内无接收者的死广播信号 |
| `build:签名口令外移/构建治理/README 重写/版本 personal.24(390124)` | 签名口令外移（env → 用户级文件，缺失即构建失败），wrapper 校验和，jvmTarget 固定，语言资源过滤，README 重写（AI 维护声明/fork 关系） |
| `fix:外部交叉审查修复` | 关注吧全量同步达上限/中断时标记截断并跳过缓存与列表整体替换（防部分数据静默丢吧），一键签到失败回调补主线程切换，WebView 实例离开页面即销毁，首页取消关注去除未登录 NPE，release 签名 fail-closed（缺配置直接失败，不再静默降级 debug 签名） |

### 为什么需要本地记录（差分模型的设计动机）

服务端对"我是否赞/踩过"的回映不可靠（实测确认）：`has_disagree` 是客户端私有字段刷新即重置，
`has_agree` 可能被回显成 1（疑似"有过操作"语义）。因此"我的态度"一律以本地记录为准，
服务端只提供计数基准。核心文件：`api/models/protos/AgreeOp.kt`（差分数学与结果三分类）、
`utils/OpRecordStore.kt`（持久层 + 进程级共享状态）。

## 测试

```bash
./gradlew.bat :app:testDebugUnitTest   # 129 个 JVM 用例，无需设备
./gradlew.bat :app:assembleRelease     # 完整发布构建（R8 + 签名 + lintVital）
```

覆盖：差分数学 / 状态机时序 / rebase 语义 / 并发原子性 / 列表页 reducer no-op /
V22 门控哨兵 / 限流器 / WebView 宿主安全 / 首页分页。关键修复均经过证伪验证
（临时回退修复 → 对应用例恰好失败 → 还原复验）。

赞踩与楼中楼图片的真机行为（限流提示、杀进程重进、图片门控）无法用 JVM 单测覆盖，
以实机使用为准。

## 构建环境

- JDK 17（`gradle.properties` 与 kotlin jvmTarget 已锁定）
- Android SDK platform-36 + build-tools 36.0.0
- 签名：`keystore.properties` 与口令文件**不入库**（见 .gitignore），口令解析顺序为
  环境变量 `TIEBA_KEYSTORE_PASSWORD`/`TIEBA_KEY_PASSWORD` → `~/.tieba-personal.properties`，
  全缺时构建直接失败（拒绝静默降级 debug 签名）

## 上游与同步

上游 [zzc10086/TiebaLite](https://github.com/zzc10086/TiebaLite) 持续活跃维护；
本 fork 定期合并上游提交（GitHub 页面 "Sync fork" 即可）。协议层文件（`ProtobufRequest.kt`
等）刻意保持与上游零差异以降低合并成本，个人改动集中在赞踩系统、UI 层与构建治理。
