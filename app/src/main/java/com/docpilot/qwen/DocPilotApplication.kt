package com.docpilot.qwen

import android.app.Application
import com.docpilot.qwen.data.AppContainer

class DocPilotApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

