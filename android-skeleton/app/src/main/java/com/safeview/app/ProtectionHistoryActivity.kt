package com.safeview.app

import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ProtectionHistoryActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = SafeViewMediaDatabase(this)
        val counts = db.counts()
        val events = db.recentEvents()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(16, 24, 39))
        }
        root.addView(TextView(this).apply {
            text = "Protection history"
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "${counts["media"] ?: 0} media scanned  ·  ${counts["blocked"] ?: 0} blocks recorded"
            setTextColor(Color.rgb(174, 184, 202))
            textSize = 14f
            setPadding(0, 8, 0, 24)
        })
        if (events.isEmpty()) {
            root.addView(TextView(this).apply {
                text = "No protection events yet. SafeView stores event metadata only; it does not save blocked images or videos."
                setTextColor(Color.rgb(174, 184, 202))
                textSize = 15f
            })
        } else {
            events.forEach { event ->
                root.addView(TextView(this).apply {
                    text = event
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(0, 10, 0, 10)
                })
            }
        }
        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }
}
