package com.docpilot.qwen.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val sizeLabel: String,
    val status: String,
    val updatedAt: String,
    val sourceUri: String,
    val markdown: String = "",
    val parseJson: String = "",
    val folder: String = "默认",
    val tags: String = "",
    val pageCount: Int = 0,
    val parseProgress: Int = 0,
    val citationsJson: String = "[]"
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val role: String,
    val content: String,
    val source: String,
    val streaming: Boolean = false,
    val citationsJson: String = "[]"
)

@Entity(tableName = "extractions")
data class ExtractionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val templateName: String,
    val content: String,
    val source: String,
    val citationsJson: String = "[]"
)

data class PageCitation(
    val id: String,
    val page: Int,
    val title: String,
    val snippet: String,
    val source: String
)
