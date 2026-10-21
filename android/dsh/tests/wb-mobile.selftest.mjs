#!/usr/bin/env node
/**
 * wb-mobile 插件自检（纯 Node，不需要 Android 设备）。
 *
 * 校验三件事：
 *  1. 模块能被 ESM 加载，apply() 注册了 4 个工具与 1 段系统提示词；
 *  2. 每个工具的 parameters / output.schema 落在 DSH 允许的 JSON Schema 子集内
 *     （type 单值、只允许 properties/required/additionalProperties/items/enum/const + 注释键）；
 *  3. 用假的 ctx.subprocess 跑一次 wb_sh / wb_env，检查返回值满足 output.schema、渲染成文本。
 *
 * 用法：node android/dsh/tests/wb-mobile.selftest.mjs
 */
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const pluginPath = path.resolve(here, "../assets/dsh-plugins/wb-mobile/index.js");

const CONSTRAINT = new Set(["type", "oneOf", "properties", "required", "additionalProperties", "items", "enum", "const"]);
const ANNOTATION = new Set(["description", "title", "default", "examples"]);
const TYPES = ["object", "array", "string", "number", "integer", "boolean", "null"];

const failures = [];
function check(condition, message) {
  if (!condition) failures.push(message);
}

function checkSchema(schema, where) {
  check(schema !== null && typeof schema === "object" && !Array.isArray(schema), where + ": schema must be an object");
  if (schema === null || typeof schema !== "object") return;
  for (const key of Object.keys(schema)) {
    check(CONSTRAINT.has(key) || ANNOTATION.has(key), where + ": unsupported schema keyword " + key);
  }
  if (Object.hasOwn(schema, "type")) {
    check(typeof schema.type === "string" && TYPES.includes(schema.type), where + ".type must be a single supported type string");
  }
  if (Object.hasOwn(schema, "properties")) {
    check(schema.type === "object", where + ".properties requires type object");
    for (const [key, child] of Object.entries(schema.properties)) checkSchema(child, where + ".properties." + key);
  }
  if (Object.hasOwn(schema, "required")) {
    check(schema.type === "object", where + ".required requires type object");
    check(Array.isArray(schema.required) && schema.required.every((n) => typeof n === "string"), where + ".required must be string[]");
    for (const name of schema.required || []) check(Object.hasOwn(schema.properties || {}, name), where + ".required lists unknown property " + name);
  }
  if (Object.hasOwn(schema, "items")) {
    check(schema.type === "array", where + ".items requires type array");
    checkSchema(schema.items, where + ".items");
  }
  if (Object.hasOwn(schema, "additionalProperties")) {
    check(typeof schema.additionalProperties === "boolean", where + ".additionalProperties must be boolean");
  }
}

function validateValue(schema, value, where) {
  if (!schema || typeof schema !== "object") return;
  const type = schema.type;
  if (type === "object") {
    check(value !== null && typeof value === "object" && !Array.isArray(value), where + ": expected object");
    if (value === null || typeof value !== "object" || Array.isArray(value)) return;
    for (const name of schema.required || []) check(Object.hasOwn(value, name), where + ": missing required field " + name);
    for (const [key, child] of Object.entries(value)) {
      const sub = (schema.properties || {})[key];
      if (sub === undefined) {
        check(schema.additionalProperties !== false, where + ": unexpected field " + key);
        continue;
      }
      validateValue(sub, child, where + "." + key);
    }
    return;
  }
  if (type === "array") {
    check(Array.isArray(value), where + ": expected array");
    if (!Array.isArray(value)) return;
    for (let i = 0; i < value.length; i++) validateValue(schema.items, value[i], where + "[" + i + "]");
    return;
  }
  if (type === "string") { check(typeof value === "string", where + ": expected string"); return; }
  if (type === "boolean") { check(typeof value === "boolean", where + ": expected boolean"); return; }
  if (type === "integer") { check(typeof value === "number" && Number.isInteger(value), where + ": expected integer"); return; }
  if (type === "number") { check(typeof value === "number", where + ": expected number"); return; }
  if (type === "null") { check(value === null, where + ": expected null"); }
}

/** 假 subprocess：不真的起进程，按 argv 回放固定输出。 */
function fakeSpawn(spec) {
  const command = spec.argv[2] || "";
  const stdoutText = command.indexOf("fail") >= 0 ? "" : "hello from fake sh\n";
  const stderrText = command.indexOf("fail") >= 0 ? "fake failure\n" : "";
  const reader = (text) => ({ readFrom: () => ({ text: text, lossy: false, nextOffset: text.length }) });
  const handle = {
    status: "completed",
    exitCode: 0,
    signal: null,
    collected: { stdout: reader(stdoutText), stderr: reader(stderrText) },
    done: Promise.resolve({ exitCode: command.indexOf("fail") >= 0 ? 3 : 0, signal: null }),
    terminate: () => true,
    waitForExit: async () => {},
    readOutput: () => ({ delta: stdoutText, lossy: false }),
  };
  check(Array.isArray(spec.argv) && spec.argv[0] === "/system/bin/sh", "wb_sh must spawn /system/bin/sh, got " + spec.argv[0]);
  check(spec.argv[1] === "-c", "wb_sh must pass -c");
  check(typeof spec.graceMs === "number" && spec.graceMs > 0, "wb_sh must set a positive graceMs");
  check(spec.stdio && spec.stdio.stdout && typeof spec.stdio.stdout.maxBytes === "number", "wb_sh must bound stdout");
  check(spec.cwd !== undefined, "wb_sh must set cwd");
  return handle;
}

const tools = new Map();
const sections = [];
const ctx = {
  logger: { warn: (message) => console.log("[warn] " + message), debug: () => {} },
  tools: {
    register: (definition) => {
      check(!tools.has(definition.name), "duplicate tool " + definition.name);
      tools.set(definition.name, definition);
      return () => tools.delete(definition.name);
    },
  },
  systemPrompt: { section: (section) => sections.push(section) },
  get: (serviceName) => (serviceName === "subprocess" ? { spawn: fakeSpawn } : undefined),
};

const mod = await import(pathToFileURL(pluginPath).href);
check(typeof mod.apply === "function", "plugin must export apply()");
check(Array.isArray(mod.inject) && mod.inject.includes("tools"), "plugin must inject tools");
check(Array.isArray(mod.inject) && mod.inject.includes("systemPrompt"), "plugin must inject systemPrompt");
mod.apply(ctx);

console.log("已注册工具：" + [...tools.keys()].join(", "));
check(tools.size === 4, "expected 4 tools, got " + tools.size);
check(sections.length === 1, "expected 1 prompt section, got " + sections.length);
check(sections[0] && sections[0].interpolate === false, "prompt section must disable interpolation");
check(sections[0] && sections[0].text.length > 800, "prompt section must carry the environment manual");
check(sections[0] && sections[0].text.includes("wb_sh"), "manual must mention wb_sh");
check(sections[0] && !sections[0].text.includes("{{"), "manual must not contain interpolation markers");

for (const [name, definition] of tools) {
  check(typeof definition.description === "string" && definition.description.length > 10, name + ": description too short");
  check(typeof definition.output === "object" && typeof definition.output.render === "function", name + ": output.render missing");
  checkSchema(definition.parameters, name + ".parameters");
  checkSchema(definition.output.schema, name + ".output.schema");
}

const sh = tools.get("wb_sh");
const shOk = await sh.execute({ command: "echo hi", description: "echo hi" }, { signal: undefined });
validateValue(sh.output.schema, shOk, "wb_sh.value");
const shRendered = sh.output.render({}, shOk);
check(shRendered[0].text.includes("hello from fake sh"), "wb_sh render must include stdout");
check(shRendered[0].text.includes("[exit code: 0]"), "wb_sh render must include exit code");

const shFail = await sh.execute({ command: "fail now", description: "fail now" }, { signal: undefined });
validateValue(sh.output.schema, shFail, "wb_sh.value(fail)");
check(shFail.exitCode === 3 && shFail.stderr.includes("fake failure"), "wb_sh must surface non-zero exit and stderr");

const env = tools.get("wb_env");
const envValue = await env.execute({}, {});
validateValue(env.output.schema, envValue, "wb_env.value");
check(envValue.summary.includes("Android 环境体检"), "wb_env summary must be a report");
check(envValue.shell === "/system/bin/sh", "wb_env must report the shell path");
check(Array.isArray(envValue.available) && envValue.available.length > 0, "wb_env must probe PATH");

const shizuku = tools.get("wb_shizuku");
const shizukuValue = await shizuku.execute({ command: "pm list packages", description: "list packages" }, {});
validateValue(shizuku.output.schema, shizukuValue, "wb_shizuku.value");
check(shizukuValue.ok === false, "wb_shizuku must fail closed without a bridge token");
check(shizukuValue.error.length > 0, "wb_shizuku must explain the failure");

const notify = tools.get("wb_notify");
const notifyValue = await notify.execute({ text: "hi" }, {});
validateValue(notify.output.schema, notifyValue, "wb_notify.value");

if (failures.length > 0) {
  console.error("\n自检失败（" + failures.length + "）：");
  for (const failure of failures) console.error(" - " + failure);
  process.exit(1);
}
console.log("\n自检通过：4 个工具 + 1 段环境手册，schema 与渲染均符合 DSH 约定。");
