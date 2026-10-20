package com.workbuddy2api.panel.data.agent

import kotlinx.serialization.Serializable

/**
 * 一条对话消息。
 *
 * - [role]：`user` / `assistant` / `tool`（system 提示词单独存在 [AgentSession.systemPrompt] 里，不占消息）；
 * - [content]：纯文本内容（流式输出时会被逐步追加）；
 * - [ts]：毫秒时间戳，0 表示未记录；
 * - [pending]：流式输出尚未结束，界面可以显示「正在输入」状态。
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val ts: Long = 0L,
    val pending: Boolean = false,
)

/**
 * 一个会话（含完整消息历史）。
 *
 * 由 [AgentStore] 统一持久化到 `filesDir/agent/sessions.json`；
 * 会话列表按 [updatedAt] 倒序展示。[messages] 用 MutableList，
 * 方便流式输出时就地追加/覆盖最后一条助手消息。
 */
@Serializable
data class AgentSession(
    val id: String,
    val title: String,
    val model: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messages: MutableList<ChatMessage> = mutableListOf(),
)

/** 模型下拉项：[label] 优先用服务端给的展示名，没有就退回 [id]。 */
@Serializable
data class ModelOption(val id: String, val label: String)
