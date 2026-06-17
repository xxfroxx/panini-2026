package com.panini.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class AddStickerResponse(
    val ok: Boolean,
    val status: String,
    val message: String,
    val requiresConfirmation: Boolean? = null,
    val sticker: StickerResponse? = null,
)

data class StickerResponse(
    val id: String,
    val name: String?,
    val context: String?,
    val oldQuantity: Int?,
    val newQuantity: Int?,
)

data class AddStickerBatchResponse(
    val ok: Boolean,
    val status: String,
    val message: String,
    val results: List<BatchStickerResult>,
)

data class BatchStickerResult(
    val id: String,
    val status: String,
    val message: String,
    val requiresConfirmation: Boolean = false,
    val oldQuantity: Int? = null,
    val newQuantity: Int? = null,
)

class PaniniApi {
    suspend fun addSticker(
        stickerId: String,
        confirmDuplicate: Boolean = false,
    ): AddStickerResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("secret", AppConfig.APP_SECRET)
            .put("action", "addSticker")
            .put("stickerId", stickerId)
            .put("confirmDuplicate", confirmDuplicate)
            .toString()

        val connection = (URL(AppConfig.APPS_SCRIPT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = stream.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }

            parseAddStickerResponse(JSONObject(responseBody))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun addStickerBatch(
        stickerIds: List<String>,
        confirmDuplicates: Map<String, Boolean> = emptyMap(),
    ): AddStickerBatchResponse = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("secret", AppConfig.APP_SECRET)
            .put("action", "addStickerBatch")
            .put("stickerIds", JSONArray(stickerIds))
            .put("confirmDuplicates", JSONObject(confirmDuplicates))
            .toString()

        val connection = (URL(AppConfig.APPS_SCRIPT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream ?: connection.inputStream
            }

            val responseBody = stream.use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }

            parseAddStickerBatchResponse(JSONObject(responseBody))
        } finally {
            connection.disconnect()
        }
    }

    private fun parseAddStickerResponse(json: JSONObject): AddStickerResponse {
        val stickerJson = json.optJSONObject("sticker")

        return AddStickerResponse(
            ok = json.optBoolean("ok"),
            status = json.optString("status"),
            message = json.optString("message"),
            requiresConfirmation = if (json.has("requiresConfirmation")) json.optBoolean("requiresConfirmation") else null,
            sticker = stickerJson?.let {
                StickerResponse(
                    id = it.optString("id"),
                    name = it.optNullableString("name"),
                    context = it.optNullableString("context"),
                    oldQuantity = it.optNullableInt("oldQuantity"),
                    newQuantity = it.optNullableInt("newQuantity"),
                )
            },
        )
    }

    private fun parseAddStickerBatchResponse(json: JSONObject): AddStickerBatchResponse {
        val resultsJson = json.optJSONArray("results") ?: JSONArray()
        val results = buildList {
            for (index in 0 until resultsJson.length()) {
                val item = resultsJson.optJSONObject(index) ?: continue
                add(
                    BatchStickerResult(
                        id = item.optString("id"),
                        status = item.optString("status"),
                        message = item.optString("message"),
                        requiresConfirmation = item.optBoolean("requiresConfirmation", false),
                        oldQuantity = item.optNullableInt("oldQuantity"),
                        newQuantity = item.optNullableInt("newQuantity"),
                    ),
                )
            }
        }

        return AddStickerBatchResponse(
            ok = json.optBoolean("ok"),
            status = json.optString("status"),
            message = json.optString("message"),
            results = results,
        )
    }
}

private fun JSONObject.optNullableString(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name) else null
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
