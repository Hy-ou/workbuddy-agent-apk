package com.workbuddy2api.panel.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/*
 * 集成方（Lead）需要把下面的片段加进 AndroidManifest.xml 的 <application> 内，
 * 否则本服务不会出现在系统「无障碍」列表里（本文件不修改 Manifest）：
 *
 * <service
 *     android:name=".agent.WbAccessibilityService"
 *     android:exported="true"
 *     android:label="@string/app_name"
 *     android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
 *     <intent-filter>
 *         <action android:name="android.accessibilityservice.AccessibilityService" />
 *     </intent-filter>
 *     <meta-data
 *         android:name="android.accessibilityservice"
 *         android:resource="@xml/wb_accessibility_config" />
 * </service>
 *
 * 说明：无需 <uses-permission>，BIND_ACCESSIBILITY_SERVICE 是签名级权限，
 * 只靠上面的 android:permission 声明即可；exported 必须为 true，系统才能绑定。
 */

/**
 * Agent 手机操作所使用的无障碍服务。
 *
 * 职责：把「读屏 / 点击 / 输入 / 滚动 / 返回 / 回桌面」这些原语包装成普通方法，
 * 供 [MobileTools] 调用；所有方法都返回 Boolean 或字符串，不抛异常。
 *
 * 使用前提：用户已在系统「设置 → 无障碍」里手动开启本服务（Manifest 由集成方注册），
 * 服务连接后 [instance] 才有值，[onServiceConnected] / [onDestroy] 负责维护它。
 *
 * 注意：
 * - 页面节点树用广度优先遍历，最多扫 [MAX_SCAN_NODES] 个节点，
 *   读屏结果最多 [MAX_DUMP_NODES] 条，避免 prompt 被控件淹掉；
 * - 节点不做 recycle（API 33 起官方已废弃该调用，且它并非必需）；
 * - 坐标点击优先用节点 ACTION_CLICK，找不到可点击节点时退回 [dispatchGesture] 手势点击
 *   （需要配置里的 `canPerformGestures`）。
 */
class WbAccessibilityService : AccessibilityService() {

    companion object {
        private const val MAX_DUMP_NODES = 60
        private const val MAX_SCAN_NODES = 400

        /** 服务实例：未连接（未授权 / 已销毁）时为 null。 */
        @Volatile
        var instance: WbAccessibilityService? = null
            private set
    }

    /** 最近一次无障碍事件里的前台包名，读不到 root 时的兜底。 */
    @Volatile
    private var lastPackage: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 无障碍事件回调：只记录前台包名，真正的操作都由 [MobileTools] 主动触发。 */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString().orEmpty()
        if (pkg.isNotEmpty()) lastPackage = pkg
    }

    override fun onInterrupt() = Unit

    // ── 读屏 ──────────────────────────────────────────────────────────

    /**
     * 生成紧凑的界面描述：前台应用包名 + 最多 [maxNodes] 条有文字/可操作的控件，
     * 每条包含文本、[可点击]/[可输入]/[可滚动] 标记与控件中心坐标。
     */
    fun dumpScreen(maxNodes: Int = MAX_DUMP_NODES): String {
        val root = rootNode() ?: return "无法读取当前界面（无障碍服务可能未获得窗口内容权限）"
        val pkg = root.packageName?.toString().orEmpty().ifBlank { activePackage() }
        val seen = HashSet<String>()
        val lines = ArrayList<String>(maxNodes)
        for (node in collectNodes(root)) {
            if (lines.size >= maxNodes) break
            val text = nodeLabel(node)
            if (text.isBlank()) continue
            val clickable = node.isClickable
            val editable = node.isEditable
            val scrollable = node.isScrollable
            // 同文本同标记的重复控件只保留一条（列表项经常重复）
            val key = "$text|$clickable|$editable|$scrollable"
            if (!seen.add(key)) continue
            val flags = buildString {
                if (clickable) append(" [可点击]")
                if (editable) append(" [可输入]")
                if (scrollable) append(" [可滚动]")
            }
            val center = boundsOf(node)?.let { " @${it.centerX()},${it.centerY()}" }.orEmpty()
            lines.add("${lines.size + 1}. $text$flags$center")
        }
        if (lines.isEmpty()) return "应用：${pkg.ifBlank { "未知" }}\n当前界面没有可识别的文字控件"
        return buildString {
            append("应用：").append(pkg.ifBlank { "未知" })
            append("\n控件（").append(lines.size).append(" 个）：\n")
            lines.forEach { append(it).append('\n') }
        }.trimEnd()
    }

    /** 当前前台应用包名（root 不可用时退回最近一次事件里的包名）。 */
    fun activePackage(): String {
        val fromRoot = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull().orEmpty()
        return fromRoot.ifBlank { lastPackage }
    }

    // ── 点击 ──────────────────────────────────────────────────────────

    /** 按文本 / 内容描述点击控件：先精确匹配，再包含匹配。 */
    fun clickText(text: String): Boolean {
        val target = text.trim()
        if (target.isEmpty()) return false
        val root = rootNode() ?: return false
        var fuzzy: AccessibilityNodeInfo? = null
        for (node in collectNodes(root)) {
            if (matchesExactly(node, target)) return clickNode(node)
            if (fuzzy == null && matchesLoosely(node, target)) fuzzy = node
        }
        val node = fuzzy ?: return false
        return clickNode(node)
    }

    /** 按屏幕坐标点击：优先找坐标所在的最深层可点击控件，找不到就用手势点击。 */
    fun clickPoint(x: Int, y: Int): Boolean {
        val root = rootNode()
        if (root != null) {
            var candidate: AccessibilityNodeInfo? = null
            for (node in collectNodes(root)) {
                val bounds = boundsOf(node) ?: continue
                if (!bounds.contains(x, y)) continue
                if (node.isClickable) candidate = node
            }
            if (candidate?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                return true
            }
        }
        return tapAt(x, y)
    }

    /** 手势点击（需要无障碍配置里的 `canPerformGestures`）。 */
    fun tapAt(x: Int, y: Int): Boolean = runCatching {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
            .build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    // ── 输入 ──────────────────────────────────────────────────────────

    /** 往当前聚焦（或第一个）输入框写入文字：ACTION_SET_TEXT 优先，失败退回剪贴板粘贴。 */
    fun typeText(text: String): Boolean {
        if (text.isEmpty()) return false
        val root = rootNode() ?: return false
        val node = findEditable(root) ?: return false
        runCatching { node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        // 兜底：Android 10+ 后台应用写剪贴板可能被系统拒绝，因此整段包在 runCatching 里
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        return runCatching {
            manager.setPrimaryClip(ClipData.newPlainText("wb_agent", text))
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }.getOrDefault(false)
    }

    // ── 滚动 / 全局动作 ──────────────────────────────────────────────

    /** 向下滚动一屏。 */
    fun scrollForward(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)

    /** 向上滚动一屏。 */
    fun scrollBackward(): Boolean = scroll(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

    /** 返回键。 */
    fun pressBack(): Boolean = runCatching {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }.getOrDefault(false)

    /** 回到桌面。 */
    fun pressHome(): Boolean = runCatching {
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }.getOrDefault(false)

    // ── 内部实现 ──────────────────────────────────────────────────────

    private fun scroll(action: Int): Boolean {
        val root = rootNode() ?: return false
        val scrollables = ArrayList<AccessibilityNodeInfo>()
        for (node in collectNodes(root)) {
            if (node.isScrollable) scrollables.add(node)
        }
        if (scrollables.isEmpty()) return false
        // 获得焦点的列表优先，其次按 BFS 顺序（越靠外层的容器越可能是主滚动区）
        scrollables.sortBy { if (it.isFocused) 0 else 1 }
        for (node in scrollables) {
            if (runCatching { node.performAction(action) }.getOrDefault(false)) return true
        }
        return false
    }

    private fun rootNode(): AccessibilityNodeInfo? =
        runCatching { rootInActiveWindow }.getOrNull()

    /** 广度优先收集节点（浅层控件优先，读屏结果更贴近用户看到的内容）。 */
    private fun collectNodes(root: AccessibilityNodeInfo, maxNodes: Int = MAX_SCAN_NODES): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>(64)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val node = queue.removeFirst()
            out.add(node)
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (index in 0 until count) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                queue.addLast(child)
            }
        }
        return out
    }

    /** 控件的可读文字：优先 text，其次 contentDescription（图标按钮通常只有后者）。 */
    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun matchesExactly(node: AccessibilityNodeInfo, target: String): Boolean {
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        return text.equals(target, ignoreCase = true) || desc.equals(target, ignoreCase = true)
    }

    private fun matchesLoosely(node: AccessibilityNodeInfo, target: String): Boolean {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        return text.contains(target, ignoreCase = true) || desc.contains(target, ignoreCase = true)
    }

    /** 点击节点；自身不可点时向上找最近的祖先（列表项常把点击挂在父容器上）。 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable &&
                runCatching { candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            ) {
                return true
            }
            current = runCatching { candidate.parent }.getOrNull()
        }
        val bounds = boundsOf(node) ?: return false
        return tapAt(bounds.centerX(), bounds.centerY())
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var fallback: AccessibilityNodeInfo? = null
        for (node in collectNodes(root)) {
            if (!node.isEditable) continue
            if (node.isFocused) return node
            if (fallback == null) fallback = node
        }
        return fallback
    }

    private fun boundsOf(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        val ok = runCatching { node.getBoundsInScreen(rect) }.isSuccess
        if (!ok || rect.isEmpty) return null
        return rect
    }
}
