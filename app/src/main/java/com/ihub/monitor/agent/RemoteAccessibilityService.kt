package com.ihub.monitor.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent

class RemoteAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 0 does not inspect UI tree. Service is used only for gestures.
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()

        return dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    ensureSoftKeyboardForFocusedInput()
                }
            },
            null
        )
    }

    fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    fun setText(text: String): Boolean {
        val focusedNode = findFocusedEditableNode() ?: return false
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val success = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                ensureSoftKeyboardForFocusedInput()
            }
            return success
        }

        return false
    }

    private fun ensureSoftKeyboardForFocusedInput() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            softKeyboardController.showMode = SHOW_MODE_AUTO
        }

        Handler(Looper.getMainLooper()).postDelayed({
            val focusedNode = findFocusedEditableNode() ?: return@postDelayed
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }, 120)
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val directFocus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (directFocus != null && isEditableNode(directFocus)) {
            return directFocus
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            windows
                .asSequence()
                .mapNotNull { it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                .firstOrNull { isEditableNode(it) }
                ?.let { return it }

            windows
                .asSequence()
                .mapNotNull { it.root }
                .mapNotNull { findFirstEditableNode(it) }
                .firstOrNull()
                ?.let { return it }
        }

        return rootInActiveWindow?.let(::findFirstEditableNode)
    }

    private fun findFirstEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (isEditableNode(node)) {
            return node
        }

        repeat(node.childCount) { index ->
            val child = node.getChild(index) ?: return@repeat
            val editableChild = findFirstEditableNode(child)
            if (editableChild != null) {
                return editableChild
            }
        }

        return null
    }

    private fun isEditableNode(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isEditable || className.contains("EditText", ignoreCase = true)
    }

    companion object {
        @Volatile
        private var instance: RemoteAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        fun performTap(x: Int, y: Int): Boolean = instance?.tap(x.toFloat(), y.toFloat()) ?: false

        fun performSetText(text: String): Boolean = instance?.setText(text) ?: false

        fun performSwipe(
            startX: Int,
            startY: Int,
            endX: Int,
            endY: Int,
            durationMs: Int
        ): Boolean = instance?.swipe(
            startX.toFloat(),
            startY.toFloat(),
            endX.toFloat(),
            endY.toFloat(),
            durationMs.toLong()
        ) ?: false
    }
}
