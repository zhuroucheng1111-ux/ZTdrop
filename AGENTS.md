# ZTDrop Agent 开发文档

本文件是仓库唯一持续更新的开发说明。面向后续开发者和 Agent；用户说明见 [README.md](README.md)。历史规划、逐版快照与旧版测试文档在当前工作目录的 `backup/` 中本地归档，不随源码上传，也不作为现行需求或验收依据。修改功能时同步更新 README 中受影响的用法与限制，以及本文件中的开发状态。

## 开发环境与改动规则

- 主要环境：Windows 11、PowerShell 7+、UTF-8。路径可能包含空格与中文；不要假定 Bash 可用。
- 遵循当前 Tauri 2 + React 18 + TypeScript + Rust 结构。改动保持聚焦，不为整理文档引入新依赖或重构。
- 保留现有 CRLF/LF 风格。先确认用户修改和未提交状态，再编辑；不要覆盖用户工作。
- Python 辅助脚本如有需要，优先使用 `uv` 和 `pathlib`，调用外部进程优先参数列表。
- 当前目录可能没有 `.git` 元数据；不要据此假定文件可以随意删除。构建产物、测试包和用户数据应保留，只有确认目标路径和用途后才清理。

## 当前版本与验收状态

- 源码构建号为 **0.9.19**；`package.json`、`src-tauri/Cargo.toml`、`src-tauri/tauri.conf.json`、`src/App.tsx` 的 `APP_VERSION` 必须一致。
- 产品原名 ZTBeam，自 0.9.7 改为 ZTDrop。线上 TCP 魔数 `ZTB4` 是协议常量，不随产品名改变。
- 用户反馈 **0.9.7 基本完成双机测试**。本机编译、单元测试或端口监听只能证明本机状态，不等于 0.9.19 双机、多机或不同显示缩放验收。验收齐全前保持发布候选状态，不擅自升为 1.0。
- 0.9.17 修复聊天 ACK、多个来源中的同名文件、直链“传完再停”；0.9.18 改为按 Windows 硬件网卡属性正向选择物理局域网；0.9.19 将快投附件“＋”改为居中 SVG。上述改动仍需在当前同版包上做对应实机回归。
- 2026-09-28 文档整理时复核：`npm run build`、`cargo fmt --check` 和 Rust 30 项单元测试通过。先前的本机启动与端口检查已通过；这些结果不替代下列实机验收。

### 下一轮实机验收

1. 两台 Windows 11 x64 均运行 0.9.19，先退出旧实例。A 机开启 VPN/TUN，B 机不开启；两端保持“仅使用物理局域网”开启，同处一个实体 IPv4 子网。
2. 双向在线发现；诊断中的本机 IP 是物理以太网或 Wi-Fi 地址；双向加好友、文字已送达、文件及文件夹收发。
3. 双向四位码查询与下载、大文件断点续传、多个来源的同名文件、直链 URL 使用物理局域网 IP，另一设备浏览器下载。
4. 直链和客户端传输的“传完再停”：已开始的传输完成，新连接被拒绝；覆盖真实大文件与断网恢复。
5. 更换另一种 VPN/TUN 再测发现与传输；关闭网络开关时确认恢复系统路由，重新开启后确认物理局域网选路。
6. 在不同窗口尺寸、屏幕分辨率和 Windows 显示缩放下检查快投附件图标居中，展开菜单时按钮框不位移。
7. 无 WebView2 Runtime 设备验证离线安装包；有条件时补测三台及以上设备、浏览器大文件 Range。

出现单向发现时收集两端“设置 → 诊断”报告、物理网卡 IP/掩码、VPN 类型、对端 IP 和具体失败步骤。仅根据证据记录通过范围。

## 代码位置

下表的 `engine/` 均指 `src-tauri/src/engine/`。

| 位置 | 职责 |
| --- | --- |
| `src/App.tsx`、`src/index.css` | 主界面、码连、设置、诊断与样式；前端版本号在 App.tsx。 |
| `src/DirectPanel.tsx` | 快投、好友、聊天和文件消息。 |
| `src/WebSharePanel.tsx` | 直链、有效期、二维码和下载状态。 |
| `src-tauri/src/lib.rs`、`commands.rs` | 应用启动、托盘、数据目录和前后端命令。 |
| `engine/network.rs`、`discovery.rs` | 物理网卡选路、UDP 发现与信令。 |
| `engine/protocol.rs`、`code_lease.rs`、`code_share.rs` | v2 信令、四位码租约与分享状态。 |
| `engine/transport.rs`、`transfer.rs` | TCP 监听、清单、流式传输与续传。 |
| `engine/messenger.rs`、`web_share.rs` | SQLite 消息与好友、HTTP 浏览器分享。 |
| `engine/resource.rs`、`share_manager.rs`、`diagnostics.rs` | 共享会话生命周期、停止语义、结构化日志。 |
| `src-tauri/tauri.conf.json`、`tauri.offline-webview.conf.json` | 普通 NSIS 与 WebView2 离线安装配置。 |

## 网络与协议不变量

| 端口 | 用途 |
| --- | --- |
| UDP 52110 | 在线心跳、查码、租约、好友与聊天信令。 |
| TCP 52111 | 客户端文件与文件夹流式传输。 |
| TCP 52112 | HTTP 单文件浏览器下载及 Range。 |

- 仅面向可信私有局域网；没有公网发现、中继或自动端口映射。两端使用同版。信令是 UTF-8 JSON，必须含 `protocol_version: 2`、`message_id`、`type`、`sender_device_id`、`timestamp_ms`。旧 v1 和明文 `QUERY_CODE:` 已移除。
- 心跳每 3 秒，9 秒无心跳判离线。四位码有效 15 分钟；租约心跳 5 秒、远端超时 20 秒、抢码仲裁窗口 350 毫秒。UDP 协调在丢包或网络分区时只能尽力而为。
- TCP 握手为 `ZTB4` + 32 字节 ASCII 十六进制 token。清单上限 10 万文件、10 万子目录、32 MB，数据按 256 KB 块流式传输。接收临时文件使用 `.ztdrop-*.part`；续传检查大小与修改时间，不计算内容哈希。
- 立即停止会撤销令牌并中断当前传输。“传完再停”立刻拒绝新连接，允许已开始的连接完成。修改这两种语义时，三种分享模式都要回归。
- 直链只允许单个文件；`GET /s/<token>` 为下载页，`GET /download/<token>` 为数据流，支持 HTTP Range。浏览器分享可设 15 分钟、1 小时、8 小时、24 小时或手动结束。TCP 与 HTTP 入站并发上限各为 8。
- TCP 和 HTTP 内容是明文；不能把四位码或直链宣传为适合不可信网络的安全分享。文件夹逐文件完成，不保证整体原子性。

### 物理局域网设置

设置中的“仅使用物理局域网”默认开启。Windows 侧从硬件属性识别已连接的以太网或 Wi-Fi，再选择局域网 IPv4/前缀并把相关 UDP/TCP 流量绑定到匹配接口；不要退回按 VPN 品牌或网卡名称逐项排除。默认只允许同一 IPv4 子网。关闭开关使用系统路由，可能经过 VPN。持久化文件仍叫 `exclude-virtual-adapters`，这是兼容旧设置的历史文件名；改动功能时一并核对开关行为和用户文案。

## 本地数据与发布产物

- 默认数据目录是 `<exe 同级>\data\`；若 exe 同级 `data-dir.txt` 有非空路径，则使用该路径。数据含 `device-id`、`messages.sqlite3`、`exclude-virtual-adapters`、`logs\ztdrop.log`、`webview\`。旧 `%APPDATA%\com.ztbeam.lan` 的设备 ID、消息和设置仅做一次复制迁移，旧目录不自动删除。
- 日志是 JSON Lines，单文件约 2 MB 轮转、保留 5 份。启动端口冲突等错误写入 `startup-error.txt` 并弹窗。
- 程序安装在 C 盘时，默认数据也在 C 盘；构建工具和系统组件另有自身缓存。不要在文档中笼统承诺“完全不写 C 盘”。
- 0.9.19 之前的测试 ZIP 已清理；本地发布产物统一放在 `release/`。安装包、离线包、免安装压缩包、提取出的测试包、`release/`、`backup/` 和构建缓存都不进入源码仓库，由 `.gitignore` 排除。不要改动已发包内容或散列，除非明确制作新版本。
- 当前可交付的 0.9.19 本地产物在 `release/` 下：`ZTDrop_0.9.19_x64-setup.exe`（普通 NSIS 安装包）、`ZTDrop_0.9.19_x64-offline-setup.exe`（内置 WebView2 离线运行库）和 `ZTDrop-V0.9.19-portable-Win11-x64.rar`（免安装版，内含单个 `ZTDrop.exe`）。三者均未签名。
- 上述三个产物的 SHA-256：`45F9562E9D75EF509452A5FD90885367BED6EBCE1B871C459E1EEA3DC05AE50B`（普通安装包）、`7F7AA4FA2820B000A90E9E3EC0A63391D408922383293AD7E8F3BCB389F58C3F`（离线安装包）、`89A470886717FCCEBAF6632E8A57CDBCF1AAB415F1EDD905C48BA300BD9E307B`（免安装压缩包）。

## 验证与打包

```powershell
npm ci
npm run build
cargo fmt --manifest-path src-tauri/Cargo.toml --check
cargo test --lib --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

最后一条产生普通 NSIS 安装包。要制作 WebView2 离线安装包，先将普通安装包复制到**独立的目标路径**，再运行：

```powershell
npm run tauri build -- --config src-tauri/tauri.offline-webview.conf.json
```

两种构建会写入同一默认 NSIS 输出文件名，必须先保存普通包，避免被离线包覆盖。发布前核对版本号、安装包与免安装版可启动、三端口状态、产物 SHA-256，以及双机实测记录；测试不能用本机单元测试替代。当前仓库还没有 `LICENSE`，公开授权方式需由项目所有者确定。
