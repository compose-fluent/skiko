package org.jetbrains.skiko.winui

internal object WinUIAccessibilityDiff {
    fun changes(
        oldSnapshot: WinUIAccessibilitySnapshot?,
        newSnapshot: WinUIAccessibilitySnapshot?,
    ): List<WinUIAccessibilityChange> {
        if (oldSnapshot == null && newSnapshot == null) {
            return emptyList()
        }
        if (oldSnapshot == null) {
            return listOf(
                WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.STRUCTURE_CHANGED,
                    nodeId = newSnapshot?.root?.id,
                )
            )
        }
        if (newSnapshot == null) {
            return listOf(
                WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.STRUCTURE_CHANGED,
                    nodeId = oldSnapshot.root.id,
                )
            )
        }

        val oldNodes = oldSnapshot.root.flattenById()
        val newNodes = newSnapshot.root.flattenById()
        val changes = mutableListOf<WinUIAccessibilityChange>()

        if (oldSnapshot.focusedNodeId != newSnapshot.focusedNodeId) {
            changes += WinUIAccessibilityChange(
                type = WinUIAccessibilityChangeType.FOCUS_CHANGED,
                nodeId = newSnapshot.focusedNodeId,
                oldValue = oldSnapshot.focusedNodeId?.toString(),
                newValue = newSnapshot.focusedNodeId?.toString(),
            )
        }

        for ((nodeId, oldNode) in oldNodes) {
            val newNode = newNodes[nodeId] ?: continue
            if (oldNode.children.map(WinUIAccessibilityNode::id) != newNode.children.map(WinUIAccessibilityNode::id)) {
                changes += WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.STRUCTURE_CHANGED,
                    nodeId = nodeId,
                )
            }
            if (oldNode.value != newNode.value) {
                changes += WinUIAccessibilityChange(
                    type = oldNode.valueChangeType(newNode),
                    nodeId = nodeId,
                    oldValue = oldNode.value,
                    newValue = newNode.value,
                )
            }
            if (oldNode.liveRegionPayload() != newNode.liveRegionPayload()) {
                changes += WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.LIVE_REGION_CHANGED,
                    nodeId = nodeId,
                    oldValue = oldNode.liveRegionPayload(),
                    newValue = newNode.liveRegionPayload(),
                )
            }
            if (oldNode.withoutChildren() != newNode.withoutChildren()) {
                changes += WinUIAccessibilityChange(
                    type = WinUIAccessibilityChangeType.NODE_UPDATED,
                    nodeId = nodeId,
                )
            }
        }

        for (nodeId in oldNodes.keys - newNodes.keys) {
            changes += WinUIAccessibilityChange(
                type = WinUIAccessibilityChangeType.NODE_REMOVED,
                nodeId = nodeId,
            )
        }
        for (nodeId in newNodes.keys - oldNodes.keys) {
            changes += WinUIAccessibilityChange(
                type = WinUIAccessibilityChangeType.NODE_ADDED,
                nodeId = nodeId,
            )
        }

        return changes.distinct()
    }
}

private fun WinUIAccessibilityNode.flattenById(): Map<Long, WinUIAccessibilityNode> {
    val nodes = linkedMapOf<Long, WinUIAccessibilityNode>()

    fun visit(node: WinUIAccessibilityNode) {
        if (node.id !in nodes) {
            nodes[node.id] = node
        }
        node.children.forEach(::visit)
    }

    visit(this)
    return nodes
}

private fun WinUIAccessibilityNode.withoutChildren(): WinUIAccessibilityNode =
    copy(children = emptyList())

private fun WinUIAccessibilityNode.valueChangeType(newNode: WinUIAccessibilityNode): WinUIAccessibilityChangeType =
    if (isTextLike() || newNode.isTextLike()) {
        WinUIAccessibilityChangeType.TEXT_CHANGED
    } else {
        WinUIAccessibilityChangeType.VALUE_CHANGED
    }

private fun WinUIAccessibilityNode.isTextLike(): Boolean =
    info.role == WinUIAccessibilityRole.TEXT ||
        info.role == WinUIAccessibilityRole.EDIT ||
        state.editable

private fun WinUIAccessibilityNode.liveRegionPayload(): String =
    if (info.liveSetting == WinUIAccessibilityLiveSetting.OFF) {
        ""
    } else {
        "${info.liveSetting}|${info.name}|$value|${state.enabled}|${state.focused}"
    }
