package com.panini.tracker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
}

private fun JSONObject.optNullableString(name: String): String? {
    return if (has(name) && !isNull(name)) optString(name) else null
}

private fun JSONObject.optNullableInt(name: String): Int? {
    return if (has(name) && !isNull(name)) optInt(name) else null
}
