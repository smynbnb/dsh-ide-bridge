# DSH IDE Bridge

把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 接进 JetBrains IDE 的工具窗口：
桌面端照常运行，IDE 右侧直接对话、看流式输出、处理权限审批，不用再开浏览器标签页。

> 状态：个人项目，开发中。主要在 Windows + IntelliJ IDEA 2026.2 + DSH 0.2.1 上验证。

## 仓库结构

| 目录 | 端 | 作用 |
|---|---|---|
| [`dsh-plugin/`](dsh-plugin) | DSH 侧（Node ESM） | 发布带 token 的访问地址、注册 IDE 项目为工作区、MCP 工具桥、精简面板（SSE 流式 + 审批 + 权限） |
| [`plugin/`](plugin) | IDE 侧（Java + JCEF） | 读取交接文件、内嵌精简面板、按 IDE 真实主题取色、MCP 中转、选中代码发送 |
| [`tools/`](tools) | 开发 | 面板/SSE/审批的离线测试、主题取色测试 |

IDEA 插件 fork 自 [Chocomintopia/dsh-jetbrains-plugin](https://github.com/Chocomintopia/dsh-jetbrains-plugin)（MIT），
在其“静态地址 WebUI 工具窗口”的基础上，改成了连接**已在运行的桌面端**，并加入了精简面板、流式输出、审批窗口和主题跟随。

## 功能

- **内嵌精简面板**：不是完整 WebUI，只保留对话、思考、工具轨迹和输入框；同源加载，复用桌面端登录 cookie。
- **流式输出**：服务端把模型逐字帧、会话事件、工具调用通过 SSE 推给面板，不再轮询。
- **审批窗口**：工具请求权限时，面板底部弹卡片（工具名、原因、调用 id），允许一次 / 拒绝；只接管 IDE 面板所属会话，其他会话仍走桌面端。
- **权限选择**：只读 / 工作区可写 / 完全权限，直接切换当前会话的权限预设。
- **主题跟随**：采样 IDE 当前 LookAndFeel 的真实颜色（Islands、Darcula、IntelliJ Light、High Contrast、自定义主题），主题/编辑器配色切换后立即重刷；高对比主题下填充按钮使用主题自己的默认按钮配色，避免亮色底配白字。
- **Plan → Act 自动续跑**：从 Plan 切到 Act、输入框为空、且上一轮已有回复时，面板会自动发一条模式切换指令，让模型立刻执行计划，不用再手打“执行”；输入框里已有文字时不会抢发，避免把你的指令顶掉。Plan 模式下仍由服务端 `tools.guard` 拦截写操作；面板加的只读前缀只进模型上下文，聊天气泡里会剥掉。
- **插话发送**：回合运行时点「插话」（或 Ctrl/⌘+Enter），消息以 `steer` 模式注入当前回合，而不是排队到下一轮；空闲时该按钮禁用。
- **工作区自动注册**：在 IDE 里打开项目，DSH 侧自动把它注册为工作区，新会话默认落到该项目。
- **MCP 工具桥**：IDE 在线时把 IDE 的 MCP 工具注册进模型工具表，IDE 关闭时自动注销。
- **断线重连**：桌面端重启后 token 轮换，IDE 侧自动重连并回到精简面板。

## 架构

```text
┌────────────────────────────┐          ┌─────────────────────────────┐
│ IntelliJ IDEA              │          │ DeepSeek Harness 桌面端      │
│                            │          │                             │
│  工具窗口（JCEF）           │  同源    │  dsh-plugin                 │
│   /api/ide-panel  ◄────────┼──────────┼─ ui-server.js（面板/SSE/审批）│
│        ▲                   │  cookie  │                             │
│        │ 主题颜色注入        │          │  index.js（写 bridge.json）  │
│   PanelTheme.java          │          │  mcp-bridge.js（MCP 客户端） │
│                            │          │                             │
│  BridgeConfig  ◄───────────┼──────────┼─ %LOCALAPPDATA%\dsh-ide-    │
│  （读 bridge.json）         │  文件    │  bridge\bridge.json         │
│                            │          │                             │
│  McpRelayServer :64343 ────┼──────────┼─► MCP 客户端                │
│        │                   │          │                             │
│        ▼                   │          │                             │
│  IDE MCP Server :64342     │          │                             │
└────────────────────────────┘          └─────────────────────────────┘
```

`bridge.json` 由 DSH 侧插件在启动时写入，内含带启动 token 的地址；IDE 侧读它、校验进程存活、探测端口，然后加载。

## 快速开始

### 前置

- DeepSeek Harness 桌面端（带 web）。
- IntelliJ IDEA 2026.2+（需要 JCEF，平台 build 262+）。
- 可选：IDEA 自带的 **MCP Server** 插件（端口 64342），用于把 IDE 工具暴露给模型。

### 1. 安装 DSH 侧插件

把 `dsh-plugin/` 放在一个稳定目录，然后在你的 DSH profile（例如 `%USERPROFILE%\.dsh\profiles\desktop`）里：

1. `package.json` 的 `dependencies` 增加：

   ```json
   "dsh-ide-bridge": "file:C:/path/to/dsh-plugin"
   ```

2. 安装依赖（在 profile 目录执行）：

   ```powershell
   pnpm install
   ```

3. `cordis.patch.yml` 追加：

   ```yaml
   - insert:
       - id: dsh-ide-bridge
         name: 'dsh-ide-bridge'
       - id: dsh-ide-mcp
         name: 'dsh-ide-bridge/mcp'
       - id: dsh-ide-ui
         name: 'dsh-ide-bridge/ui'
   ```

4. 重启 DeepSeek Harness。启动后应生成
   `%LOCALAPPDATA%\dsh-ide-bridge\bridge.json`，并监听本机端口。

`dsh-ide-ui` 额外注册了 `/api/ide-panel`、`/api/ide/stream`、`/api/ide/approval`、`/api/ide/permission`
等仅供 IDE 面板使用的同源路由。

### 2. 构建并安装 IDEA 插件

```powershell
.\plugin\build.ps1 -IdeRoot "D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.2"
```

在 `plugin\releases\` 生成 `dsh-ide-bridge.jar`。然后在 IDEA 里
**Settings → Plugins → ⚙ → Install Plugin from Disk…** 选择该 jar，重启 IDE。

打开右侧工具窗口 **DeepSeek Harness Bridge**，面板应连上桌面端当前会话。

> 也可以把 jar 放进 `%APPDATA%\JetBrains\IntelliJIdea2026.2\plugins\dsh-ide-bridge\lib\`，
> 本仓库的 `plugin/` 就是这样安装的。

## 配置

| 位置 | 项 | 说明 |
|---|---|---|
| IDEA JVM 参数 | `-Ddsh.bridge.path=<file>` | 覆盖交接文件路径（测试用） |
| IDEA JVM 参数 | `-Ddsh.web.url=<url>` | 没有 DSH 侧插件时，手工指定带 token 的地址 |
| DSH 侧插件 | `config.path` | 覆盖 `bridge.json` 输出路径 |

## 面板路径

| 路由 | 方法 | 说明 |
|---|---|---|
| `/api/ide-panel` | GET | 精简面板页面 |
| `/api/ide/stream` | GET | SSE：快照 + 会话事件 + 逐字帧 + 审批事件 |
| `/api/ide/approval` | POST | 面板对审批做决定（`allow` / `reject`） |
| `/api/ide/permission` | GET/POST | 读/切换当前会话权限预设 |
| `/api/ide/plan-mode` | POST | 面板的 Plan/Act 只读开关（配合 `tools.guard`） |
| `/api/ide/context` | GET | 把项目目录解析成 DSH 工作区 |

## 安全说明

- `bridge.json` 内含本次启动的访问 token，写在 `%LOCALAPPDATA%\dsh-ide-bridge\`，文件权限 0600；
  **不要提交、不要分享**。它等价于桌面端的登录凭据。
- 面板与 DSH 同源，使用浏览器签名 cookie 认证；所有 `/api` 路由都经过 DSH 的 Host/Origin 校验。
- MCP 中转只监听 `127.0.0.1`，不对外网开放。
- 仓库里的测试与文档不包含任何真实 token、账号或本机路径。

## 开发与验证

```powershell
# 面板逻辑 + 服务端路由/SSE/审批（Node，无需 IDE）
node tools\test-ui-server.mjs
node tools\test-panel-ui.mjs
```

`tools/java/ThemeCheck.java` 用 IDE 自带的 JDK 跑配色采样与注入脚本检查，
用法见文件头注释（需要 `-classpath` 指向 IDE 的 `lib` 与 JCEF 模块）。

## 许可证与致谢

- 本仓库：MIT，见 [LICENSE](LICENSE)。
- `plugin/` 派生自 [Chocomintopia/dsh-jetbrains-plugin](https://github.com/Chocomintopia/dsh-jetbrains-plugin)（MIT），
  原始版权与许可保留在 [`plugin/LICENSE`](plugin/LICENSE)。
- [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 及其图标归 DeepSeek 所有。
