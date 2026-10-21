/**
 * @workbuddy/dsh-mobile
 *
 * WorkBuddy 一体化 APK 的 DSH 内核内置插件（由 App 在每次启动内核前写入 profile；
 * 源文件在 android/dsh/assets/dsh-plugins/wb-mobile，不要改手机上的副本）。
 *
 * 它解决两个问题：
 *  1. 能力：Android 上没有 GNU 用户态，官方 bash 工具 spawn "bash" 会 ENOENT/EACCES，
 *     这里提供真正可用的执行通道 —— wb_sh（/system/bin/sh）、wb_shizuku（宿主桥 → uid 2000）。
 *  2. 认知：模型不知道这台手机有什么（权限、PATH、工具链、Shizuku、npm），
 *     于是反复猜命令、反复失败。这里用 wb_env 工具 + 一段「环境手册」系统提示词把事实讲清楚。
 *
 * 设计约束（改代码时请遵守）：
 *  - 零第三方依赖：只 import node: 内置模块。profile 的 node_modules 里解析不到 @deepseek-ai/*，
 *    一旦引入就会 ERR_MODULE_NOT_FOUND 让整个插件加载失败。
 *  - 工具定义用 ctx.tools.register 的原始 JSON Schema（不用 defineTool）；schema 只允许
 *    type/properties/required/additionalProperties/items/enum/const 加注释键（description 等）。
 *  - 桥 token 只在进程内使用：子进程环境会被 harness 的凭据清洗（名字含 TOKEN 的一律剔除）。
 */
import { accessSync, constants as fsConstants, readdirSync, statSync } from "node:fs";
import { delimiter } from "node:path";

export const name = "wb-mobile";
/**
 * 只硬依赖 tools 与 systemPrompt：subprocess 是惰性获取的（见 runShell），
 * 这样即使组合里没有加载 dsh-subprocess-local，wb_env / wb_notify 仍然可用，
 * 而不是整个插件停在 pending 状态。
 */
export const inject = ["tools", "systemPrompt"];

/** Android 上唯一保证存在的 shell（mksh）。绝对路径，绕开 PATH 解析与 exec 限制。 */
const SHELL = "/system/bin/sh";
/** 宿主回环桥（LocalBridge，只监听 127.0.0.1，要求 x-wb-token）。 */
const BRIDGE = "http://127.0.0.1:7865";
const MAX_OUTPUT_BYTES = 48 * 1024;
const MAX_SPILL_BYTES = 8 * 1024 * 1024;
const DEFAULT_TIMEOUT_MS = 60000;
const MAX_TIMEOUT_MS = 300000;
const GRACE_MS = 3000;

/** 面向模型的终端环境（与 dsh-bash-local 的 ENV_OVERRIDES 同源，避免颜色/分页器污染输出）。 */
const CHILD_ENV = {
  NO_COLOR: "1",
  TERM: "dumb",
  PAGER: "cat",
  GIT_PAGER: "cat",
  LANG: "en_US.UTF-8",
};

/** 探测清单：判断哪些命令这台机器上真的能用。 */
const PROBE_COMMANDS = [
  "sh", "bash", "toybox", "busybox",
  "node", "npm", "npx", "pnpm",
  "git", "python3", "sqlite3", "ffmpeg", "magick",
  "curl", "wget", "ssh", "adb", "rg", "jq",
  "tar", "unzip", "zip", "make", "clang", "go", "java", "vim", "tmux",
];

function errText(error) {
  if (error === undefined || error === null) return "unknown error";
  if (typeof error === "string") return error;
  const message = error && error.message ? error.message : String(error);
  return message;
}

function clampTimeout(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return DEFAULT_TIMEOUT_MS;
  return Math.min(Math.max(Math.round(n), 1000), MAX_TIMEOUT_MS);
}

function bridgeToken() {
  return process.env.WB_BRIDGE_TOKEN || "";
}

function workspaceDir() {
  return process.env.DSH_WORKSPACE || process.cwd();
}

/** 在 PATH 里找可执行文件（只判断权限位，不真正执行）。 */
function findExecutable(command) {
  const dirs = (process.env.PATH || "").split(delimiter).filter(function (d) { return d.length > 0; });
  for (let i = 0; i < dirs.length; i++) {
    const candidate = dirs[i] + "/" + command;
    try {
      accessSync(candidate, fsConstants.X_OK);
      return candidate;
    } catch (error) { /* 继续找下一个 */ }
  }
  return "";
}

function listDir(dir) {
  try {
    if (!statSync(dir).isDirectory()) return [];
    return readdirSync(dir).filter(function (n) { return n.indexOf(".") !== 0; }).sort();
  } catch (error) {
    return [];
  }
}

function canAccess(target, mode) {
  try {
    accessSync(target, mode);
    return true;
  } catch (error) {
    return false;
  }
}

/** 宿主桥健康检查（顺带拿到 Shizuku 授权状态）。 */
async function bridgeHealth() {
  const controller = new AbortController();
  const timer = setTimeout(function () { controller.abort(); }, 1500);
  try {
    const response = await fetch(BRIDGE + "/health", {
      method: "POST",
      headers: { "content-type": "application/json", "x-wb-token": bridgeToken() },
      body: "{}",
      signal: controller.signal,
    });
    const json = await response.json();
    return json && json.ok === true ? json : null;
  } catch (error) {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/** POST 到宿主桥；非 2xx 或 ok!=true 一律抛错（调用方转成工具失败结果）。 */
async function bridgePost(path, body, timeoutMs) {
  if (bridgeToken().length === 0) {
    throw new Error("宿主桥 token 缺失（WB_BRIDGE_TOKEN）：内核可能不是由 WorkBuddy 启动的");
  }
  const controller = new AbortController();
  const timer = setTimeout(function () { controller.abort(); }, timeoutMs);
  try {
    const response = await fetch(BRIDGE + path, {
      method: "POST",
      headers: { "content-type": "application/json", "x-wb-token": bridgeToken() },
      body: JSON.stringify(body || {}),
      signal: controller.signal,
    });
    const raw = await response.text();
    let json = null;
    try { json = JSON.parse(raw); } catch (error) { json = null; }
    if (json === null) throw new Error("宿主桥返回非 JSON 响应：" + raw.slice(0, 200));
    if (!response.ok || json.ok !== true) throw new Error(json.error || ("宿主桥 HTTP " + response.status));
    return json;
  } finally {
    clearTimeout(timer);
  }
}

// ───────────────────────────── wb_sh：POSIX shell ─────────────────────────────

async function runShell(ctx, args, exec) {
  const cwd = typeof args.workdir === "string" && args.workdir.length > 0 ? args.workdir : workspaceDir();
  const timeoutMs = clampTimeout(args.timeoutMs);
  const controller = new AbortController();
  const timer = setTimeout(function () { controller.abort(); }, timeoutMs);
  let signal = controller.signal;
  if (exec && exec.signal && typeof AbortSignal.any === "function") {
    signal = AbortSignal.any([controller.signal, exec.signal]);
  }
  const subprocess = typeof ctx.get === "function" ? ctx.get("subprocess") : undefined;
  if (!subprocess) {
    clearTimeout(timer);
    throw new Error("wb_sh: 当前组合没有 subprocess 服务（dsh-subprocess-local 未加载），无法执行命令");
  }
  let handle;
  try {
    handle = subprocess.spawn({
      argv: [SHELL, "-c", args.command],
      cwd: cwd,
      stdio: {
        stdin: "ignore",
        stdout: { maxBytes: MAX_OUTPUT_BYTES, spill: { maxBytes: MAX_SPILL_BYTES } },
        stderr: { maxBytes: MAX_OUTPUT_BYTES, spill: { maxBytes: MAX_SPILL_BYTES } },
      },
      graceMs: GRACE_MS,
      signal: signal,
      env: CHILD_ENV,
    });
  } catch (error) {
    clearTimeout(timer);
    throw new Error("wb_sh: 无法启动 " + SHELL + "（" + errText(error) + "）；若 workdir 不存在请改用绝对路径重试");
  }
  let outcome = null;
  let failure = "";
  try {
    outcome = await handle.done;
  } catch (error) {
    failure = errText(error);
  } finally {
    clearTimeout(timer);
  }
  const out = handle.collected.stdout.readFrom(0);
  const err = handle.collected.stderr.readFrom(0);
  const stderr = [err.text, failure].filter(function (s) { return s && s.length > 0; }).join("\n");
  return {
    exitCode: outcome && typeof outcome.exitCode === "number" ? outcome.exitCode : -1,
    stdout: out.text,
    stderr: stderr,
    timedOut: controller.signal.aborted === true,
    signal: outcome && outcome.signal ? String(outcome.signal) : "",
    truncated: out.lossy === true || err.lossy === true,
    timeoutMs: timeoutMs,
    cwd: cwd,
  };
}

function shellTool(ctx) {
  return {
    name: "wb_sh",
    description: "Run a POSIX shell command through /system/bin/sh (Android mksh). Call it as await tools.wb_sh({ command, description }) inside run_code; never use child_process/execSync there.",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["command", "description"],
      properties: {
        command: {
          type: "string",
          description: "POSIX shell script body, executed by /system/bin/sh -c. No bash-only syntax (no [[ ]], arrays, or brace expansion).",
        },
        description: {
          type: "string",
          description: "Short active-voice summary of the call, shown in the UI (5-10 words).",
        },
        workdir: {
          type: "string",
          description: "Absolute working directory for this call. Defaults to the session workspace.",
        },
        timeoutMs: {
          type: "integer",
          description: "Timeout in milliseconds (default 60000, max 300000).",
        },
      },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        required: ["exitCode", "stdout", "stderr", "timedOut", "signal", "truncated", "timeoutMs", "cwd"],
        properties: {
          exitCode: { type: "integer" },
          stdout: { type: "string" },
          stderr: { type: "string" },
          timedOut: { type: "boolean" },
          signal: { type: "string" },
          truncated: { type: "boolean" },
          timeoutMs: { type: "integer" },
          cwd: { type: "string" },
        },
      },
      render: function (_args, value) {
        const lines = [];
        lines.push(value.stdout && value.stdout.length > 0 ? value.stdout : "(no output)");
        if (value.stderr && value.stderr.length > 0) lines.push("[stderr]\n" + value.stderr);
        if (value.truncated) lines.push("[output truncated; only the tail is shown]");
        if (value.timedOut) lines.push("[timed out after " + value.timeoutMs + "ms]");
        if (value.signal) lines.push("[killed by signal: " + value.signal + "]");
        lines.push("[exit code: " + value.exitCode + "]");
        return [{ type: "text", text: lines.join("\n") }];
      },
    },
    async execute(args, exec) {
      return await runShell(ctx, args, exec);
    },
  };
}

// ───────────────────────────── wb_env：环境体检 ─────────────────────────────

function environmentFacts() {
  const available = [];
  const missing = [];
  const located = {};
  for (let i = 0; i < PROBE_COMMANDS.length; i++) {
    const command = PROBE_COMMANDS[i];
    const found = findExecutable(command);
    if (found.length > 0) {
      available.push(command);
      located[command] = found;
    } else {
      missing.push(command);
    }
  }
  const toolchainBin = process.env.WB_TOOLCHAIN_BIN || "";
  const toolsBin = process.env.WB_TOOLS_BIN || "";
  const storageRoot = "/storage/emulated/0";
  return {
    workspace: workspaceDir(),
    shell: SHELL,
    path: (process.env.PATH || "").split(delimiter).filter(function (d) { return d.length > 0; }),
    available: available,
    missing: missing,
    located: located,
    toolchain: listDir(toolchainBin),
    tools: listDir(toolsBin),
    storageRoot: storageRoot,
    storageWritable: canAccess(storageRoot, fsConstants.W_OK),
    workspaceWritable: canAccess(workspaceDir(), fsConstants.W_OK),
    notifyScript: process.env.WB_NOTIFY || "",
  };
}

function envTool() {
  return {
    name: "wb_env",
    description: "Report this Android device's real toolchain: available commands, storage permissions, Shizuku bridge. Call it first (await tools.wb_env({})) when unsure what exists.",
    parameters: {
      type: "object",
      additionalProperties: false,
      properties: {},
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        required: [
          "summary", "workspace", "shell", "available", "missing", "toolchain",
          "storageWritable", "workspaceWritable", "bridge", "shizuku", "notify",
        ],
        properties: {
          summary: { type: "string" },
          workspace: { type: "string" },
          shell: { type: "string" },
          available: { type: "array", items: { type: "string" } },
          missing: { type: "array", items: { type: "string" } },
          toolchain: { type: "array", items: { type: "string" } },
          storageWritable: { type: "boolean" },
          workspaceWritable: { type: "boolean" },
          bridge: { type: "boolean" },
          shizuku: { type: "boolean" },
          notify: { type: "boolean" },
        },
      },
      render: function (_args, value) {
        return [{ type: "text", text: value.summary }];
      },
    },
    async execute() {
      const facts = environmentFacts();
      const health = await bridgeHealth();
      const shizuku = !!(health && health.shizuku && health.shizuku.granted === true);
      const shizukuInstalled = !!(health && health.shizuku && health.shizuku.available === true);
      const notify = facts.notifyScript.length > 0 || health !== null;
      const lines = [];
      lines.push("Android 环境体检");
      lines.push("- 工作区：" + facts.workspace + (facts.workspaceWritable ? "（可写）" : "（不可写！）"));
      lines.push("- 共享存储 " + facts.storageRoot + "：" + (facts.storageWritable ? "可写（已授予「所有文件访问」）" : "不可写（未授予「所有文件访问」，只能访问应用私有目录）"));
      lines.push("- shell：" + facts.shell + "（mksh；无 GNU 工具集，只有 toybox）");
      lines.push("- PATH：" + facts.path.join(" : "));
      lines.push("- 可用命令：" + (facts.available.length > 0 ? facts.available.join(", ") : "（无）"));
      lines.push("- 不可用命令：" + (facts.missing.length > 0 ? facts.missing.join(", ") : "（无）"));
      if (facts.toolchain.length > 0) lines.push("- 已装工具链（home/toolchain/usr/bin）：" + facts.toolchain.join(", "));
      else lines.push("- 已装工具链：无（需要时让用户去「设置 → 扩展能力 → 系统工具链」安装）");
      if (facts.tools.length > 0) lines.push("- 已装 npm CLI（home/tools）：" + facts.tools.join(", "));
      lines.push("- node/npm/pnpm：" + (facts.located["node"] ? "node=" + facts.located["node"] : "node 缺失") + "；" + (facts.available.indexOf("npm") >= 0 ? "npm 可用" : "npm 缺失") + "；" + (facts.available.indexOf("pnpm") >= 0 ? "pnpm 可用" : "pnpm 缺失"));
      lines.push("- 宿主桥：" + (health !== null ? "在线" : "离线（通知/Shizuku 通道不可用）"));
      lines.push("- Shizuku：" + (shizuku ? "已授权（可用 wb_shizuku 以 shell 身份执行）" : (shizukuInstalled ? "已安装未授权（让用户到「设置 → 扩展能力」授权）" : "未安装/未启动（wb_shizuku 不可用）")));
      return {
        summary: lines.join("\n"),
        workspace: facts.workspace,
        shell: facts.shell,
        available: facts.available,
        missing: facts.missing,
        toolchain: facts.toolchain,
        storageWritable: facts.storageWritable,
        workspaceWritable: facts.workspaceWritable,
        bridge: health !== null,
        shizuku: shizuku,
        notify: notify,
      };
    },
  };
}

// ───────────────────────────── wb_shizuku：uid 2000 ─────────────────────────────

function shizukuTool() {
  return {
    name: "wb_shizuku",
    description: "Run a privileged Android command through Shizuku (shell uid 2000, or root under KernelSU/Magisk): pm, input, screencap, settings, dumpsys. Call it as await tools.wb_shizuku({ command, description }).",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["command", "description"],
      properties: {
        command: {
          type: "string",
          description: "Command line executed by sh -c as uid 2000, e.g. pm list packages -3 or input tap 540 1200.",
        },
        description: {
          type: "string",
          description: "Short active-voice summary of the call, shown in the UI (5-10 words).",
        },
        timeoutMs: {
          type: "integer",
          description: "Timeout in milliseconds (default 25000, max 120000).",
        },
      },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        required: ["ok", "exitCode", "output", "error"],
        properties: {
          ok: { type: "boolean" },
          exitCode: { type: "integer" },
          output: { type: "string" },
          error: { type: "string" },
        },
      },
      render: function (_args, value) {
        if (!value.ok) return [{ type: "text", text: "[shizuku unavailable] " + value.error }];
        const lines = [];
        lines.push(value.output && value.output.length > 0 ? value.output : "(no output)");
        lines.push("[exit code: " + value.exitCode + "]");
        return [{ type: "text", text: lines.join("\n") }];
      },
    },
    async execute(args) {
      const timeoutMs = Math.min(clampTimeout(args.timeoutMs || 25000), 120000);
      try {
        const json = await bridgePost("/shell", { command: args.command, timeoutMs: timeoutMs }, timeoutMs + 15000);
        return {
          ok: true,
          exitCode: typeof json.code === "number" ? json.code : -1,
          output: typeof json.output === "string" ? json.output : "",
          error: "",
        };
      } catch (error) {
        return { ok: false, exitCode: -1, output: "", error: errText(error) };
      }
    },
  };
}

// ───────────────────────────── wb_notify：系统通知 ─────────────────────────────

function notifyTool() {
  return {
    name: "wb_notify",
    description: "Push a notification to the phone's notification shade, e.g. when a long task finishes. Call it as await tools.wb_notify({ text }).",
    parameters: {
      type: "object",
      additionalProperties: false,
      required: ["text"],
      properties: {
        title: { type: "string", description: "Short notification title (defaults to WorkBuddy)." },
        text: { type: "string", description: "Notification body text." },
      },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        required: ["ok", "error"],
        properties: {
          ok: { type: "boolean" },
          error: { type: "string" },
        },
      },
      render: function (_args, value) {
        return [{ type: "text", text: value.ok ? "notification posted" : "[notify failed] " + value.error }];
      },
    },
    async execute(args) {
      try {
        await bridgePost("/notify", { title: args.title || "WorkBuddy", text: args.text }, 10000);
        return { ok: true, error: "" };
      } catch (error) {
        return { ok: false, error: errText(error) };
      }
    },
  };
}

// ───────────────────────────── 环境手册（系统提示词段） ─────────────────────────────

function environmentManual() {
  return [
    "## Android 运行环境（WorkBuddy 一体化 APK 内的 DSH 内核）",
    "",
    "你在一台 Android 手机里运行：Node 内核进程以 WorkBuddy 应用的 uid 执行。",
    "下面的环境事实来自实测配置，不要按 Linux 桌面/服务器的习惯假设。",
    "",
    "### 调用方式（本会话是 PTC 模式，先读这段）",
    "- 你只能直接调用 run_code；其它工具（bash / read / write / wb_sh …）都必须在代码里调用：await tools.工具名({ ... })。",
    "- 不要把工具名当命令去执行（例如 sh('wb_env') 一定失败），也不要在 run_code 里用 child_process / execSync / spawn / spawnSync：",
    "  内核里的 node 是 Termux 构建，默认 shell 被硬编码成 /data/data/com.termux/files/usr/bin/sh，这台机器上并不存在，",
    "  因此每次调用都是 spawnSync ... ENOENT。执行命令只有两条路：tools.wb_sh 或 tools.bash。",
    "- 常用调用：",
    "    const env = await tools.wb_env({});                 // 环境体检，summary 里是给人看的报告",
    "    const r = await tools.wb_sh({ command: \"ls /sdcard/Download | head\", description: \"list downloads\" });",
    "    console.log(r.exitCode, r.stdout, r.stderr);",
    "    const p = await tools.wb_shizuku({ command: \"pm list packages -3\", description: \"list user apps\" });",
    "    await tools.wb_notify({ title: \"WorkBuddy\", text: \"任务完成\" });",
    "- run_code 自己的沙箱：cwd 是会话工作区（常见 /data/data/<包名>/files/<目录>），env 里没有 PATH / HOME；",
    "  fs / path 可以正常用（读写手机文件没问题），但沙箱里不能执行任何命令 —— 真正带 PATH（home/bin、toolchain）的环境只在 tools.wb_sh / tools.bash 里。",
    "",
    "### 文件",
    "- 会话工作区：" + workspaceDir() + "；内核默认工作区是 /storage/emulated/0/WorkBuddy/workspace。",
    "- 已授予「所有文件访问」（MANAGE_EXTERNAL_STORAGE）：/storage/emulated/0 下的用户文件（Download、Documents、DCIM、Pictures、Android/media 等）可直接用 read / write / edit / glob / grep 或 run_code 里的 fs 读写，无需审批。",
    "- 优先用文件工具（read/write/edit/glob/grep）而不是 shell 的 cat/sed/find/awk：更快、不依赖二进制、结果结构化。",
    "- 路径一律写绝对路径；Android 文件系统区分大小写。",
    "- /sdcard 是 FUSE 挂载且带 noexec：不要在那里执行任何二进制或脚本；chmod 与文件所有者语义也不生效。",
    "- 应用私有目录同样不可 exec（SELinux）：不要直接执行 files/ 下的文件。",
    "",
    "### Shell（在 tools.wb_sh / tools.bash 里执行）",
    "- Android 没有 GNU 用户态：系统只有 /system/bin/sh（mksh）与 toybox。",
    "- tools.wb_sh 直接跑 /system/bin/sh -c；tools.bash 走同一个 shell（启动器把 bash 映射到 mksh），两者都只支持 POSIX 语法 —— 没有双方括号条件、数组、ANSI-C 引号、花括号展开、local -n、进程替换。",
    "- 先试 tools.wb_sh；它报错（ENOENT / spawn 失败 / 语法错误）时读 stderr 换写法，不要原样重复。",
    "- 已有命令（toybox）：ls cat cp mv rm mkdir ln touch head tail find grep sed awk cut sort uniq xargs tar gzip unzip date stat du df ps kill sleep printf readlink which id getprop。",
    "- toybox 与 GNU 有差异：grep 没有 -P、sed 的 -i 不接受备份后缀、find 的部分谓词不同；复杂文本处理优先在 run_code 里用 JS 写（fs + 字符串处理）。",
    "- 已内置 node / npm / pnpm（home/bin 里是指向 jniLibs 启动器的符号链接）：在 tools.wb_sh 里直接写命令名，不要写绝对路径，也不要自己拼接 exec。",
    "- npm/pnpm：pnpm add 包名（registry 已指向 npmmirror）；CLI 工具装到 home/tools，其 .bin 已在 PATH。",
    "",
    "### 工具链（按需安装，不要假设存在）",
    "- git / python3 / sqlite3 / ffmpeg / magick / clang / go / java / ssh / adb / vim / tmux / make 需要用户先在「设置 → 扩展能力 → 系统工具链」安装；装在 home/toolchain/usr 且已进 PATH，装好后在 tools.wb_sh 里直接写命令名。",
    "- 不确定某命令是否存在：先 await tools.wb_env({})（一次给出可用/不可用命令、权限、桥状态），不要靠猜。",
    "",
    "### 提权（Shizuku）",
    "- 需要特权身份的动作走 tools.wb_shizuku：pm install/uninstall、input tap/text/swipe、screencap、settings put、dumpsys、am start、wm size、cmd。",
    "- 它经宿主回环桥（127.0.0.1:7865）转发，要求用户已安装并授权 Shizuku；返回 shizuku unavailable 时降级到 tools.wb_sh 或无障碍能力，不要反复重试。",
    "- 真实身份因设备而异：普通设备是 shell（uid 2000），KernelSU/Magisk 环境下是 root。第一次使用先执行 id -un 确认，再决定是否执行破坏性操作；删除系统文件、卸载系统应用这类动作必须先得到用户明确同意。",
    "",
    "### 其它",
    "- 通知用户：tools.wb_notify（标题 + 正文）；长任务完成或需要用户确认时用。",
    "- 批量/复杂处理：就在 run_code 里写 TypeScript 组合调用（Promise.all 并发多个 tools.wb_sh），避免多轮往返。",
    "",
    "### 排错约定",
    "- 任何工具报错：先读 stderr 与错误文本，再决定换工具、换语法还是先 tools.wb_env；禁止原样重复同一条失败命令。",
    "- 非零退出码是命令结果而不是工具故障：解释它，不要当成重试理由。",
    "- 改文件用 edit/write 而不是 shell 重定向。",
  ].join("\n");
}

export function apply(ctx) {
  ctx.tools.register(shellTool(ctx));
  ctx.tools.register(envTool());
  ctx.tools.register(shizukuTool());
  ctx.tools.register(notifyTool());
  try {
    ctx.systemPrompt.section({
      name: "workbuddy:android-environment",
      order: 1050,
      interpolate: false,
      text: environmentManual(),
    });
  } catch (error) {
    try { ctx.logger.warn("wb-mobile: 环境手册注入失败：" + errText(error)); } catch (ignored) { /* 无 logger 时忽略 */ }
  }
}
