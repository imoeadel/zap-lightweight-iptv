package com.moe.zap.ui

import android.app.Activity
import android.app.Dialog
import android.view.KeyEvent
import android.view.LayoutInflater
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.moe.zap.R
import com.moe.zap.data.Lock

/** A four-digit PIN pad for a remote. Number keys on the remote work too. */
object PinDialog {
    fun verify(activity: Activity, title: String, onOk: () -> Unit) {
        show(activity, title) { pin, dialog, message ->
            if (Lock.checkPin(pin)) {
                dialog.dismiss()
                onOk()
            } else {
                message.text = "Wrong PIN. Try again."
            }
        }
    }

    /** Chooses a new PIN, asking for it twice. */
    fun create(activity: Activity, onOk: () -> Unit) {
        var first: String? = null
        show(activity, "Choose a 4-digit PIN") { pin, dialog, message ->
            val before = first
            when {
                before == null -> {
                    first = pin
                    message.text = "Enter it once more"
                }
                before == pin -> {
                    Lock.setPin(pin)
                    dialog.dismiss()
                    onOk()
                }
                else -> {
                    first = null
                    message.text = "Those didn't match. Choose a PIN."
                }
            }
        }
    }

    private fun show(activity: Activity, title: String, onEntered: (String, Dialog, TextView) -> Unit) {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val ctx = activity
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(ctx.dp(24), ctx.dp(20), ctx.dp(24), ctx.dp(20))
        }
        val message = TextView(ctx).apply {
            text = title
            setTextColor(ctx.getColor(R.color.text))
            textSize = 16f
        }
        val dots = TextView(ctx).apply {
            setTextColor(ctx.getColor(R.color.accent))
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            minHeight = ctx.dp(40)
        }
        val grid = GridLayout(ctx).apply { columnCount = 3 }
        var pin = ""
        fun press(d: String) {
            pin += d
            dots.text = "●".repeat(pin.length)
            if (pin.length == 4) {
                val entered = pin
                pin = ""
                dots.text = ""
                onEntered(entered, dialog, message)
            }
        }
        val inflater = LayoutInflater.from(ctx)
        for (label in listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "")) {
            val key = inflater.inflate(R.layout.key, grid, false) as TextView
            key.text = label
            val lp = GridLayout.LayoutParams()
            lp.width = ctx.dp(56)
            lp.height = ctx.dp(40)
            lp.setMargins(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
            key.layoutParams = lp
            when (label) {
                "" -> key.visibility = android.view.View.INVISIBLE
                "⌫" -> key.setOnClickListener {
                    pin = pin.dropLast(1)
                    dots.text = "●".repeat(pin.length)
                }
                else -> key.setOnClickListener { press(label) }
            }
            grid.addView(key)
        }
        root.addView(message)
        root.addView(dots)
        root.addView(grid)
        dialog.setContentView(root)
        dialog.setOnKeyListener { _, code, e ->
            if (e.action == KeyEvent.ACTION_DOWN && code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                press((code - KeyEvent.KEYCODE_0).toString())
                true
            } else {
                false
            }
        }
        dialog.show()
        grid.getChildAt(4).requestFocus()
    }
}
