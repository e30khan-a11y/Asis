package com.example.voiceagent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

data class InteractiveNode(
    val id: Int,
    val text: String,
    val className: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val nodeRef: AccessibilityNodeInfo
)

data class ScreenDump(
    val summaryJson: String,
    val nodeMap: Map<Int, InteractiveNode>
)

object AccessibilityTreeParser {

    fun parse(root: AccessibilityNodeInfo?): ScreenDump {
        val nodeMap = mutableMapOf<Int, InteractiveNode>()
        if (root == null) {
            return ScreenDump("[]", nodeMap)
        }

        var nextId = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val elementsList = mutableListOf<String>()

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").trim()
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isScrollable = node.isScrollable
            val className = node.className?.toString()?.substringAfterLast(".") ?: "View"

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Filter out invisible, off-screen, or empty un-clickable containers
            val isInteractive = isClickable || isEditable || isScrollable
            val hasContent = text.isNotEmpty()
            val isVisible = bounds.width() > 0 && bounds.height() > 0

            if (isVisible && (isInteractive || hasContent)) {
                val assignedId = nextId++
                val interactiveNode = InteractiveNode(
                    id = assignedId,
                    text = text,
                    className = className,
                    bounds = bounds,
                    isClickable = isClickable,
                    isEditable = isEditable,
                    nodeRef = node
                )
                nodeMap[assignedId] = interactiveNode

                val typeLabel = when {
                    isEditable -> "Input/TextField"
                    isClickable -> "Button/Clickable"
                    isScrollable -> "Scrollable"
                    else -> "Text"
                }

                val sanitizedText = text.replace("\"", "\\\"").replace("\n", " ")
                elementsList.add(
                    """{"id": $assignedId, "type": "$typeLabel", "text": "$sanitizedText", "bounds": [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]}"""
                )
            }

            // Enqueue children
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }

        val jsonOutput = "[\n  " + elementsList.joinToString(",\n  ") + "\n]"
        return ScreenDump(jsonOutput, nodeMap)
    }
}
