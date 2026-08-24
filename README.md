# Project Management

Local dashboard to register and operate the many side projects under `C:\Users\BOBZHU01\Projects` from one place: register a project's root directory and start/stop commands, then start, stop and tail logs from a single web UI.

## Stack

- Backend: Spring Boot 3.2.5, Java 17, JPA, H2 (file mode), Lombok
- Frontend: Vite + React 18 + TypeScript + axios
- Process control: `ProcessBuilder` + `ProcessHandle.descendants()` + PowerShell port-kill fallback
- Persistence: H2 file `./data/pm.mv.db`
- Logs: per-process in-memory ring buffer (last 2000 lines) + per-day file under `./logs/`

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

## Security

The backend binds to `127.0.0.1` and executes arbitrary user-supplied shell commands. **Do not expose it to the LAN.**
