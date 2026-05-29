package com.docpilot.qwen.data

import android.content.Context
import androidx.room.Room
import com.docpilot.qwen.BuildConfig
import com.docpilot.qwen.data.local.DocPilotDatabase
import com.docpilot.qwen.data.local.LocalModelManager
import com.docpilot.qwen.data.local.MnnQwenEngine
import com.docpilot.qwen.data.network.NetworkModule
import com.docpilot.qwen.data.settings.SettingsStore
import com.docpilot.qwen.security.ApiKeyStore

class AppContainer(context: Context) {
    private val database = Room.databaseBuilder(
        context,
        DocPilotDatabase::class.java,
        "docpilot.db"
    ).addMigrations(DocPilotDatabase.MIGRATION_1_2).build()

    val apiKeyStore = ApiKeyStore(context)
    val settingsStore = SettingsStore(context)
    val localModelManager = LocalModelManager(context)
    private val localModelEngine = MnnQwenEngine(context, localModelManager)

    private val networkModule = NetworkModule(
        qwenBaseUrl = BuildConfig.QWEN_BASE_URL,
        textInBaseUrl = BuildConfig.TEXTIN_BASE_URL
    )

    val repository = DocumentRepository(
        documentDao = database.documentDao(),
        qwenApi = networkModule.qwenApi,
        qwenStreamClient = networkModule.qwenStreamClient,
        textInApi = networkModule.textInApi,
        apiKeyStore = apiKeyStore,
        localModelEngine = localModelEngine
    )
}
