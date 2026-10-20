package com.workbuddy2api.panel.dsh

/** 精选社区插件清单（来源：Soodok/Deepseek-Harness-Local-Android）。 */
object CommunityPlugins {

    data class Entry(val id: String, val packageName: String, val desc: String)

    val CATALOG: List<Entry> = listOf(
        Entry("cost-meter", "dsh-cost-meter", "会话费用统计：本会话成本、当日费用、历史记录"),
        Entry("free-search", "dsh-free-search", "免费网页搜索：24 个搜索引擎，无需 API Key"),
        Entry("whale-widget", "dsh-whale-widget", "余额小鲸鱼挂件：余额/今日已用/峰谷定价"),
        Entry("mcp-connector", "dsh-mcp-connector", "MCP 连接器：接入 Model Context Protocol 服务器"),
        Entry("im", "@xmanrui/dsh-im", "把微信、飞书、企微、钉钉、QQ、Telegram 接入 DSH"),
        Entry("memory", "@openviking/dsh-memory-plugin", "记忆与上下文管理：跨会话记忆"),
        Entry("vision-router", "dsh-vision-router", "视觉路由：给纯文本 Agent 加图像理解"),
        Entry("genui", "@changfenhuang/dsh-genui", "对话中渲染交互式 UI 组件"),
        Entry("rewind", "dsh-rewind-plugin", "会话回滚：回退对话并还原工作区文件"),
        Entry("pet", "dsh-pet", "桌面宠物：赖在 DSH 里不走了"),
        Entry("dream-skin", "dsh-dream-skin", "WebUI 换肤：8 套 iOS / Linear 风格主题"),
        Entry("skills-manager", "@michengai/dsh-skills-manager", "Skills 统一加载与安全管理"),
    )
}
