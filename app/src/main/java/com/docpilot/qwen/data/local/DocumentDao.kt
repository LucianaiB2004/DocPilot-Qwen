package com.docpilot.qwen.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Query("SELECT * FROM documents ORDER BY id ASC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM chat_messages WHERE documentId = :documentId ORDER BY id ASC")
    fun observeMessages(documentId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages WHERE role = 'user' ORDER BY id DESC LIMIT 8")
    fun observeRecentQuestions(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM extractions WHERE documentId = :documentId ORDER BY id ASC")
    fun observeExtractions(documentId: Long): Flow<List<ExtractionEntity>>

    @Query("SELECT * FROM extractions ORDER BY id ASC")
    fun observeAllExtractions(): Flow<List<ExtractionEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getDocument(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE sourceUri = :sourceUri LIMIT 1")
    suspend fun getDocumentBySourceUri(sourceUri: String): DocumentEntity?

    @Query("SELECT COUNT(*) FROM documents")
    suspend fun countDocuments(): Int

    @Query("SELECT COUNT(*) FROM documents WHERE sourceUri LIKE 'asset://sample_docs/%'")
    suspend fun countAssetSamples(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDocument(document: DocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExtraction(extraction: ExtractionEntity): Long

    @Query("SELECT * FROM extractions WHERE documentId = :documentId AND templateName = :templateName AND content = :content LIMIT 1")
    suspend fun findExtraction(documentId: Long, templateName: String, content: String): ExtractionEntity?

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteDocument(id: Long)

    @Query("DELETE FROM chat_messages WHERE id = :id")
    suspend fun deleteMessage(id: Long)

    @Query("DELETE FROM chat_messages WHERE documentId = :documentId")
    suspend fun deleteMessagesForDocument(documentId: Long)

    @Query("DELETE FROM extractions WHERE id = :id")
    suspend fun deleteExtraction(id: Long)

    @Query("DELETE FROM extractions WHERE documentId = :documentId")
    suspend fun deleteExtractionsForDocument(documentId: Long)

    @Query("UPDATE documents SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("UPDATE documents SET markdown = :markdown, parseJson = :json WHERE id = :id")
    suspend fun updateParsedContent(id: Long, markdown: String, json: String)

    @Query("UPDATE documents SET markdown = :markdown, parseJson = :json, pageCount = :pageCount, citationsJson = :citationsJson, parseProgress = :progress WHERE id = :id")
    suspend fun updateParsedContent(
        id: Long,
        markdown: String,
        json: String,
        pageCount: Int,
        citationsJson: String,
        progress: Int
    )

    @Query("UPDATE documents SET parseProgress = :progress, status = :status WHERE id = :id")
    suspend fun updateParseProgress(id: Long, progress: Int, status: String)

    @Query("UPDATE documents SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameDocument(id: Long, name: String, updatedAt: String)

    @Query("UPDATE documents SET tags = :tags, folder = :folder, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDocumentOrganization(id: Long, tags: String, folder: String, updatedAt: String)

    @Query("UPDATE chat_messages SET content = :content, streaming = :streaming, citationsJson = :citationsJson WHERE id = :id")
    suspend fun updateMessageContent(id: Long, content: String, streaming: Boolean, citationsJson: String = "[]")

    @Query("DELETE FROM chat_messages")
    suspend fun deleteMessages()

    @Query("DELETE FROM extractions")
    suspend fun deleteExtractions()

    @Query("DELETE FROM documents")
    suspend fun deleteDocuments()
}
