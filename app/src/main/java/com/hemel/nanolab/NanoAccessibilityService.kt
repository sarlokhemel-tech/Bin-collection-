package com.hemel.nanolab

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NanoAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: NanoAccessibilityService? = null

        fun treesDir(base: File): File = File(base, "trees").apply { mkdirs() }
    }

    private var overlay: View? = null
    private var saveLabel: TextView? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideOverlay()
        instance = null
        super.onDestroy()
    }

    fun isOverlayShown(): Boolean = overlay != null

    fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density

        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        val bg = GradientDrawable()
        bg.cornerRadius = 28f * d
        bg.setColor(0xE6212121.toInt())
        container.background = bg
        container.setPadding((6 * d).toInt(), (4 * d).toInt(), (6 * d).toInt(), (4 * d).toInt())

        fun makeBtn(label: String): TextView {
            val tv = TextView(this)
            tv.text = label
            tv.setTextColor(Color.WHITE)
            tv.textSize = 16f
            tv.setPadding((12 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
            return tv
        }

        val save = makeBtn("💾 সেভ")
        val home = makeBtn("📱 অ্যাপ")
        val close = makeBtn("✕")
        container.addView(save)
        container.addView(home)
        container.addView(close)
        saveLabel = save

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (24 * d).toInt()
        lp.y = (240 * d).toInt()

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        container.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (Math.abs(dx) > 12 * d || Math.abs(dy) > 12 * d) moved = true
                    if (moved) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        try { wm.updateViewLayout(container, lp) } catch (e: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val x = ev.x
                        if (x >= close.left) {
                            hideOverlay()
                        } else if (x >= home.left) {
                            openApp()
                        } else {
                            saveTree()
                        }
                    }
                }
            }
            true
        }

        try {
            wm.addView(container, lp)
            overlay = container
        } catch (e: Exception) {
            Toast.makeText(this, "ফ্লোটিং বাটন দেখানো যায়নি: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun hideOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (e: Exception) {
        }
        overlay = null
        saveLabel = null
    }

    private fun openApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName) ?: return
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun findRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow
        if (r != null) return r
        for (w in windows) {
            val wr = w.root
            if (wr != null) return wr
        }
        return null
    }

    private var nodeCount = 0

    private fun dumpNode(n: AccessibilityNodeInfo, depth: Int, sb: StringBuilder) {
        if (nodeCount >= 6000 || depth > 80) return
        nodeCount++
        val r = Rect()
        n.getBoundsInScreen(r)
        sb.append("  ".repeat(depth))
        sb.append(n.className ?: "?")
        n.viewIdResourceName?.let { sb.append(" id=").append(it) }
        n.text?.let { sb.append(" text=\"").append(it.toString().replace("\n", "\\n")).append('"') }
        n.contentDescription?.let { sb.append(" desc=\"").append(it.toString().replace("\n", "\\n")).append('"') }
        n.hintText?.let { sb.append(" hint=\"").append(it.toString().replace("\n", "\\n")).append('"') }
        sb.append(" bounds=[").append(r.left).append(',').append(r.top).append("][")
            .append(r.right).append(',').append(r.bottom).append(']')
        val flags = ArrayList<String>()
        if (n.isClickable) flags.add("clickable")
        if (n.isLongClickable) flags.add("long-clickable")
        if (n.isScrollable) flags.add("scrollable")
        if (n.isEditable) flags.add("editable")
        if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
        if (n.isFocusable) flags.add("focusable")
        if (n.isFocused) flags.add("focused")
        if (n.isSelected) flags.add("selected")
        if (!n.isEnabled) flags.add("disabled")
        if (!n.isVisibleToUser) flags.add("invisible")
        if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append('}')
        sb.append('\n')
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            dumpNode(c, depth + 1, sb)
        }
    }

    private fun saveTree() {
        val root = findRoot()
        if (root == null) {
            Toast.makeText(this, "স্ক্রিনের ডেটা পাওয়া যায়নি, আবার চেষ্টা করুন", Toast.LENGTH_SHORT).show()
            return
        }
        val pkg = root.packageName?.toString() ?: "unknown"
        val now = Date()
        val sb = StringBuilder()
        sb.append("# Accessibility Tree\n")
        sb.append("# package: ").append(pkg).append('\n')
        sb.append("# time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)).append('\n')
        val dm = resources.displayMetrics
        sb.append("# screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append("\n\n")
        nodeCount = 0
        dumpNode(root, 0, sb)
        sb.append("\n# nodes: ").append(nodeCount).append('\n')

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now) + "_" + pkg + ".txt"
        try {
            File(treesDir(filesDir), name).writeText(sb.toString())
            saveLabel?.text = "✓ সেভ হয়েছে"
            saveLabel?.postDelayed({ saveLabel?.text = "💾 সেভ" }, 1500)
            Toast.makeText(this, "সেভ হয়েছে ($nodeCount নোড) — NanoLab অ্যাপে গিয়ে কপি করুন", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "সেভ ব্যর্থ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
