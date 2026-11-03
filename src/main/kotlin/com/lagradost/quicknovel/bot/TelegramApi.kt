package com.lagradost.quicknovel.bot

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.quicknovel.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class TelegramException(val code: Int, val description: String, val retryAfter: Int? = null) :
    Exception("Telegram $code: $description")

/** Inline keyboard button helper */
data class Btn(val text: String, val data: String? = null, val url: String? = null, val inline: String? = null) {
    fun toMap(): Map<String, String> = buildMap {
        put("text", text)
        when {
            url != null -> put("url", url)
            inline != null -> put("switch_inline_query_current_chat", inline)
            else -> put("callback_data", data ?: "noop")
        }
    }
}

/** A file that is stored on Telegram's servers (message in a chat + reusable file_id) */
data class TgFile(val chatId: Long, val messageId: Long, val fileId: String?)

fun keyboard(rows: List<List<Btn>>): Map<String, Any> =
    mapOf("inline_keyboard" to rows.filter { it.isNotEmpty() }.map { r -> r.map { it.toMap() } })

/**
 * Minimal, dependency free Telegram Bot API client (OkHttp + Jackson, both already on the
 * classpath through NiceHttp).
 */
class TelegramApi(token: String) {
    private val base = "${Config.apiBase}/bot$token/"
    private val mapper = AppUtils.mapper
    private val json = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    private fun parse(code: Int, body: String): JsonNode {
        val tree = runCatching { mapper.readTree(body) }.getOrNull()
            ?: throw TelegramException(code, "Invalid response: ${body.take(200)}")
        if (!tree.path("ok").asBoolean(false)) {
            throw TelegramException(
                tree.path("error_code").asInt(code),
                tree.path("description").asText("unknown error"),
                tree.path("parameters").path("retry_after").takeIf { it.isInt }?.asInt()
            )
        }
        return tree.path("result")
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: TelegramException) {
                if (e.code == 429 && attempt < 3) {
                    delay(((e.retryAfter ?: 3) + 1) * 1000L); attempt++; continue
                }
                throw e
            } catch (e: java.io.IOException) {
                if (attempt < 2) {
                    delay(2000L * (attempt + 1)); attempt++; continue
                }
                throw e
            }
        }
    }

    suspend fun call(method: String, params: Map<String, Any?> = emptyMap()): JsonNode = withRetry {
        withContext(Dispatchers.IO) {
            val body = mapper.writeValueAsString(params.filterValues { it != null }).toRequestBody(json)
            client.newCall(Request.Builder().url(base + method).post(body).build()).execute().use { res ->
                parse(res.code, res.body.string())
            }
        }
    }

    suspend fun upload(
        method: String,
        params: Map<String, Any?>,
        field: String,
        fileName: String,
        bytes: ByteArray,
        mime: String = "application/octet-stream",
    ): JsonNode = withRetry {
        withContext(Dispatchers.IO) {
            val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            params.forEach { (k, v) ->
                if (v == null) return@forEach
                mp.addFormDataPart(k, if (v is String || v is Number || v is Boolean) v.toString() else mapper.writeValueAsString(v))
            }
            mp.addFormDataPart(field, fileName, bytes.toRequestBody(mime.toMediaType()))
            client.newCall(Request.Builder().url(base + method).post(mp.build()).build()).execute().use { res ->
                parse(res.code, res.body.string())
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    suspend fun getUpdates(offset: Long, timeout: Int = 50): JsonNode =
        call("getUpdates", mapOf("offset" to offset, "timeout" to timeout,
            "allowed_updates" to listOf("message", "callback_query", "channel_post", "inline_query")))

    suspend fun sendMessage(chatId: Long, text: String, markup: Map<String, Any>? = null, replyTo: Long? = null): Long =
        call(
            "sendMessage", mapOf(
                "chat_id" to chatId, "text" to text, "parse_mode" to "HTML",
                "link_preview_options" to mapOf("is_disabled" to true),
                "reply_markup" to markup,
                "reply_parameters" to replyTo?.let { mapOf("message_id" to it, "allow_sending_without_reply" to true) },
            )
        ).path("message_id").asLong()

    /** Edits a text message; "message is not modified" is ignored. Returns false when the edit failed. */
    suspend fun editMessage(chatId: Long, messageId: Long, text: String, markup: Map<String, Any>? = null): Boolean = try {
        call(
            "editMessageText", mapOf(
                "chat_id" to chatId, "message_id" to messageId, "text" to text, "parse_mode" to "HTML",
                "link_preview_options" to mapOf("is_disabled" to true), "reply_markup" to markup,
            )
        ); true
    } catch (e: TelegramException) {
        e.description.contains("not modified")
    }

    suspend fun editMarkup(chatId: Long, messageId: Long, markup: Map<String, Any>?) {
        runCatching { call("editMessageReplyMarkup", mapOf("chat_id" to chatId, "message_id" to messageId, "reply_markup" to (markup ?: keyboard(emptyList())))) }
    }

    suspend fun deleteMessage(chatId: Long, messageId: Long) {
        runCatching { call("deleteMessage", mapOf("chat_id" to chatId, "message_id" to messageId)) }
    }

    suspend fun answerCallback(id: String, text: String? = null, alert: Boolean = false) {
        runCatching { call("answerCallbackQuery", mapOf("callback_query_id" to id, "text" to text, "show_alert" to alert)) }
    }

    suspend fun sendChatAction(chatId: Long, action: String) {
        runCatching { call("sendChatAction", mapOf("chat_id" to chatId, "action" to action)) }
    }

    suspend fun sendPhoto(chatId: Long, bytes: ByteArray, caption: String, markup: Map<String, Any>?): Long =
        sendPhotoFile(chatId, bytes, caption, markup).messageId

    /** Sends a photo and returns its message id + the file_id of the biggest size (reusable) */
    suspend fun sendPhotoFile(chatId: Long, bytes: ByteArray, caption: String, markup: Map<String, Any>?): TgFile {
        val m = upload(
            "sendPhoto", mapOf("chat_id" to chatId, "caption" to caption, "parse_mode" to "HTML", "reply_markup" to markup),
            "photo", "cover.jpg", bytes, "image/jpeg"
        )
        return TgFile(chatId, m.path("message_id").asLong(), m.path("photo").lastOrNull()?.path("file_id")?.asText())
    }

    /** Re-sends a file that is already on Telegram (no upload, no CPU - just the file_id) */
    suspend fun sendCached(
        method: String, chatId: Long, fileId: String, caption: String?, markup: Map<String, Any>? = null,
    ): Long {
        val field = if (method == "sendPhoto") "photo" else "document"
        return call(
            method, mapOf("chat_id" to chatId, field to fileId, "caption" to caption, "parse_mode" to "HTML", "reply_markup" to markup)
        ).path("message_id").asLong()
    }

    /** Copies a message (e.g. a book from the dump channel) without the "forwarded from" header */
    suspend fun copyMessage(chatId: Long, fromChat: Long, messageId: Long, markup: Map<String, Any>? = null): Long =
        call("copyMessage", mapOf("chat_id" to chatId, "from_chat_id" to fromChat, "message_id" to messageId, "reply_markup" to markup))
            .path("message_id").asLong()

    suspend fun answerInlineQuery(id: String, results: List<Map<String, Any?>>, nextOffset: String?, cacheTime: Int = 60,
                                  button: Map<String, Any>? = null) {
        runCatching {
            call("answerInlineQuery", mapOf(
                "inline_query_id" to id, "results" to results.map { r -> r.filterValues { it != null } },
                "cache_time" to cacheTime, "is_personal" to false, "next_offset" to (nextOffset ?: ""), "button" to button,
            ))
        }.onFailure { Logger.debug("answerInlineQuery failed", it) }
    }

    suspend fun sendDocument(
        chatId: Long, fileName: String, bytes: ByteArray, caption: String? = null,
        mime: String = "application/octet-stream", thumb: ByteArray? = null,
    ): Long = sendDocumentFile(chatId, fileName, bytes, caption, mime, thumb).messageId

    /** Uploads a document and returns its message id + reusable file_id */
    suspend fun sendDocumentFile(
        chatId: Long, fileName: String, bytes: ByteArray, caption: String? = null,
        mime: String = "application/octet-stream", thumb: ByteArray? = null,
    ): TgFile {
        val m = uploadDocument(chatId, fileName, bytes, caption, mime, thumb)
        return TgFile(chatId, m.path("message_id").asLong(), m.path("document").path("file_id").asText(null))
    }

    private suspend fun uploadDocument(
        chatId: Long, fileName: String, bytes: ByteArray, caption: String?, mime: String, thumb: ByteArray?,
    ): JsonNode {
        if (thumb == null) {
            return upload(
                "sendDocument", mapOf("chat_id" to chatId, "caption" to caption, "parse_mode" to "HTML"),
                "document", fileName, bytes, mime
            )
        }
        // thumbnail must be uploaded as an extra multipart file referenced with attach://
        return withRetry {
            withContext(Dispatchers.IO) {
                val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chatId.toString())
                    .addFormDataPart("parse_mode", "HTML")
                    .addFormDataPart("thumbnail", "attach://thumbfile")
                    .addFormDataPart("document", fileName, bytes.toRequestBody(mime.toMediaType()))
                    .addFormDataPart("thumbfile", "thumb.jpg", thumb.toRequestBody("image/jpeg".toMediaType()))
                if (caption != null) mp.addFormDataPart("caption", caption)
                client.newCall(Request.Builder().url(base + "sendDocument").post(mp.build()).build()).execute().use { res ->
                    parse(res.code, res.body.string())
                }
            }
        }
    }

    suspend fun setCommands(commands: List<Pair<String, String>>, scope: Map<String, Any>? = null) {
        runCatching {
            call("setMyCommands", mapOf("commands" to commands.map { mapOf("command" to it.first, "description" to it.second) }, "scope" to scope))
        }.onFailure { Logger.warn("setMyCommands failed: ${it.message}") }
    }
}
