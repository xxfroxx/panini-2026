package com.panini.tracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stickerIds = remember { mutableStateListOf<String>() }
    var inputId by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Lista vacía.") }
    var saving by remember { mutableStateOf(false) }
    var duplicateRequest by remember { mutableStateOf<DuplicateRequest?>(null) }
    var showScanner by remember { mutableStateOf(false) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            showScanner = true
        } else {
            statusMessage = "Permiso de cámara denegado. Puedes seguir agregando IDs manualmente."
        }
    }

    fun addIdToList(id: String) {
        val normalizedIds = parseStickerIds(id)

        if (normalizedIds.isEmpty()) {
            statusMessage = "Escribe un ID válido."
            return
        }

        stickerIds.addAll(normalizedIds)
        inputId = ""
        statusMessage = if (normalizedIds.size == 1) {
            "${normalizedIds.first()} añadido a la lista."
        } else {
            "${normalizedIds.size} cromos añadidos a la lista."
        }
    }

    fun clearList() {
        if (saving) return
        stickerIds.clear()
        statusMessage = "Lista vacía."
    }

    fun openScanner() {
        if (saving) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
        statusMessage = "Guardando..."

        scope.launch {
            val result = BatchSummary()
            val itemsToSave = stickerIds.toList()

            try {
                statusMessage = "Enviando ${itemsToSave.size} cromos..."
                val batchResponse = api.addStickerBatch(itemsToSave)
                val confirmedDuplicates = mutableListOf<String>()

                if (!batchResponse.ok) {
                    statusMessage = batchResponse.message.ifBlank {
                        "No se pudo guardar la lista. Revisa Apps Script."
                    }
                    result.errors.addAll(itemsToSave)
                    saving = false
                    return@launch
                }

                if (batchResponse.results.isEmpty() && itemsToSave.isNotEmpty()) {
                    statusMessage = "Apps Script no devolvió resultados batch. Revisa que addStickerBatch esté desplegado."
                    result.errors.addAll(itemsToSave)
                    saving = false
                    return@launch
                }

                batchResponse.results.forEach { item ->
                    when (item.status) {
                        "added" -> result.added.add(item.id)
                        "incremented" -> result.incremented.add(item.id)
                        "not_found" -> result.notFound.add(item.id)
                        "already_exists" -> {
                            val confirmed = askDuplicate(item.id)
                            if (confirmed) {
                                confirmedDuplicates.add(item.id)
                            } else {
                                result.cancelled.add(item.id)
                            }
                        }
                        else -> result.errors.add(item.id)
                    }
                }

                if (confirmedDuplicates.isNotEmpty()) {
                    statusMessage = "Guardando duplicados confirmados..."
                    val confirmMap = confirmedDuplicates.associateWith { true }
                    val confirmedResponse = api.addStickerBatch(
                        stickerIds = confirmedDuplicates,
                        confirmDuplicates = confirmMap,
                    )

                    if (!confirmedResponse.ok) {
                        result.errors.addAll(confirmedDuplicates)
                        statusMessage = confirmedResponse.message.ifBlank {
                            "No se pudieron guardar duplicados confirmados."
                        }
                        saving = false
                        return@launch
                    }

                    confirmedResponse.results.forEach { item ->
                        when (item.status) {
                            "incremented" -> result.incremented.add(item.id)
                            "added" -> result.added.add(item.id)
                            "not_found" -> result.notFound.add(item.id)
                            else -> result.errors.add(item.id)
                        }
                    }
                }
            } catch (_: Exception) {
                result.errors.addAll(itemsToSave)
            }

            statusMessage = result.toDisplayText()
            saving = false
        }
    }

    if (showScanner) {
        ScannerScreen(
            onStickerConfirmed = { id ->
                addIdToList(id)
            },
            onCancel = { showScanner = false },
        )
    } else {
        MainListScreen(
            inputId = inputId,
            onInputChange = { inputId = it },
            stickerIds = stickerIds,
            statusMessage = statusMessage,
            saving = saving,
            onAdd = { addIdToList(inputId) },
            onScan = ::openScanner,
            onSave = ::saveList,
            onClear = ::clearList,
        )
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
fun MainListScreen(
    inputId: String,
    onInputChange: (String) -> Unit,
    stickerIds: MutableList<String>,
    statusMessage: String,
    saving: Boolean,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
) {
    val statusScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 20.dp)
            .padding(bottom = 56.dp),
    ) {
        Text(
            text = "Panini 2026 Tracker",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(18.dp))
        OutlinedTextField(
            value = inputId,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("ID del cromo") },
            placeholder = { Text("Ej: COL05, MEX01, FWC9") },
            singleLine = true,
            enabled = !saving,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onAdd,
                modifier = Modifier.weight(1f),
                enabled = !saving,
            ) {
                Text("Añadir a lista")
            }
            OutlinedButton(
                onClick = onScan,
                modifier = Modifier.weight(1f),
                enabled = !saving,
            ) {
                Text("Escanear")
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Button(
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (saving) "Guardando..." else "Guardar lista")
            }
            OutlinedButton(
                onClick = onClear,
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = statusMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(statusScrollState)
                    .padding(12.dp),
            )
        }
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
}

@Composable
fun ScannerScreen(
    onStickerConfirmed: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var statusMessage by remember { mutableStateOf("Buscando ID...") }
    var detectedId by remember { mutableStateOf<String?>(null) }
    var lastDetectedId by remember { mutableStateOf<String?>(null) }
    var lastDetectionTime by remember { mutableStateOf(0L) }

    DisposableEffect(Unit) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { imageAnalysis ->
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            processImageProxy(
                                imageProxy = imageProxy,
                                context = context,
                                recognizer = recognizer,
                                detectionPaused = detectedId != null,
                                onStatus = { statusMessage = it },
                                onDetected = { id ->
                                    val now = SystemClock.elapsedRealtime()
                                    if (id == lastDetectedId && now - lastDetectionTime < 2000) {
                                        return@processImageProxy
                                    }

                                    lastDetectedId = id
                                    lastDetectionTime = now
                                    detectedId = id
                                    statusMessage = "Detectado: $id"
                                },
                            )
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            } catch (_: Exception) {
                statusMessage = "No se pudo abrir la cámara."
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            recognizer.close()
            cameraExecutor.shutdown()
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(bottom = 48.dp),
    ) {
        Text(
            text = "Apunta al código del cromo",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
        )
        Spacer(modifier = Modifier.height(10.dp))
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = statusMessage)
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Finalizar")
        }
    }

    detectedId?.let { id ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("ID detectado") },
            text = { Text("Detectado: $id") },
            confirmButton = {
                TextButton(onClick = {
                    onStickerConfirmed(id)
                    detectedId = null
                    statusMessage = "$id añadido a la lista."
                }) {
                    Text("Añadir a lista")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { detectedId = null; statusMessage = "Buscando ID..." }) {
                        Text("Reintentar")
                    }
                    TextButton(onClick = onCancel) {
                        Text("Finalizar")
                    }
                }
            },
        )
    }
}

@ExperimentalGetImage
private fun processImageProxy(
    imageProxy: ImageProxy,
    context: Context,
    recognizer: TextRecognizer,
    detectionPaused: Boolean,
    onStatus: (String) -> Unit,
    onDetected: (String) -> Unit,
) {
    if (detectionPaused) {
        imageProxy.close()
        return
    }

    val mediaImage = imageProxy.image

    if (mediaImage == null) {
        imageProxy.close()
        return
    }

    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

    recognizer.process(image)
        .addOnSuccessListener(ContextCompat.getMainExecutor(context)) { visionText ->
            val id = extractStickerIdFromText(visionText.text)
            if (id != null) {
                onDetected(id)
            } else {
                onStatus("Buscando ID...")
            }
        }
        .addOnFailureListener(ContextCompat.getMainExecutor(context)) {
            onStatus("Buscando ID...")
        }
        .addOnCompleteListener {
            imageProxy.close()
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

fun extractStickerIdFromText(rawText: String): String? {
    val text = rawText
        .uppercase()
        .replace("-", " ")
        .replace("_", " ")

    val fwcMatch = Regex("""\bFWC\s*(\d{1,2})\b""").find(text)
    if (fwcMatch != null) {
        val number = fwcMatch.groupValues[1].toIntOrNull()
        if (number != null && number in 1..19) {
            return "FWC$number"
        }
    }

    if (Regex("""\b00\b""").containsMatchIn(text)) {
        return "00"
    }

    val normalMatch = Regex("""\b([A-Z]{3})\s*(\d{1,2})\b""").find(text)
    if (normalMatch != null) {
        val prefix = normalMatch.groupValues[1]
        val number = normalMatch.groupValues[2].toIntOrNull()

        if (prefix != "FWC" && number != null && number in 1..20) {
            return prefix + number.toString().padStart(2, '0')
        }
    }

    return null
}

fun normalizeStickerId(input: String): String {
    val text = input
        .trim()
        .uppercase()
        .replace("-", " ")
        .replace("_", " ")

    val fwcMatch = Regex("""^FWC\s*(\d{1,2})$""").find(text)
    if (fwcMatch != null) {
        val number = fwcMatch.groupValues[1].toIntOrNull()
        if (number != null && number in 1..19) {
            return "FWC$number"
        }
    }

    if (text == "00") {
        return "00"
    }

    val normalMatch = Regex("""^([A-Z]{3})\s*(\d{1,2})$""").find(text)
    if (normalMatch != null) {
        val prefix = normalMatch.groupValues[1]
        val number = normalMatch.groupValues[2].toIntOrNull()

        if (prefix != "FWC" && number != null && number in 1..20) {
            return prefix + number.toString().padStart(2, '0')
        }
    }

    return text.replace(" ", "")
}

fun parseStickerIds(input: String): List<String> {
    return input
        .split(",")
        .map { normalizeStickerId(it) }
        .filter { it.isNotBlank() }
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
