package com.docpilot.qwen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.docpilot.qwen.ui.DocPilotApp
import com.docpilot.qwen.ui.theme.DocPilotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DocPilotApplication
        setContent {
            DocPilotTheme {
                DocPilotApp(container = app.container)
            }
        }
    }
}

