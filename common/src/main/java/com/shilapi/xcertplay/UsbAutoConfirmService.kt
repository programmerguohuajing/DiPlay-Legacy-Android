package com.shilapi.xcertplay

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * An accessibility service to automatically check "Always allow / Use by default"
 * and click "OK / Confirm" when the Android system USB permission dialog appears for DiPlay.
 */
class UsbAutoConfirmService : AccessibilityService() {

    private var lastClickTime = -DEBOUNCE_MILLIS
    private var usbWindowId: Int? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            usbWindowId = if (isSystemUsbWindow(event.packageName?.toString(), event.className?.toString())) {
                event.windowId
            } else null
        }
        if (usbWindowId != event.windowId) return
        val now = SystemClock.uptimeMillis()
        if (now - lastClickTime < DEBOUNCE_MILLIS) return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        try {
            if (root.windowId != usbWindowId || root.packageName?.toString() !in SYSTEM_PACKAGES) return
            val texts = mutableListOf<String>()
            visit(root) { node ->
                node.text?.toString()?.let(texts::add)
                node.contentDescription?.toString()?.let(texts::add)
                false
            }
            val appLabel = applicationInfo.loadLabel(packageManager).toString()
            if (!isTargetPrompt(texts.joinToString(" "), appLabel)) return
            // Check optional "always allow / default" checkbox on legacy & modern ROMs
            visit(root) { node ->
                val className = node.className?.toString().orEmpty()
                val isCheckBox = node.isCheckable || className.contains("CheckBox", ignoreCase = true)
                isCheckBox && !node.isChecked && node.isEnabled &&
                    (node.viewIdResourceName == "android:id/alwaysUse" ||
                        node.viewIdResourceName?.endsWith(":id/alwaysUse") == true ||
                        node.viewIdResourceName?.endsWith(":id/check") == true ||
                        node.isCheckable) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            val confirmed = visit(root) { node ->
                val label = node.text?.toString()?.trim()
                node.isEnabled && node.isClickable &&
                    (node.viewIdResourceName == "android:id/button1" ||
                        node.viewIdResourceName?.endsWith(":id/button1") == true ||
                        (label != null && CONFIRM_LABELS.any { it.equals(label, ignoreCase = true) })) &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            if (confirmed) {
                lastClickTime = now
                Log.i(TAG, "Successfully auto-confirmed DiPlay USB/VPN permission dialog")
            }
        } finally {
            @Suppress("DEPRECATION")
            root.recycle()
        }
    }

    override fun onInterrupt() { usbWindowId = null }

    private fun visit(node: AccessibilityNodeInfo, depth: Int = 0, action: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (depth > 32) return false
        if (action(node)) return true
        for (i in 0 until node.childCount) {
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                if (visit(child, depth + 1, action)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    companion object {
        private const val TAG = "UsbAutoConfirm"
        private const val DEBOUNCE_MILLIS = 800L
        private val SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.android.settings",
            "com.android.vpndialogs",
        )
        private val USB_ACTIVITIES = setOf(
            "com.android.systemui.usb.UsbPermissionActivity",
            "com.android.systemui.usb.UsbConfirmActivity",
            "com.android.systemui.usb.UsbResolverActivity",
            "com.android.settings.usb.UsbPermissionActivity",
            "com.android.vpndialogs.ConfirmDialog",
        )
        private val CONFIRM_LABELS = setOf(
            "确定", "允许", "确认", "同意", "授权", "总是", "始终", "好的",
            "OK", "Allow", "Confirm", "Yes", "Grant", "Authorize", "Connect", "Turn on", "Always"
        )

        internal fun isSystemUsbWindow(pkg: String?, className: String?): Boolean =
            pkg in SYSTEM_PACKAGES && className in USB_ACTIVITIES

        internal fun isTargetPrompt(text: String, appLabel: String): Boolean {
            if (appLabel.isBlank()) return false
            val hasAppRef = Regex("(?<![\\p{L}\\p{N}_])${Regex.escape(appLabel)}(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
            val hasTopic = text.contains("USB", ignoreCase = true) ||
                text.contains("VPN", ignoreCase = true) ||
                text.contains("网络连接请求", ignoreCase = true) ||
                text.contains("Connection request", ignoreCase = true)
            return hasAppRef && hasTopic
        }


        fun isEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServices)
            val myService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToString()
            val myShortService = ComponentName(context, UsbAutoConfirmService::class.java).flattenToShortString()
            while (colonSplitter.hasNext()) {
                val componentName = colonSplitter.next()
                if (componentName.equals(myService, ignoreCase = true) ||
                    componentName.equals(myShortService, ignoreCase = true)
                ) {
                    return true
                }
            }
            return false
        }

        fun openSettings(context: Context): Boolean = runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
