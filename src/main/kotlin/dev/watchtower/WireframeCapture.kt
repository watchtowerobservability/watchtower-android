package dev.watchtower

import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

/** The View tree as wire nodes; Jetpack Compose content comes from its semantics tree when it can be read. */
internal object WireframeCapture {
    const val MAX_NODES = 1500

    fun nodes(root: View, cfg: ReplayConfig): List<WireNode> {
        val out = ArrayList<WireNode>()
        val rl = IntArray(2).also { root.getLocationOnScreen(it) }
        fun visit(v: View) {
            if (out.size >= MAX_NODES || v.visibility != View.VISIBLE || v.alpha < 0.01f) return
            val l = IntArray(2).also { v.getLocationOnScreen(it) }
            val tag = v.tag?.toString()
            val force = tag == "wt-mask"
            val k = kindOf(v)
            val hide = hidesText(k, cfg.maskText, unmask = tag == "wt-unmask", forceMask = force)
            val bg = (v.background as? ColorDrawable)?.let { hexColor(it.color) }
            val text = (v as? TextView)?.text?.toString()?.let { wireText(it, hide) }
            out += WireNode(System.identityHashCode(v), l[0] - rl[0], l[1] - rl[1], v.width, v.height, wireKind(k), bg, text)
            if (force) return // a hidden container shows no children
            if (v.javaClass.name.endsWith("AndroidComposeView")) out += ComposeSemantics.nodes(v, rl, cfg, MAX_NODES - out.size)
            (v as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) visit(g.getChildAt(i)) }
        }
        visit(root)
        return out
    }

    private fun kindOf(v: View): Kind = when {
        v is EditText && (v.inputType and 0x80 != 0 || v.inputType and 0x10 != 0) -> Kind.PASSWORD
        v is EditText -> Kind.INPUT
        v is TextView -> Kind.TEXT
        v is ImageView -> Kind.IMAGE
        else -> Kind.OTHER
    }
}

/**
 * Compose draws without Views, so its semantics tree stands in: `AndroidComposeView.semanticsOwner`
 * -> unmerged root node -> children, each with `boundsInWindow` and a config holding `Text`,
 * `EditableText`, `Password` and `ContentDescription`. Read by reflection so the SDK needs no
 * Compose dependency; any failure (a Compose version with other names) yields no nodes, never a crash.
 */
internal object ComposeSemantics {
    fun nodes(view: View, rootLoc: IntArray, cfg: ReplayConfig, budget: Int): List<WireNode> = try {
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view)
        val root = owner!!.javaClass.getMethod("getUnmergedRootSemanticsNode").invoke(owner)!!
        val vl = IntArray(2).also { view.getLocationOnScreen(it) }
        val out = ArrayList<WireNode>()
        walk(root, vl[0] - rootLoc[0], vl[1] - rootLoc[1], cfg, out, budget, 0)
        out
    } catch (_: Throwable) {
        emptyList()
    }

    private fun walk(node: Any, dx: Int, dy: Int, cfg: ReplayConfig, out: MutableList<WireNode>, budget: Int, depth: Int) {
        if (out.size >= budget || depth > 40) return
        val b = node.javaClass.getMethod("getBoundsInWindow").invoke(node)!!
        fun f(name: String) = (b.javaClass.getMethod(name).invoke(b) as Float).toInt()
        val props = props(node)
        val password = props.containsKey("Password")
        val editable = props["EditableText"]
        val text = editable ?: props["Text"] ?: props["ContentDescription"]
        val kind = when {
            password -> Kind.PASSWORD
            editable != null -> Kind.INPUT
            text != null -> Kind.TEXT
            else -> Kind.OTHER
        }
        val id = node.javaClass.getMethod("getId").invoke(node) as Int
        if (f("getRight") > f("getLeft") && f("getBottom") > f("getTop")) {
            out += WireNode(
                id xor 0x40000000, f("getLeft") + dx, f("getTop") + dy, f("getRight") - f("getLeft"), f("getBottom") - f("getTop"),
                wireKind(kind), null, text?.let { wireText(it, hidesText(kind, cfg.maskText)) },
            )
        }
        (node.javaClass.getMethod("getChildren").invoke(node) as List<*>).forEach { c -> c?.let { walk(it, dx, dy, cfg, out, budget, depth + 1) } }
    }

    /** `Text`, `EditableText`, `Password`, `ContentDescription` of a node's config as strings (a flag as ""). */
    private fun props(node: Any): Map<String, String> {
        val cfgObj = node.javaClass.getMethod("getConfig").invoke(node) as Iterable<*>
        val m = HashMap<String, String>()
        for (e in cfgObj) {
            val entry = e as Map.Entry<*, *>
            val key = entry.key!!
            val name = key.javaClass.getMethod("getName").invoke(key) as String
            if (name !in setOf("Text", "EditableText", "Password", "ContentDescription")) continue
            val v = entry.value
            m[name] = when (v) {
                is List<*> -> v.joinToString(" ") { textOf(it) }
                null -> ""
                else -> textOf(v)
            }
        }
        return m
    }

    /** An AnnotatedString's text, else the value's string. */
    private fun textOf(v: Any?): String = try {
        v!!.javaClass.getMethod("getText").invoke(v) as String
    } catch (_: Throwable) {
        v?.toString() ?: ""
    }
}
