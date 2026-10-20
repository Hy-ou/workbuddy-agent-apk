package com.workbuddy2api.panel.dsh

import java.io.File

/**
 * 宿主内置的 DSH 插件：`notify` —— 让 DSH 的 Agent 能发手机通知。
 *
 * 实现方式：把插件包写进内核 node_modules，插件执行时 POST 到宿主回环桥
 * （127.0.0.1:7865，token 由 DSHManager 通过环境变量注入），由 App 负责真正发通知。
 */
object NotifyPlugin {

    const val ID = "wb-notify"
    const val PACKAGE = "@workbuddy/dsh-tool-notify"
    private const val VERSION = "1.0.0"

    private val INDEX_JS = """
import { defineTool } from "@deepseek-ai/dsh-tools";

export const name = "tool-notify";
export const inject = ["tools"];

const BRIDGE = "http://127.0.0.1:7865/notify";

async function post(title, text) {
  const token = process.env.WB_BRIDGE_TOKEN || "";
  const res = await fetch(BRIDGE, {
    method: "POST",
    headers: { "content-type": "application/json", "x-wb-token": token },
    body: JSON.stringify({ title, text }),
  });
  const body = await res.text();
  if (!res.ok) throw new Error("notify failed: " + res.status + " " + body);
  return body;
}

export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: "notify",
    description: "Push a notification to the user's phone notification shade. Use when a long task finishes or the user's attention is needed.",
    parameters: {
      title: { type: "string", description: "Short notification title." },
      text: { type: "string", required: true, description: "Notification body text." },
    },
    output: {
      schema: {
        type: "object",
        additionalProperties: false,
        properties: { ok: { type: "boolean", required: true } },
      },
      render: (_args, value) => [{ type: "text", text: JSON.stringify(value) }],
    },
    async execute(args) {
      await post(args.title || "WorkBuddy", args.text);
      return { ok: true };
    },
  }));
}
"""

    /** 把插件包写入内核 node_modules；返回是否就绪。 */
    fun ensure(kernelDir: File): Boolean = runCatching {
        val pkgDir = packageDir(kernelDir)
        val index = File(pkgDir, "index.js")
        val pkgJson = File(pkgDir, "package.json")
        if (index.isFile && pkgJson.isFile && index.readText() == INDEX_JS.trimIndent() + "\n") return true
        pkgDir.mkdirs()
        index.writeText(INDEX_JS.trimIndent() + "\n")
        pkgJson.writeText(
            """
            {
              "name": "$PACKAGE",
              "version": "$VERSION",
              "type": "module",
              "main": "index.js",
              "peerDependencies": { "@deepseek-ai/dsh-tools": "*" }
            }
            """.trimIndent() + "\n",
        )
        true
    }.getOrDefault(false)

    fun packageDir(kernelDir: File): File =
        File(kernelDir, "dshroot/lib/node_modules/@deepseek-ai/dsh/node_modules/@workbuddy/dsh-tool-notify")
}
