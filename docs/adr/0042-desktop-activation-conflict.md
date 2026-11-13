# 0042. Shizuku 激活前检查其他版本的桌面占用

- 状态：采用
- 日期：2026-11-13
- 来源：用户反馈共存版 Shizuku 激活时退出；CPH2645 / Android 16 现场日志。

## 证据

共存版在 adoptShellPermissionIdentity 阶段报 SecurityException：
`Shell can delegate permissions only to one instrumentation at a time`。
系统 Active instrumentation 同时显示原版 `com.yegorkapomidorka.headswap` 仍保持激活。
此时重启共存版的激活路径必然失败，即使安装包名、provider、Shizuku 授权都互相独立。

## 决定

受限 Shizuku UserService 在记录激活尝试和启动 instrumentation 前，读取系统活动 instrumentation
的组件头部。遇到其他包的未结束实例时直接显示本地化提示。dispatch 前再次检查，缩小检查后的竞态。
只把组件头部交给解析器，不读取或返回 instrumentation 参数、token 或任意 shell 命令。
允许同包重新激活；不自动停止其他应用，也不尝试撤销别人的权限委派。

激活完成或失败后的重新打开使用实际包名与固定源码 Activity 类名组成的显式组件。
此前按包过滤的 MAIN/LAUNCHER 命令在 Android 17 模拟器中曾返回无法解析，导致退出后未返回应用。

## 限制

共存安装不代表可以同时激活两个版本。切换时需要用户在系统应用设置中强行停止原先激活的版本。
系统 dumpsys 格式不是稳定 API，解析不到时仍由现有 instrumentation 处理系统拒绝；检查之后仍可能
有其他应用开始激活，因此最终成功标准继续是 DelegatedDesktopAccess。

## 验证

用户在系统设置中强行停止原版后，已确认 test2 可以通过 Shizuku 激活。
test3 在 Android 17 模拟器中：另一个 smoke 包保持激活时显示中文占用提示，当前 PID 保持不变，
不启动自己的 instrumentation；停止 smoke 后再次点击即可得到 READY，并连接 UID 2000 的 VDM 宿主，
随后自动回到 MainActivity。解析表达式同时在 Android 的 ICU 正则实现上运行验证，明确转义结束花括号。
1208 项单元测试、签名 release 构建及 lintVitalRelease 通过；未进行本修复包的实际车机连接测试。
