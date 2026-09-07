# Tieba Lite · 个人版

<p align="center">
    <a href="https://github.com/zzc10086/TiebaLite">
        <img alt="Forked from" src="https://img.shields.io/badge/forked%20from-zzc10086%2FTiebaLite-blue">
    </a>
    <a href="https://github.com/juersss/TiebaLite/actions/workflows/test.yml">
        <img alt="Unit tests & lint" src="https://github.com/juersss/TiebaLite/actions/workflows/test.yml/badge.svg">
    </a>
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

## 改了什么，去哪里看

本页刻意**不维护改动清单**——手写清单必然滞后于提交历史。相对上游 `4.0-dev` 的全部
改动以主题式提交线性排列（`feat:`/`fix:`/`perf:`/`build:`/`refactor:` 前缀 + 中文摘要），
逐一审阅提交历史即是最准确的账本；当前版本号以 `application.properties` 为准，
测试与静态分析的实时状态以顶部的 CI 徽章为准。

改动集中在四处，各自的现状与取舍都写在对应代码的头注释里：

| 主题 | 权威出处 |
|---|---|
| 赞踩差分模型与点踩 | `api/models/protos/AgreeOp.kt`、`utils/OpRecordStore.kt` |
| 楼中楼图片（V22 门控） | `api/V22ImageGateSentinel.kt` |
| 吧内浏览进度保持 | `ui/page/forum/threadlist/ForumBrowseCache.kt` |
| 凭据传输安全 | `api/retrofit/RetrofitTiebaApi.kt`、`CleartextCredentialGuardInterceptor.kt` |

### 为什么需要本地记录（差分模型的设计动机）

服务端对"我是否赞/踩过"的回映不可靠（实测确认）：`has_disagree` 是客户端私有字段刷新即重置，
`has_agree` 可能被回显成 1（疑似"有过操作"语义）。因此"我的态度"一律以本地记录为准，
服务端只提供计数基准：`显示计数 = 服务端基准 + delta(my) − delta(server)`。

## 测试与构建

```bash
./gradlew.bat :app:testDebugUnitTest   # JVM 单测，无需设备（数量与结果看 CI）
./gradlew.bat :app:lintDebug           # 静态分析；新增 Error 阻断，存量挂账于 app/lint-baseline.xml
./gradlew.bat :app:assembleRelease     # 发布构建（R8 + 签名；lint 把关在全量 lintDebug/CI）
```

- JDK 17 + Android SDK platform-36 / build-tools 36.0.0（`gradle.properties` 与 jvmTarget 已锁定）；
- 依赖全配置锁定（`app/gradle.lockfile`），升级依赖时用 `--write-locks` 重算锁文件；
- 签名：口令不入库，解析顺序与 fail-closed 语义详见 `app/build.gradle.kts` 头部注释；
- 纯克隆（无任何签名凭据）可正常 Debug 构建、跑单测、IDE 同步；
- 赞踩、楼中楼图片、选图的真机行为无法用 JVM 单测覆盖，以实机使用为准。

## 上游与同步

上游 [zzc10086/TiebaLite](https://github.com/zzc10086/TiebaLite) 持续活跃维护；
本 fork 定期合并上游提交（GitHub 页面 "Sync fork" 即可）。pb 编解码核心 `ProtobufRequest.kt`
刻意保持与上游零差异以降低合并成本；赞踩系统在此基础上**新增**了 `Agree.proto` 与
`models/protos/AgreeOp.kt`（差分模型），并**修改了** `api/` 传输层若干文件（`RetrofitTiebaApi.kt`
凭据接口 HTTPS 迁移 / `CleartextCredentialGuardInterceptor.kt` 明文凭据拦截 / 部分接口与模型）。
同步这些目录时以本 fork 版本为准，勿盲目 `git checkout --theirs`。
