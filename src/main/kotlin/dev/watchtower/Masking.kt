package dev.watchtower

/** What a view is, for masking. Kept apart from android.view so the rules run in plain JVM tests. */
enum class Kind { TEXT, INPUT, PASSWORD, IMAGE, OTHER }

class Node(
    val kind: Kind,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
    /** The app tagged this view `wt-unmask` (opt out of masking). */
    val unmask: Boolean = false,
    /** The app tagged this view `wt-mask` (always hide). */
    val forceMask: Boolean = false,
    val children: List<Node> = emptyList(),
)

data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Rectangles to paint over before a screenshot leaves the device. Default (maskText): all text
 * and all inputs. Password and `wt-mask` views are masked always. `wt-unmask` lifts the mask from a
 * text view only, never from an input or a password.
 */
fun maskRects(root: Node, maskText: Boolean = true): List<Rect> {
    val out = ArrayList<Rect>()
    fun walk(n: Node) {
        val hide = n.forceMask || n.kind == Kind.PASSWORD ||
            (n.kind == Kind.INPUT && (maskText || !n.unmask)) ||
            (n.kind == Kind.TEXT && maskText && !n.unmask)
        if (hide && n.w > 0 && n.h > 0) out.add(Rect(n.x, n.y, n.w, n.h)) else n.children.forEach(::walk)
    }
    walk(root)
    return out
}
