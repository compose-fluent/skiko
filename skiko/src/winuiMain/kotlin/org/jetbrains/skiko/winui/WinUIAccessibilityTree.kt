package org.jetbrains.skiko.winui

internal class WinUIAccessibilityTree private constructor(
    val snapshot: WinUIAccessibilitySnapshot,
    private val nodesById: Map<Long, WinUIAccessibilityNode>,
    private val parentsById: Map<Long, WinUIAccessibilityNode>,
    private val nodesInTraversalOrder: List<WinUIAccessibilityNode>,
    val duplicateNodeIds: Set<Long>,
) {
    val root: WinUIAccessibilityNode
        get() = snapshot.root

    val focusedNode: WinUIAccessibilityNode?
        get() = snapshot.focusedNodeId?.let(::findNode)

    fun findNode(nodeId: Long): WinUIAccessibilityNode? =
        nodesById[nodeId]

    fun parentOf(nodeId: Long): WinUIAccessibilityNode? =
        parentsById[nodeId]

    fun childrenOf(nodeId: Long): List<WinUIAccessibilityNode> =
        findNode(nodeId)?.children ?: emptyList()

    fun hitTest(x: Float, y: Float): WinUIAccessibilityNode? =
        root.deepestAt(x, y)

    fun focusTarget(direction: WinUIAccessibilityFocusDirection): WinUIAccessibilityNode? {
        val focusableNodes = nodesInTraversalOrder.filter(WinUIAccessibilityNode::isFocusable)
        return when (direction) {
            WinUIAccessibilityFocusDirection.FIRST -> focusableNodes.firstOrNull()
            WinUIAccessibilityFocusDirection.LAST -> focusableNodes.lastOrNull()
            WinUIAccessibilityFocusDirection.NEXT -> focusableNodes.nextAfter(snapshot.focusedNodeId)
            WinUIAccessibilityFocusDirection.PREVIOUS -> focusableNodes.previousBefore(snapshot.focusedNodeId)
            WinUIAccessibilityFocusDirection.PARENT -> focusedNode
                ?.let { parentOf(it.id) }
                ?.takeIf(WinUIAccessibilityNode::isFocusable)
            WinUIAccessibilityFocusDirection.FIRST_CHILD -> focusedNode
                ?.children
                ?.firstOrNull(WinUIAccessibilityNode::isFocusable)
        }
    }

    companion object {
        fun build(snapshot: WinUIAccessibilitySnapshot): WinUIAccessibilityTree {
            val nodesById = linkedMapOf<Long, WinUIAccessibilityNode>()
            val parentsById = linkedMapOf<Long, WinUIAccessibilityNode>()
            val nodesInTraversalOrder = mutableListOf<WinUIAccessibilityNode>()
            val duplicateNodeIds = linkedSetOf<Long>()

            fun visit(node: WinUIAccessibilityNode, parent: WinUIAccessibilityNode?) {
                if (node.id in nodesById) {
                    duplicateNodeIds += node.id
                } else {
                    nodesById[node.id] = node
                }
                if (parent != null && node.id !in parentsById) {
                    parentsById[node.id] = parent
                }
                nodesInTraversalOrder += node
                node.children.forEach { child ->
                    visit(child, node)
                }
            }

            visit(snapshot.root, parent = null)
            return WinUIAccessibilityTree(
                snapshot = snapshot,
                nodesById = nodesById,
                parentsById = parentsById,
                nodesInTraversalOrder = nodesInTraversalOrder,
                duplicateNodeIds = duplicateNodeIds,
            )
        }
    }
}

private fun WinUIAccessibilityNode.deepestAt(x: Float, y: Float): WinUIAccessibilityNode? {
    if (!bounds.contains(x, y)) {
        return null
    }
    for (index in children.indices.reversed()) {
        children[index].deepestAt(x, y)?.let { return it }
    }
    return this
}

private fun WinUIAccessibilityNode.isFocusable(): Boolean =
    state.enabled && state.focusable

private fun List<WinUIAccessibilityNode>.nextAfter(focusedNodeId: Long?): WinUIAccessibilityNode? {
    if (isEmpty()) {
        return null
    }
    val focusedIndex = indexOfFirst { it.id == focusedNodeId }
    return if (focusedIndex < 0 || focusedIndex == lastIndex) {
        first()
    } else {
        this[focusedIndex + 1]
    }
}

private fun List<WinUIAccessibilityNode>.previousBefore(focusedNodeId: Long?): WinUIAccessibilityNode? {
    if (isEmpty()) {
        return null
    }
    val focusedIndex = indexOfFirst { it.id == focusedNodeId }
    return if (focusedIndex <= 0) {
        last()
    } else {
        this[focusedIndex - 1]
    }
}

private fun WinUIRect.contains(x: Float, y: Float): Boolean =
    x >= this.x &&
        y >= this.y &&
        x < this.x + width &&
        y < this.y + height
