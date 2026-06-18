// onno desktop shell.
//
// Generic across every onno application: it boots the bundled JVM (a jlinked
// runtime + the app's boot jar, both shipped as Tauri resources), waits for the
// embedded server to report ready, then asks the server for its window manifest
// and draws the window around the live UI. No app-specific code lives here —
// title, geometry and tray all come from the server's `/api/desktop/manifest`,
// which is built from the application's typed `DesktopApp` bean.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::process::{Child, Command};
use std::sync::Mutex;
use std::time::{Duration, Instant};

use serde::Deserialize;
use tauri::{LogicalSize, Manager, PhysicalPosition, RunEvent};

/// Handle to the embedded server so we can terminate it on exit.
struct ServerProcess(Mutex<Option<Child>>);

#[derive(Deserialize)]
struct Manifest {
    title: String,
    window: WindowCfg,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct WindowCfg {
    width: u32,
    height: u32,
    min_width: u32,
    min_height: u32,
    resizable: bool,
    center: bool,
    maximized: bool,
}

/// Ask the OS for a free TCP port so multiple onno apps never collide.
fn free_port() -> u16 {
    std::net::TcpListener::bind("127.0.0.1:0")
        .and_then(|l| l.local_addr())
        .map(|a| a.port())
        .unwrap_or(8421)
}

fn main() {
    tauri::Builder::default()
        .manage(ServerProcess(Mutex::new(None)))
        .setup(|app| {
            let handle = app.handle().clone();
            let resource_dir = app.path().resource_dir()?;
            let data_dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&data_dir).ok();

            let port = free_port();

            // Bundled JVM: a jlinked runtime and the Spring Boot jar, both staged
            // by the `su.onno.desktop` Gradle task and declared as resources.
            let java = resource_dir.join("runtime").join(if cfg!(windows) {
                "bin/java.exe"
            } else {
                "bin/java"
            });
            let jar = resource_dir.join("app").join("onno.jar");

            let child = Command::new(&java)
                .arg("-jar")
                .arg(&jar)
                .arg(format!("--server.port={}", port))
                .arg(format!(
                    "--onno.desktop.home={}",
                    data_dir.to_string_lossy()
                ))
                .spawn()
                .expect("failed to launch embedded onno server");

            app.state::<ServerProcess>().0.lock().unwrap().replace(child);

            // Drive readiness + window off the UI thread; the window starts on the
            // bundled splash and swaps to the live UI once the server answers.
            std::thread::spawn(move || {
                let base = format!("http://127.0.0.1:{}", port);
                wait_ready(&base);
                if let Ok(manifest) = fetch_manifest(&base) {
                    if let Some(win) = handle.get_webview_window("main") {
                        let _ = win.set_title(&manifest.title);
                        let _ = win.set_size(LogicalSize::new(
                            manifest.window.width as f64,
                            manifest.window.height as f64,
                        ));
                        if manifest.window.min_width > 0 || manifest.window.min_height > 0 {
                            let _ = win.set_min_size(Some(LogicalSize::new(
                                manifest.window.min_width as f64,
                                manifest.window.min_height as f64,
                            )));
                        }
                        let _ = win.set_resizable(manifest.window.resizable);
                        // Position last, after the splash→app resize. We can't use
                        // win.center(): right after a resize it reads the *stale*
                        // (splash) size and centers that, leaving the larger window
                        // shoved to the right. Instead compute the centered top-left
                        // from the active monitor and the known target size, which is
                        // correct regardless of when the async resize lands.
                        if manifest.window.maximized {
                            let _ = win.maximize();
                        } else if manifest.window.center {
                            center_on_monitor(&win, manifest.window.width, manifest.window.height);
                        }
                        if let Ok(url) = base.parse() {
                            let _ = win.navigate(url);
                        }
                    }
                }
            });

            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building the onno desktop shell")
        .run(|app, event| {
            if let RunEvent::Exit | RunEvent::ExitRequested { .. } = event {
                if let Some(mut child) =
                    app.state::<ServerProcess>().0.lock().unwrap().take()
                {
                    let _ = child.kill();
                }
            }
        });
}

/// Center the window on its current monitor using the *target* logical size rather
/// than the window's current (possibly mid-resize) size, so it lands centered even
/// though `set_size` is applied asynchronously. Falls back to `center()` if the
/// monitor can't be resolved.
fn center_on_monitor(win: &tauri::WebviewWindow, width: u32, height: u32) {
    match win.current_monitor() {
        Ok(Some(monitor)) => {
            let scale = monitor.scale_factor();
            let msize = monitor.size(); // physical pixels
            let mpos = monitor.position();
            let win_w = (width as f64 * scale).round() as i32;
            let win_h = (height as f64 * scale).round() as i32;
            let x = mpos.x + ((msize.width as i32 - win_w) / 2).max(0);
            let y = mpos.y + ((msize.height as i32 - win_h) / 2).max(0);
            let _ = win.set_position(PhysicalPosition::new(x, y));
        }
        _ => {
            let _ = win.center();
        }
    }
}

/// Poll `/api/desktop/ready` until the embedded server answers (or we give up).
fn wait_ready(base: &str) {
    let url = format!("{}/api/desktop/ready", base);
    let deadline = Instant::now() + Duration::from_secs(120);
    while Instant::now() < deadline {
        if ureq::get(&url)
            .timeout(Duration::from_secs(2))
            .call()
            .is_ok()
        {
            return;
        }
        std::thread::sleep(Duration::from_millis(300));
    }
}

/// Fetch the server-driven window manifest built from the app's `DesktopApp` bean.
fn fetch_manifest(base: &str) -> Result<Manifest, Box<dyn std::error::Error>> {
    let url = format!("{}/api/desktop/manifest", base);
    let manifest = ureq::get(&url).call()?.into_json::<Manifest>()?;
    Ok(manifest)
}
