package com.han2.app

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class SendButtonTarget(val node: AccessibilityNodeInfo, val bounds: Rect)

fun interface SendButtonDetector {
    fun find(root: AccessibilityNodeInfo): SendButtonTarget?
}

class DefaultSendButtonDetector(
    private val density: Float,
    private val adapters: List<AppAdapter> = listOf(KakaoTalkAdapter(), SamsungMessagesAdapter())
) : SendButtonDetector {
    override fun find(root: AccessibilityNodeInfo): SendButtonTarget? {
        val packageName = root.packageName?.toString().orEmpty()
        adapters.firstOrNull { it.supports(packageName) }?.find(root, density)?.let { return it }
        return findRecursively(root) { node -> isGenericSendButton(node, density) }
    }
}

interface AppAdapter {
    fun supports(packageName: String): Boolean
    fun find(root: AccessibilityNodeInfo, density: Float): SendButtonTarget?
}

class KakaoTalkAdapter : AppAdapter {
    override fun supports(packageName: String) = packageName == "com.kakao.talk"

    override fun find(root: AccessibilityNodeInfo, density: Float): SendButtonTarget? =
        findRecursively(root) { node ->
            val id = node.viewIdResourceName.orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            node.isClickable && node.isEnabled && validButtonBounds(node, density) &&
                (id.endsWith(":id/send_button_layout") || description == "전송")
        }
}

class SamsungMessagesAdapter : AppAdapter {
    override fun supports(packageName: String) = packageName == "com.samsung.android.messaging"

    override fun find(root: AccessibilityNodeInfo, density: Float): SendButtonTarget? =
        findRecursively(root) { node ->
            node.isClickable && node.isEnabled &&
                node.viewIdResourceName.orEmpty().endsWith(":id/send_button") &&
                validButtonBounds(node, density)
        }
}

private val sendMarkers = listOf(
    "send", "send message", "send sms", "send mms", "compose_send", "send_button",
    "전송", "보내기", "메시지 보내기", "전송 버튼"
)

private fun isGenericSendButton(node: AccessibilityNodeInfo, density: Float): Boolean {
    if (!node.isClickable || !node.isEnabled || !validButtonBounds(node, density)) return false
    return hasSendMarker(nodeDescriptor(node)) || descendantHasSendMarker(node, 3)
}

private fun nodeDescriptor(node: AccessibilityNodeInfo): String =
    listOfNotNull(node.text, node.contentDescription, node.viewIdResourceName)
        .joinToString(" ").lowercase()

private fun hasSendMarker(descriptor: String): Boolean =
    descriptor.contains("전송") || descriptor.contains("보내기") ||
        descriptor.contains("메시지 보내기") || sendMarkers.any(descriptor::contains)

/**
 * Compose and some custom view trees put the send semantics on a non-clickable
 * icon below a clickable parent. Inspect a small descendant depth and return
 * the clickable parent's bounds/action so both interception and ACTION_CLICK work.
 */
private fun descendantHasSendMarker(node: AccessibilityNodeInfo, remainingDepth: Int): Boolean {
    if (remainingDepth <= 0) return false
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        val matches = hasSendMarker(nodeDescriptor(child)) ||
            descendantHasSendMarker(child, remainingDepth - 1)
        child.recycle()
        if (matches) return true
    }
    return false
}

private fun validButtonBounds(node: AccessibilityNodeInfo, density: Float): Boolean {
    val bounds = Rect().also(node::getBoundsInScreen)
    return !bounds.isEmpty && bounds.width() in 24..(144 * density).toInt() &&
        bounds.height() in 24..(144 * density).toInt()
}

private fun findRecursively(
    node: AccessibilityNodeInfo,
    predicate: (AccessibilityNodeInfo) -> Boolean
): SendButtonTarget? {
    if (predicate(node)) {
        val bounds = Rect().also(node::getBoundsInScreen)
        return SendButtonTarget(AccessibilityNodeInfo.obtain(node), bounds)
    }
    for (index in 0 until node.childCount) {
        val child = node.getChild(index) ?: continue
        val result = findRecursively(child, predicate)
        child.recycle()
        if (result != null) return result
    }
    return null
}
