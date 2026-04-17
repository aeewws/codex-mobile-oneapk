# Codex Mobile 环境与安装说明

这份文档是中文优先入口。英文版请看 [setup.en.md](./setup.en.md)。

Codex Mobile 当前面向 Android 9+ 的 `arm64-v8a` 设备，并通过构建时注入的 runtime 产物实现单 APK 本地运行。

## 你需要准备什么

- Android Studio 和 Android SDK
- 一台 Android 9+、`arm64-v8a` 的设备
- 一份可供打包进 APK 的 Codex arm64 runtime 目录或压缩包
- 手机上可用的浏览器，用于 OAuth 或设备码授权
- 如果你想启用保活和系统白名单增强，可选的 root 权限

## 这个仓库不包含什么

这个仓库公开的是 Android App 工程本身，不包含：

- 你的 App 私有登录态与 `auth.json`
- 你的本地 Codex 会话历史
- 你的代理配置
- 构建机本地的 `local.properties`
- 一整套完整导出的手机运行环境

## 高层启动流程

1. 克隆仓库并用 Android Studio 打开。
2. 同步 Gradle，并安装缺失的 Android SDK 组件。
3. 在构建前提供 runtime 产物路径，例如：

   ```bash
   export CODEX_MOBILE_RUNTIME_ARCHIVE=/absolute/path/to/@mmmbuto/codex-cli-termux/package
   ```

   或使用 Gradle 属性 `codexMobile.runtime.archive=/absolute/path/...`。

4. 用 Android Studio、`./gradlew assembleLegacyDebug` 或 `./gradlew assembleOssDebug` 构建并安装应用。
5. 首次启动后，App 会把内置 runtime 解压到应用私有目录，并初始化私有 `CODEX_HOME`。
6. 在“命令”页使用 `登录` 或 `设备码` 完成授权，再验证 backend 拉起、重连行为和线程加载是否正常。

## 运行时说明

- runtime 在构建时被打进 APK 资源中，首次启动时再解压到 App 私有目录
- 认证文件、配置、会话索引和 backend 日志都放在 App 私有 `CODEX_HOME`
- 当前 App 使用 App 内部管理的本地 websocket 端点与 backend 通信
- root 不是运行前提，但可用于 Doze 白名单、后台白名单和 standby bucket 加固
- 如果启用了设备码登录，需要先在 ChatGPT 安全设置里允许设备码授权

## 当前约束

- 首版只支持 `arm64-v8a`
- runtime 产物需要在构建时注入，仓库本身不直接提交运行时压缩包
- backend 行为会受到所打包的社区版 Codex runtime 版本影响
- 实际部署仍然包含电源管理、root 工具链、代理等设备侧决策
- App 界面与仓库说明目前都以中文优先

## 排查清单

- 确认设备 ABI 是 `arm64-v8a`
- 确认构建时已经提供 `CODEX_MOBILE_RUNTIME_ARCHIVE` 或 `codexMobile.runtime.archive`
- 确认首次启动后 runtime 已成功解压
- 确认测试保活能力时，App 能拿到 root
- 确认登录或设备码授权已经完成
- 确认本地 app-server 已成功监听并可被 App 连接
- 提 issue 之前尽量先准备截图或日志
