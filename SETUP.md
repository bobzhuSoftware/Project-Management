# Setup Guide / 新机器配置指南

> **English** instructions are on the left column of each section.  
> **中文** 说明在每节的右侧（或紧随其后）。

---

## 1. Prerequisites / 前置软件

You need the following four tools installed and available on `PATH` before cloning.  
克隆代码前，需要先安装以下四个工具并确保它们已加入 `PATH`。

| Tool / 工具 | Version / 版本 | Purpose / 用途 |
|-------------|---------------|----------------|
| **Git** | Any modern / 任意现代版本 | Clone the repo / 克隆代码 |
| **Java JDK** | **17** (required / 必须) | Run Spring Boot backend / 运行后端 |
| **Maven** | 3.6+ | Build & run backend / 构建并启动后端 |
| **Node.js + npm** | LTS recommended / 推荐 LTS | Run Vite + React frontend / 运行前端 |

### Verify installation / 验证安装

Run the following in a terminal to confirm each tool is available:  
在终端中运行以下命令确认各工具可用：

```cmd
git --version
java -version
mvn -version
node -v
npm -v
```

All commands should print a version number without errors.  
所有命令都应输出版本号，无报错。

---

## 2. Clone the Repository / 克隆代码

```cmd
git clone <your-github-repo-url>
cd "Project Management"
```

---

## 3. Java Version Override (Optional) / Java 路径覆盖（可选）

**When to use:** If your machine's default `java` is not Java 17 (e.g. it is JDK 8 or 21), you need this step.  
**适用情况：** 如果当前机器默认的 `java` 不是 Java 17（例如是 JDK 8 或 21），需要执行此步骤。

Create a file named **`.java-home`** in the project root (it is already git-ignored).  
在项目根目录创建名为 **`.java-home`** 的文件（该文件已被 git 忽略）。

Write the path to your JDK 17 directory as a single line — no quotes, no trailing slash:  
文件内容为 JDK 17 的安装路径，一行，无引号，无尾部斜线：

```
C:\path\to\your\jdk-17
```

For example / 示例：

```
C:\Users\bob.zhu\jdk-17.0.19+10
```

`start-dev.ps1` reads this file on every launch and automatically sets `JAVA_HOME` and prepends `\bin` to `PATH`.  
`start-dev.ps1` 每次启动时会读取该文件，并自动设置 `JAVA_HOME` 并将 `\bin` 加入 `PATH`。

---

## 4. Start the Application / 启动应用

```cmd
start-dev.cmd
```

That's it. No manual database setup is required.  
就这一步，**无需手动建表或执行任何 SQL**。

### What happens on first launch / 首次启动时会自动发生

| Step / 步骤 | Details / 说明 |
|-------------|---------------|
| Hibernate creates all tables | 根据 JPA Entity 自动建表（`projects`、`runtime_state` 等） |
| `schema.sql` runs | 执行增量 ALTER 语句（IF NOT EXISTS 保证安全） |
| Maven downloads dependencies | Maven 自动下载所有 Java 依赖，**首次耗时较长，请耐心等待** |
| `npm install` runs automatically | 检测到无 `node_modules` 时自动安装前端依赖 |
| H2 database file is created | 在 `backend/data/pm.mv.db` 自动创建数据库文件 |

---

## 5. Verify / 验证启动成功

Once the console shows the startup banner, open your browser:  
控制台出现启动完成提示后，在浏览器中打开：

| Service / 服务 | URL |
|----------------|-----|
| Frontend UI / 前端界面 | <http://127.0.0.1:5180> |
| Backend API / 后端接口 | <http://127.0.0.1:8090> |
| H2 Console / 数据库控制台 | <http://127.0.0.1:8090/h2-console> |

> Ports may differ if 8090 or 5180 are already in use. Check the console output for the actual ports.  
> 如果 8090 或 5180 端口已被占用，脚本会自动换用其他端口，请以控制台输出为准。

---

## 6. Stop the Application / 停止应用

```cmd
powershell -ExecutionPolicy Bypass -File stop-dev.ps1
```

Or press `Ctrl+C` in the terminal to stop log streaming (background jobs keep running until `stop-dev.ps1` is called).  
也可以在终端中按 `Ctrl+C` 停止日志输出流（后台进程仍在运行，需运行 `stop-dev.ps1` 才能彻底停止）。

---

## 7. Troubleshooting / 常见问题

### Backend fails to start / 后端启动失败

- Confirm `java -version` outputs **17**. If not, create the `.java-home` file (see Step 3).  
  确认 `java -version` 输出的是 **17**。否则请创建 `.java-home` 文件（见第 3 步）。
- Confirm `mvn -version` works. If `mvn` is not found, install Maven and add it to `PATH`.  
  确认 `mvn -version` 可以运行。若找不到 `mvn`，请安装 Maven 并加入 `PATH`。

### Frontend fails to start / 前端启动失败

- Confirm `node -v` and `npm -v` both work.  
  确认 `node -v` 和 `npm -v` 均可正常运行。
- If Vite fails, manually run `npm install` in the `frontend/` directory.  
  如果 Vite 启动失败，手动在 `frontend/` 目录中运行 `npm install`。

### Port already in use / 端口被占用

The startup script automatically picks an alternative port. You can also free the port manually:  
启动脚本会自动切换到空闲端口，也可手动释放端口：

```powershell
# Find and kill the process on port 8090
Get-NetTCPConnection -LocalPort 8090 -State Listen | Select-Object OwningProcess | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

### H2 Console login / H2 控制台登录

| Field / 字段 | Value / 值 |
|--------------|-----------|
| JDBC URL | `jdbc:h2:file:./data/pm` |
| Username / 用户名 | `sa` |
| Password / 密码 | *(leave blank / 留空)* |

---

## 8. Security Note / 安全说明

The backend binds to `127.0.0.1` only and executes arbitrary shell commands supplied by the user.  
**Do not expose port 8090 to your local network (LAN).**

后端仅绑定 `127.0.0.1`，且会执行用户配置的任意 Shell 命令。  
**请勿将 8090 端口暴露到局域网。**
