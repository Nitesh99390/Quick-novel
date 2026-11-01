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
data class Btn(val text: String, val data: String? = null, val url: String? = null) {
    fun toMap(): Map<String, String> = buildMap {
        put("text", text)
        if (url != null) put("url", url) else put("callback_data", data ?: "noop")
    }
}

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
        call("getUpdates", mapOf("offset" to offset, "timeout" to timeout, "allowed_updates" to listOf("message", "callback_query")))

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
        upload(
            "sendPhoto", mapOf("chat_id" to chatId, "caption" to caption, "parse_mode" to "HTML", "reply_markup" to markup),
            "photo", "cover.jpg", bytes, "image/jpeg"
        ).path("message_id").asLong()

    suspend fun sendDocument(
        chatId: Long, fileName: String, bytes: ByteArray, caption: String? = null,
        mime: String = "application/octet-stream", thumb: ByteArray? = null,
    ): Long {
        if (thumb == null) {
            return upload(
                "sendDocument", mapOf("chat_id" to chatId, "caption" to caption, "parse_mode" to "HTML"),
                "document", fileName, bytes, mime
            ).path("message_id").asLong()
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
        }.path("message_id").asLong()
    }

    suspend fun setCommands(commands: List<Pair<String, String>>) {
        runCatching {
            call("setMyCommands", mapOf("commands" to commands.map { mapOf("command" to it.first, "description" to it.second) }))
        }.onFailure { Logger.warn("setMyCommands failed: ${it.message}") }
    }
}
