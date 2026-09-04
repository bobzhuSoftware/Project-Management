# Project Management

Local dashboard to register and operate the many side projects under `C:\Users\BOBZHU01\Projects` from one place: register a project's root directory and start/stop commands, then start, stop and tail logs from a single web UI.

## Stack

- Backend: Spring Boot 3.2.5, Java 17, JPA, H2 (file mode), Lombok
- Frontend: Vite + React 18 + TypeScript + axios
- Process control: `ProcessBuilder` + `ProcessHandle.descendants()` + PowerShell port-kill fallback
- Persistence: H2 file `./data/pm.mv.db`
- Logs:
  - Managed projects: per-process in-memory ring buffer (last 2000 lines) + per-day file under `./logs/` (named `<launchId>-<date>.log`)
  - PM backend itself: rolling file under `./logs/backend/pm-backend.log` (see **Backend logs** below)

## Run

```cmd
start-dev.cmd
```

Then open <http://127.0.0.1:5180>.

To stop everything (backend, frontend, and anything still bound on 8090/5180):

```cmd
powershell -ExecutionPolicy Bypass -File stop-dev.ps1
```

## Tray mode (app-like quick launch)

An alternative to the dev flow for **daily use** (not development). It runs a single
Spring Boot process that serves both the API and the pre-built frontend on one port,
and lives in the system tray — no Vite, faster to open.

```cmd
start-tray.cmd
```

- First run builds the frontend and packages a fat jar automatically; later runs just launch the jar.
- A tray icon appears near the clock. Double-click it (or right-click → **打开界面**) to open the UI; right-click → **退出** to stop.
- Runs on port 8090 with the same H2 database and logs as the dev flow.

Rebuild after changing code:

```cmd
build-tray.cmd            REM rebuild frontend + jar
start-tray.cmd -Rebuild   REM rebuild then launch
```

> Tray mode defaults to port 8090, but auto-shifts to the next free port if it's busy
> (same behaviour as `start-dev.ps1`), so it can run alongside a running `start-dev.cmd`.
> Both modes share the same H2 database (`AUTO_SERVER` mode), so they see the same
> projects. Development still uses `start-dev.cmd` (two ports + hot reload) as before.

## Backend logs

PM's own backend log is written to `./logs/backend/pm-backend.log` (rolling: 10 MB per file,
14 days history, 200 MB total cap). This applies to **both** `start-dev` and `start-tray`.

This matters most for **tray mode**: `start-tray.ps1` launches with `javaw` (no console window),
so without this file the backend's stdout/stderr would be discarded and startup failures would be
impossible to diagnose. Check `./logs/backend/pm-backend.log` when the tray app misbehaves.

The file lives in a `backend/` subfolder so it never mixes with managed-project logs
(`./logs/<launchId>-<date>.log`), which the Logs API lists by launch-id prefix.

> Tray mode runs the packaged fat jar, so config changes only take effect after a rebuild
> (`build-tray.cmd` then `start-tray.cmd`). Dev mode picks it up on the next restart.

## Adding a project

For each project register:

- **Name** — must be unique
- **Root Directory** — absolute Windows path, e.g. `C:\Users\BOBZHU01\Projects\A Stock Stock Card`
- **Start Command** — what to run via `cmd /c` with cwd = root. Usually `start-dev.cmd`
- **Stop Command** *(optional)* — e.g. `powershell -ExecutionPolicy Bypass -File stop-dev.ps1`
- **Ports** — used for status detection and as a kill-by-port fallback when the process tree can't be reached

## Status semantics

| Status     | Meaning                                                                              |
|------------|--------------------------------------------------------------------------------------|
| RUNNING    | Started via this PM and the parent `Process` is alive                                |
| ATTACHED   | PM was restarted; the recorded PID is still alive. Live log stream is unavailable    |
| EXTERNAL   | No managed PID, but one of the declared ports is listening (started outside PM)      |
| STOPPED    | No managed PID and no declared port is listening                                     |

## Stop strategy

1. If a `stopCommand` is configured, run it first (synchronous, up to 20 s)
2. Walk the recorded PID via `ProcessHandle.descendants()` and `destroyForcibly()` everything
3. As a belt-and-braces fallback, kill any process still listening on declared ports

## Java version configuration

This project requires **Java 17**. If the machine's default `java` is a different version (e.g. JDK 8), you can override it in two places:

### For the PM app itself (Maven / Spring Boot startup)

Create a file named `.java-home` in the project root (it is gitignored, so each machine has its own copy).
Put the JDK path as a single line — no quotes, no extra text:

```
C:\Users\bob.zhu\jdk-17.0.19+10
```

`start-dev.ps1` reads this file on every launch and sets `JAVA_HOME` + prepends `\bin` to `PATH` before starting Maven. If the file does not exist, the system default is used unchanged.

### For projects managed by PM (start/stop commands)

Open **Settings** (⚙ in the sidebar) and enter the JAVA_HOME path there.
PM injects `JAVA_HOME` and `PATH` into every child process it launches.
Leave it blank to use the system default.

## Node.js version configuration

This project's frontend requires a compatible Node.js version (LTS recommended). If the machine's default `node` is incompatible, you can override it:

Create a file named `.node-home` in the project root (it is gitignored, so each machine has its own copy).
Put the Node.js installation directory as a single line — no quotes, no trailing slash:

```
C:\Program Files\nodejs
```

`start-dev.ps1` reads this file on every launch, sets `NODE_HOME`, and prepends the directory to `PATH` before starting the frontend. Both `npm install` and `npx vite` will use that Node.js version. If the file does not exist, the system default is used unchanged.

## Sharing a project (Reach: Local / Wi-Fi / Internet)

Each launch has a **Reach** toggle that controls how far its named address is reachable. Everything
defaults to **Local** (off); nothing is shared unless you opt a specific launch in.

| Reach        | Address                                   | Who can reach it                          | Dependency (on **this** machine) |
|--------------|-------------------------------------------|-------------------------------------------|----------------------------------|
| **Local**    | `http://<alias>.localhost`                | Only this machine                         | none (built-in proxy)            |
| **Wi-Fi**    | `http://<alias>.local:<port>`             | Devices on the same Wi-Fi (phones, etc.)  | none (built-in mDNS + LAN listener) |
| **Internet** | `https://<random>.trycloudflare.com?key=…`| Anyone with the link + key                | `cloudflared` binary (see below) |

### Dependencies

- **Local / Wi-Fi** need **nothing extra** — the reverse proxy and mDNS responder are built into the
  backend. Wi-Fi sharing reuses an existing firewall-allowed inbound port, so it needs no admin rights.- **Internet** sharing uses a [Cloudflare **quick tunnel**](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/):
  - Requires the **`cloudflared`** binary installed **only on this (host) machine**.
    Install with `winget install --id Cloudflare.cloudflared` or drop the single `cloudflared.exe`
    on your `PATH`. If it's missing, the Internet toggle reports a clear error.
  - **No Cloudflare account, no sign-up, no purchase, and no domain** are required for quick tunnels.
  - **People you share the link with need nothing** — just a browser. They do **not** install
    `cloudflared` or anything else.
  - It's an **outbound** connection, so it needs **no admin rights and no inbound firewall rule**,
    and it gives you real **HTTPS** that also works off your Wi-Fi.

> A quick-tunnel URL is temporary and **changes every time the tunnel (re)starts** — e.g. after an app
> restart the same launch gets a new `trycloudflare.com` address. For a permanent, stable URL you'd need
> a Cloudflare **named tunnel** (which does require an account and a domain); that is out of scope here.

> **CORS just works.** When the proxy forwards a request (via `<alias>.localhost`, `<alias>.local`, or a
> tunnel) it rewrites the `Host` **and** `Origin` headers to the upstream's own origin
> (`http://127.0.0.1:<port>`). Shared apps therefore see a request that matches the usual
> `http://localhost:*` / `http://127.0.0.1:*` dev CORS allowlists, so you don't need to add the share
> URL to each app's CORS config.

### Link lifetime (expiry)

When you flip a launch to **Internet**, you choose how long the link stays live:

- **Presets**: 1 hour (default) · 8 hours · 24 hours · 7 days.
- **No expiry** — stays up until you toggle it off or quit PM. Use this for **trusted partners** who
  need long-running access.

Regardless of the chosen lifetime, an Internet link **always**:

- carries a secret **`key`** in the URL — the link "names nothing" and refuses traffic without the key;
- **dies** the moment you toggle the launch back to Local/Wi-Fi, and when PM shuts down.

So "No expiry" is still safe: it's key-gated, host-controlled, and never outlives the app.

## Security

The backend binds to `127.0.0.1` and executes arbitrary user-supplied shell commands. **Do not expose
the PM dashboard itself to the LAN or the internet.**

The **Reach** feature only ever exposes the *managed launches you explicitly opt in* — never PM's own
API or other launches. Wi-Fi/Internet listeners apply admission control (unknown or not-shared hosts get
an opaque 404), and Internet links are key-gated and revocable. Everything defaults to Local (off).
