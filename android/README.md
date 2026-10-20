# ZTDrop Android 1.0.1

当前源码 versionName **1.0.1**、versionCode **34**。本轮 APK 为未签名包；原已签名版本及备份不改动。仓库不提交 APK、旧版源码快照或构建缓存。

## 功能
- 原生 Kotlin 四栏界面：传输、消息、设备、我的。仅新主题；Android 自适应闪电图标。
- 局域网设备发现、好友申请、聊天/ACK、置顶及备注；设备按钮按状态显示连接、等待或会话。
- 码连文件/文件夹分享与接收、单文件直链/二维码、聊天附件；保持 v2 / ZTB4、UDP52110、TCP52111、HTTP52112 与 PC 兼容。直链浏览器访问页与 PC 使用同版设计，文件名下方按大小显示 KB、MB 或 GB。
- 下载默认最多8线程，可选1/2/4/8，每段4MB。支持暂停/继续、停止并清理。优先直接写所选目录；不支持定位写入的提供器走暂存导出，成功后清理私有副本。旧发送端兼容串行续传。
- 默认下载目录通过 SAF 授权；图片使用系统选择器，无全盘存储权限。
- 我的→设备设置→后台保活，用户启用后申请通知与忽略电池优化，前台服务共享局域网引擎；设置/通知可关闭。真实锁屏存活与厂商省电策略以用户实测为准。
- 关于仅显示版本号，官网入口为 GitHub 项目主页。

## Windows 构建
```powershell
pwsh -NoProfile -File android/build-release.ps1
```
如需使用 Gradle 验证 Android 工程，直接运行仓库原有的 `gradlew.bat`。它只在本次进程内把 `TEMP/TMP` 指向 `android/build/gradle-temp`，并在未设置 `ANDROID_HOME` 时使用本机默认 SDK；不会更改系统环境变量，也不会替代上面的未签名发布脚本。
```powershell
android\gradlew.bat -p android :app:assembleDebug --no-daemon --console=plain
```
脚本使用本地 Android SDK、Kotlin/JDK 工具与经 SHA-1 校验的 ZXing 3.5.3 依赖；默认工具链路径仍兼容旧环境。当前环境缺少旧 `release/android-toolchain`，本轮使用如下参数构建 1.0.1/build34 未签名包：
```powershell
pwsh -NoProfile -File android/build-release.ps1 -BuildDirectory android/build/manual-1.0.1 -OutputDirectory release/1.0.1 -AndroidJar "$env:LOCALAPPDATA/Android/Sdk/platforms/android-37.0/android.jar" -ZxingJar android/build/toolchain/core-3.5.3.jar
```
`core-3.5.3.jar` 来自 Maven Central 的 `com.google.zxing:core:3.5.3`，脚本核对 SHA-1 `CA1349214A356CD7958651B2D5A0E1F3811A9C4B`。以 Gradle 工程配置中的版本号为准；输出目录独立，不覆盖旧正式包。
也可指定临时输出目录，不依赖任何历史发布目录：
```powershell
pwsh -NoProfile -File android/build-release.ps1 -BuildDirectory C:\Temp\ZTDrop-build -OutputDirectory C:\Temp\ZTDrop-output
```
后续版本继续使用用户原签名密钥。构建脚本不签名、不安装、不操作手机。

## 目录
- app/src/main：唯一最新业务源码、清单与资源。
- Gradle 配置、wrapper 与 build-release.ps1：构建入口。
- third_party：图标来源和 ZXing 许可说明，不作为旧版源码删除。
- 本地 Android SDK 与 `android/build/toolchain/`：当前构建使用的平台工具和 ZXing JAR；旧 `../release/android-toolchain` 仍受脚本默认参数兼容。
- ../docs/移动端.html：用户 UI 设计参考。

PC 源码与正式发布产物此次不变；PC 主题重做及后续协同发布计划保留。清理记录见 ../docs/maintenance/android-cleanup-20261008/VERIFICATION.txt。旧产物移入 Windows 回收站；工作目录不保留其副本。

## 聊天复制与图片预览（2026-10-08）
PC 消息支持选中文字/Ctrl+C和复制按钮；附件菜单可选择多张图片，逐张发送。图片仍使用兼容的 file 信令和原文件传输，发送方立即预览、接收方下载完成后预览，普通文件/文件夹不变。PC 支持 PNG/JPEG/GIF/WebP/BMP，预览上限10MB，超过上限或损坏时保留原文件卡片；点击图片查看大图，Esc关闭。安卓文字长按选中复制，发送/接收完成的图片缓存采样缩略图，点击大图可缩放；接收缓存使用实际SAF提交后的URI，不将本机路径或缩略图发送到信令。数据库迁移新增独立本机媒体索引，历史已下载但未记录路径的图片需重新接收。PC版本与安卓1.0.0/build33保持；新APK独立输出为未签名开发包，不覆盖正式签名备份，不操作手机。

文字消息：PC 右键气泡、Android 长按气泡可打开“复制 / 删除”菜单；图片和文件菜单提供“删除”。气泡不再显示常驻复制或删除按钮。删除只移除本机记录与预览索引，不撤回对端消息、不删除已下载文件。PC 仍支持选择文字后 Ctrl+C；安卓长按由应用菜单接管。
