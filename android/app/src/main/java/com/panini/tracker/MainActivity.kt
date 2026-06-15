package com.panini.tracker

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PaniniApp()
        }
    }
}

@Composable
fun PaniniApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            StickerBatchScreen(api = remember { PaniniApi() })
        }
    }
}

@Composable
fun StickerBatchScreen(api: PaniniApi) {
    val scope = rememberCoroutineScope()
    val stickerIds = remember { mutableStateListOf<String>() }
    var inputId by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Lista vacía.") }
    var saving by remember { mutableStateOf(false) }
    var summary by remember { mutableStateOf<BatchSummary?>(null) }
    var duplicateRequest by remember { mutableStateOf<DuplicateRequest?>(null) }

    fun addToList() {
        val normalizedId = normalizeStickerId(inputId)

        if (normalizedId.isBlank()) {
            statusMessage = "Escribe un ID válido."
            return
        }

        stickerIds.add(normalizedId)
        inputId = ""
        summary = null
        statusMessage = "${stickerIds.size} cromos en lista."
    }

    fun clearList() {
        if (saving) return
        stickerIds.clear()
        summary = null
        statusMessage = "Lista vacía."
    }

    suspend fun askDuplicate(id: String): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        duplicateRequest = DuplicateRequest(id = id, deferred = deferred)
        val confirmed = deferred.await()
        duplicateRequest = null
        return confirmed
    }

    fun saveList() {
        if (saving) return

        if (stickerIds.isEmpty()) {
            statusMessage = "La lista está vacía."
            return
        }

        if (AppConfig.APPS_SCRIPT_URL.isBlank()) {
            statusMessage = "Falta configurar APPS_SCRIPT_URL en local.properties."
            return
        }

        if (AppConfig.APP_SECRET.isBlank()) {
            statusMessage = "Falta configurar APP_SECRET en local.properties."
            return
        }

        saving = true
        summary = null
        statusMessage = "Guardando..."

        scope.launch {
            val result = BatchSummary()
            val itemsToSave = stickerIds.toList()

            itemsToSave.forEachIndexed { index, id ->
                statusMessage = "Guardando ${index + 1} de ${itemsToSave.size}: $id"

                try {
                    val response = api.addSticker(id, confirmDuplicate = false)
                    val responseId = response.sticker?.id ?: id

                    when (response.status) {
                        "added" -> result.added.add(responseId)
                        "incremented" -> result.incremented.add(responseId)
                        "not_found" -> result.notFound.add(responseId)
                        "already_exists" -> {
                            val confirmed = askDuplicate(responseId)

                            if (confirmed) {
                                try {
                                    val confirmedResponse = api.addSticker(responseId, confirmDuplicate = true)
                                    if (confirmedResponse.ok) {
                                        result.incremented.add(confirmedResponse.sticker?.id ?: responseId)
                                    } else {
                                        result.errors.add(responseId)
                                    }
                                } catch (_: Exception) {
                                    result.errors.add(responseId)
                                }
                            } else {
                                result.cancelled.add(responseId)
                            }
                        }
                        else -> result.errors.add(responseId)
                    }
                } catch (_: Exception) {
                    result.errors.add(id)
                }
            }

            summary = result
            statusMessage = result.toDisplayText()
            saving = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Text(
            text = "Panini 2026 Tracker",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(18.dp))
        OutlinedTextField(
            value = inputId,
            onValueChange = { inputId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ID del cromo") },
            placeholder = { Text("Ej: COL05, MEX01, FWC9") },
            singleLine = true,
            enabled = !saving,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = ::addToList,
            modifier = Modifier.fillMaxWidth(),
            enabled = !saving,
        ) {
            Text("Añadir a lista")
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = ::saveList,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (saving) "Guardando..." else "Guardar lista")
            }
            OutlinedButton(
                onClick = ::clearList,
                enabled = !saving && stickerIds.isNotEmpty(),
                modifier = Modifier.weight(1f),
            ) {
                Text("Limpiar lista")
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "${stickerIds.size} cromos en lista",
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = statusMessage)
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f),
        ) {
            if (stickerIds.isEmpty()) {
                item {
                    Text("Lista vacía")
                }
            }

            itemsIndexed(stickerIds) { index, id ->
                StickerListRow(
                    id = id,
                    enabled = !saving,
                    onRemove = { stickerIds.removeAt(index) },
                )
            }
        }
    }

    duplicateRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Cromo ya existe") },
            text = { Text("El cromo ${request.id} ya está en tu colección. ¿Quieres sumar +1?") },
            confirmButton = {
                TextButton(onClick = { request.deferred.complete(true) }) {
                    Text("Sumar +1")
                }
            },
            dismissButton = {
                TextButton(onClick = { request.deferred.complete(false) }) {
                    Text("Cancelar")
                }
            },
        )
    }
}

@Composable
fun StickerListRow(
    id: String,
    enabled: Boolean,
    onRemove: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = id,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRemove, enabled = enabled) {
                Text("Eliminar")
            }
        }
    }
}

fun normalizeStickerId(input: String): String {
    return input
        .trim()
        .uppercase()
        .replace(Regex("[\\s_-]+"), "")
}

data class DuplicateRequest(
    val id: String,
    val deferred: CompletableDeferred<Boolean>,
)

data class BatchSummary(
    val added: MutableList<String> = mutableListOf(),
    val incremented: MutableList<String> = mutableListOf(),
    val notFound: MutableList<String> = mutableListOf(),
    val cancelled: MutableList<String> = mutableListOf(),
    val errors: MutableList<String> = mutableListOf(),
) {
    fun toDisplayText(): String {
        return buildString {
            appendLine("Proceso terminado:")
            appendLine("Agregados: ${added.size}")
            appendLine("Incrementados: ${incremented.size}")
            appendLine("No encontrados: ${notFound.size}")
            appendLine("Cancelados: ${cancelled.size}")
            appendLine("Errores: ${errors.size}")

            appendDetails("Agregados", added)
            appendDetails("Incrementados", incremented)
            appendDetails("No encontrados", notFound)
            appendDetails("Cancelados", cancelled)
            appendDetails("Errores", errors)
        }.trim()
    }

    private fun StringBuilder.appendDetails(title: String, items: List<String>) {
        if (items.isEmpty()) return

        appendLine()
        appendLine("$title:")
        items.forEach { item ->
            appendLine("* $item")
        }
    }
}
