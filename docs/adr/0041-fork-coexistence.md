# 0041. 中文 Fork 使用独立包名与青蓝色图标

- 状态：采用
- 日期：2026-11-13
- 来源：用户要求“改个包名和图标配色，做共存”；Android 17 模拟器安装官方 4.2.0 与 Fork 验证。

## 背景

旧版 Fork 与上游使用同一包名、不同签名，无法同时安装。仅更改包名后的第一次安装仍报
`INSTALL_FAILED_DUPLICATE_PERMISSION`：`android.car.permission.TEMPLATE_RENDERER` 已由上游拥有。

## 决定

- 安装包名改为 `io.github.tntfish.headswap`，名称“Headswap 中文版”，图标使用青蓝背景与白色/浅青图形。
- 保留源码 namespace 与类名 `com.yegorkapomidorka.headswap`，避免无关的大规模重命名。
  运行时包名用于激活目标、Shizuku 服务组件、provider authority 与桌面 task affinity。
- 继续使用 ADR 0039 的 Fork 签名与更新来源。更新器原有包名检查可拒绝旧包名 APK；基础版本
  相同的旧发布不会被识别为升级。测试版本手动安装。
- Fork 只申请、不声明 `TEMPLATE_RENDERER`。由已安装的上游或系统提供权限声明，避免签名冲突。
- shell 激活日志、VDM 日志与激活锁使用 `headswap-zh-` 前缀，避免清理或覆盖原版文件。

## 影响

原版与 Fork 各自保存设置、激活状态与 Shizuku 授权。旧 Fork 的数据不会自动迁移。
USB 选择器可按名称选择版本；同一时刻只应运行一个版本连接车机，它们共享 CarLife/AA 端口。
系统主题图标由启动器着色，常规图标保留独立的青蓝配色。

模拟器实测两个安装顺序均可共存。Fork 先安装时，后安装上游不会立刻为 Fork 补发模板权限；
再覆盖安装一次 Fork 后 `TEMPLATE_RENDERER: granted=true`，设置可保留。

没有权限声明方时，依赖 `TEMPLATE_RENDERER` 的第三方导航可被宿主校验拒绝，沿用现有窗口回退；
不能为保留该功能重复声明权限，否则无法满足共存要求。Android Auto 模式不使用此权限。
本决定替代 ADR 0022 中“由本应用声明权限”的部分，以及 ADR 0039 中新版本必须卸载原版的说明。
