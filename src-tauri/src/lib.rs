use std::collections::HashMap;
use std::sync::atomic::AtomicUsize;
use std::sync::Arc;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogKind};
use tokio::sync::RwLock;

mod commands;
mod engine;

use anyhow::Context;
use commands::*;
use engine::code_lease::CodeLeaseManager;
use engine::code_share::CodeRegistry;
use engine::diagnostics::Diagnostics;
use engine::discovery::start_discovery_service;
use engine::messenger::Messenger;
use engine::network::NetworkPolicy;
use engine::share_manager::ShareManager;
use engine::transport::TransportEngine;
use engine::web_share::WebShareServer;
use std::path::{Path, PathBuf};
use tokio::net::UdpSocket;

/// 便携式数据目录：优先 exe 同级的 `data`，其次 `data-dir.txt` 里写的绝对路径。
/// 聊天记录、日志、设备 ID 和 WebView2 缓存都落在这里，不再写 C 盘用户目录。
fn resolve_data_dir() -> PathBuf {
    let base = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."));
    if let Ok(text) = std::fs::read_to_string(base.join("data-dir.txt")) {
        let trimmed = text.trim();
        if !trimmed.is_empty() {
            return PathBuf::from(trimmed);
        }
    }
    base.join("data")
}

/// 旧版（原 ZTBeam）把数据写在 `%APPDATA%\com.ztbeam.lan`，这里做一次拷贝迁移（只读旧目录）。
fn migrate_legacy_data(data_dir: &Path) {
    if data_dir.join("messages.sqlite3").exists() {
        return;
    }
    let Some(legacy) = std::env::var_os("APPDATA")
        .map(|base| PathBuf::from(base).join("com.ztbeam.lan"))
        .filter(|path| path.is_dir())
    else {
        return;
    };
    for name in ["device-id", "messages.sqlite3", "exclude-virtual-adapters"] {
        let source = legacy.join(name);
        let target = data_dir.join(name);
        if source.is_file() && !target.exists() {
            let _ = std::fs::copy(&source, &target);
        }
    }
}

fn load_device_id(directory: &Path) -> anyhow::Result<String> {
    load_or_create_device_id(directory)
}

/// 把主窗口显示到前台（托盘左键或菜单“显示主界面”）。
pub fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// 创建托盘图标：左键恢复窗口，菜单提供“显示主界面 / 退出”。
fn build_tray(app: &tauri::AppHandle) {
    let Ok(show) = MenuItem::with_id(app, "show", "显示主界面", true, None::<&str>) else {
        return;
    };
    let Ok(quit) = MenuItem::with_id(app, "quit", "退出 ZTDrop", true, None::<&str>) else {
        return;
    };
    let Ok(separator) = PredefinedMenuItem::separator(app) else {
        return;
    };
    let Ok(menu) = Menu::with_items(app, &[&show, &separator, &quit]) else {
        return;
    };
    let mut builder = TrayIconBuilder::with_id("main-tray")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .tooltip("ZTDrop 局域网文件传输")
        .on_menu_event(|app, event| match event.id.as_ref() {
            "show" => show_main_window(app),
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    let _ = builder.build(app);
}

fn load_or_create_device_id(directory: &std::path::Path) -> anyhow::Result<String> {
    std::fs::create_dir_all(&directory)?;
    let path = directory.join("device-id");
    if path.exists() {
        let id = std::fs::read_to_string(&path)?;
        let id = id.trim().to_string();
        if id.len() == 32 && id.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Ok(id);
        }
        anyhow::bail!("设备 ID 文件无效: {}", path.display());
    }
    let id = format!("{:032x}", rand::random::<u128>());
    std::fs::write(path, &id)?;
    Ok(id)
}

#[cfg(test)]
mod identity_tests {
    use super::*;

    #[test]
    fn device_id_survives_restart() -> anyhow::Result<()> {
        let directory =
            std::env::temp_dir().join(format!("ztdrop-identity-{:032x}", rand::random::<u128>()));
        let first = load_or_create_device_id(&directory)?;
        let second = load_or_create_device_id(&directory)?;
        assert_eq!(first, second);
        assert_eq!(first.len(), 32);
        std::fs::remove_dir_all(directory)?;
        Ok(())
    }
}

/// 服务启动。端口被占用等异常会作为 `Err` 返回，由 `report_startup_failure` 明确提示，
/// 而不是让进程静默退出。
async fn start_services(handle: tauri::AppHandle, data_directory: PathBuf) -> anyhow::Result<()> {
    let share_registry: CodeRegistry = Arc::new(RwLock::new(HashMap::new()));
    let peers = Arc::new(RwLock::new(HashMap::new()));
    std::fs::create_dir_all(&data_directory)
        .with_context(|| format!("无法创建数据目录 {}", data_directory.display()))?;
    migrate_legacy_data(&data_directory);
    // 结构化日志同时落盘到 logs/ztdrop.log（规划 §53）。
    let diagnostics = Arc::new(Diagnostics::with_file(
        data_directory.join("logs").join("ztdrop.log"),
    ));
    let active_transfers = Arc::new(AtomicUsize::new(0));
    let device_id = load_device_id(&data_directory)?;
    let network = Arc::new(NetworkPolicy::load(&data_directory).context("无法读取网卡设置")?);
    let transport = Arc::new(
        TransportEngine::init(
            share_registry.clone(),
            device_id.clone(),
            handle.clone(),
            peers.clone(),
            diagnostics.clone(),
            active_transfers.clone(),
            network.clone(),
        )
        .await?,
    );
    let discovery_socket = Arc::new(
        UdpSocket::bind(("0.0.0.0", engine::discovery::DISCOVERY_PORT))
            .await
            .context("UDP 局域网发现启动失败（UDP 52110 可能已被占用）")?,
    );
    discovery_socket
        .set_broadcast(true)
        .context("无法启用局域网广播")?;
    let code_leases = Arc::new(CodeLeaseManager::new(
        discovery_socket.clone(),
        peers.clone(),
        share_registry.clone(),
        device_id.clone(),
        handle.clone(),
        diagnostics.clone(),
        network.clone(),
    ));

    let device_name = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "ZTDrop-Client".into());

    let messenger = Arc::new(
        Messenger::new(
            &data_directory,
            device_id,
            device_name.clone(),
            peers.clone(),
            discovery_socket.clone(),
            network.clone(),
            code_leases.clone(),
            handle.clone(),
            diagnostics.clone(),
        )
        .context("消息数据库初始化失败")?,
    );
    messenger
        .restore_resources()
        .await
        .context("文件消息恢复失败")?;
    tokio::spawn(messenger.clone().maintain());
    let web_shares = Arc::new(WebShareServer::default());
    web_shares.clone().start(network.clone()).await?;
    let share_manager = Arc::new(ShareManager::new(
        share_registry.clone(),
        code_leases.clone(),
        web_shares.clone(),
        diagnostics.clone(),
        handle.clone(),
    ));

    let local_ip = network
        .ipv4_addresses()
        .first()
        .map(ToString::to_string)
        .or_else(|| local_ip_address::local_ip().ok().map(|ip| ip.to_string()))
        .unwrap_or_else(|| "127.0.0.1".into());

    let listen_port = transport.listen_port;

    // 启动设备发现组播服务
    start_discovery_service(
        handle.clone(),
        transport.node_id(),
        device_name.clone(),
        listen_port,
        peers.clone(),
        share_registry.clone(),
        discovery_socket,
        code_leases.clone(),
        messenger.clone(),
        diagnostics.clone(),
        network.clone(),
    )
    .await?;
    tokio::spawn(code_leases.clone().maintain());
    tokio::spawn(share_manager.clone().maintain());

    diagnostics.record(
        "APP",
        format!(
            "服务已启动：协议 v{}，UDP {} / TCP {} / HTTP {}",
            engine::protocol::PROTOCOL_VERSION,
            engine::discovery::DISCOVERY_PORT,
            transport.listen_port,
            engine::web_share::WEB_PORT,
        ),
    );

    handle.manage(AppState {
        transport,
        peers,
        code_leases,
        messenger,
        web_shares,
        diagnostics,
        device_name,
        local_ip,
        network,
        active_transfers,
        share_manager,
        data_dir: data_directory.clone(),
    });
    Ok(())
}

/// 启动失败时给出可见原因：写错误文件 + 弹窗，用户关掉弹窗后再退出进程。
fn report_startup_failure(handle: &tauri::AppHandle, data_dir: &Path, error: anyhow::Error) {
    let message = format!(
        "ZTDrop 启动失败：{error:#}\n\n常见原因：\n1. 另一个 ZTDrop 仍在运行，占用了 UDP 52110 / TCP 52111 / HTTP 52112；\n2. Windows 防火墙或安全软件拦截了监听端口；\n3. 同一台电脑同时运行了两个本程序实例。\n\n退出旧版本后重新启动即可。"
    );
    let _ = std::fs::create_dir_all(data_dir);
    let _ = std::fs::write(data_dir.join("startup-error.txt"), &message);
    let dialog_handle = handle.clone();
    std::thread::spawn(move || {
        let _ = dialog_handle
            .dialog()
            .message(message)
            .title("ZTDrop 启动失败")
            .kind(MessageDialogKind::Error)
            .blocking_show();
        std::process::exit(1);
    });
}

pub fn run() {
    let data_dir = resolve_data_dir();
    let _ = std::fs::create_dir_all(&data_dir);
    // WebView2 的 localStorage/cache 也放到 data 目录，避免写 C 盘用户目录。
    std::env::set_var("WEBVIEW2_USER_DATA_FOLDER", data_dir.join("webview"));
    let setup_data_dir = data_dir.clone();

    tauri::Builder::default()
        .plugin(tauri_plugin_dialog::init())
        .setup(move |app| {
            let data_dir = setup_data_dir.clone();
            let handle = app.handle().clone();
            if let Err(error) =
                tauri::async_runtime::block_on(start_services(handle.clone(), data_dir.clone()))
            {
                report_startup_failure(&handle, &data_dir, error);
                return Ok(());
            }
            // 托盘：最小化与关闭按钮都会把窗口收进这里，托盘菜单负责重新显示和真正退出。
            build_tray(&handle);
            // 先起服务再建窗口，保证前端一挂载就能拿到 AppState。
            if let Err(error) =
                tauri::WebviewWindowBuilder::new(app, "main", tauri::WebviewUrl::default())
                    .title("ZTDrop")
                    .inner_size(900.0, 740.0)
                    .min_inner_size(700.0, 600.0)
                    .center()
                    .data_directory(data_dir.join("webview"))
                    .build()
            {
                report_startup_failure(
                    &handle,
                    &data_dir,
                    anyhow::anyhow!("创建主窗口失败：{error}"),
                );
                return Ok(());
            }
            let window_handle = handle.clone();
            if let Some(window) = app.get_webview_window("main") {
                window.on_window_event(move |event| match event {
                    // 关闭按钮：交给前端判断是否有任务，再决定“终止退出”还是“缩到托盘”。
                    tauri::WindowEvent::CloseRequested { api, .. } => {
                        api.prevent_close();
                        let _ = window_handle.emit("window-close-requested", ());
                    }
                    // 最小化：直接收进托盘，不在任务栏留图标。
                    tauri::WindowEvent::Resized(_) => {
                        if let Some(window) = window_handle.get_webview_window("main") {
                            if window.is_minimized().unwrap_or(false) {
                                let _ = window.hide();
                            }
                        }
                    }
                    _ => {}
                });
            }
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            get_local_node_info,
            get_nearby_devices,
            create_code_share,
            stop_code_share,
            get_network_diagnostics,
            get_active_sessions,
            get_share_files,
            stop_session,
            stop_all_sessions,
            export_diagnostics,
            delete_message,
            clear_conversation,
            hide_to_tray,
            quit_app,
            get_network_preference,
            set_network_preference,
            offer_to_device,
            resolve_code,
            start_download,
            target_exists,
            directory_exists,
            get_friends,
            get_messages,
            request_friend,
            answer_friend,
            remove_friend,
            send_text_message,
            send_file_message,
            set_message_download_status,
            create_web_share,
            get_web_share,
            stop_web_share
        ])
        .run(tauri::generate_context!())
        .expect("运行 Tauri 应用程序时发生错误");
}
