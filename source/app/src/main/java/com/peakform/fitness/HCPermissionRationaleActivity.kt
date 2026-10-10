package com.peakform.fitness

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * HCPermissionRationaleActivity — Health Connect requires a rationale activity for the
 * WRITE_EXERCISE permission declaration. Explains what Pro writes (sessions only, never reads).
 */
class HCPermissionRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val tv = TextView(this).apply {
            text = """
                Pro writes your completed workouts to Health Connect as strength-training sessions.

                Pro never reads any health data.
                Pro has no internet permission, so nothing leaves this device except through Health Connect's own on-device store.

                You can revoke access any time in the Health Connect system settings.
            """.trimIndent()
            textSize = 16f
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(tv) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll)
            setPadding(pad, pad, pad, pad)
        }
        setContentView(root)
    }
}
