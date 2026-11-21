package com.pixr.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtelierScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var before by remember { mutableStateOf<Bitmap?>(null) }
    var after by remember { mutableStateOf<Bitmap?>(null) }
    var mode by remember { mutableStateOf(ProcessingMode.STANDARD) }
    var crop by remember { mutableStateOf(CropPreset.FULL) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var factor by remember { mutableIntStateOf(2) }
    var grain by remember { mutableFloatStateOf(70f) }
    var format by remember { mutableStateOf(ExportFormat.JPEG98) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(UiStatus("Importe une photo pour commencer.")) }
    var showSettings by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { picked ->
        if (picked != null) scope.launch {
            busy = true
            status = UiStatus("Chargement…")
            val bmp = withContext(Dispatchers.IO) { runCatching { ImageIO.decodeOriented(context, picked) }.getOrNull() }
            busy = false
            if (bmp == null) {
                status = UiStatus("Impossible de lire cette image. Choisis-en une autre.", StatusKind.ERROR)
            } else {
                uri = picked; source = bmp; before = null; after = null
                status = UiStatus("${bmp.width}×${bmp.height} px chargés.")
            }
        }
    }

    val src = source
    val cropTile = src?.let { CropMath.centerCrop(it.width, it.height, crop.ratio, zoom) }

    fun enhance() {
        val s = src ?: return
        val t = cropTile ?: return
        scope.launch {
            busy = true
            status = UiStatus("Traitement en cours…")
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    check(NativeEngine.available) { "Moteur natif indisponible" }
                    val maxIn = OutputEstimator.MAX_OUTPUT_PIXELS / (factor * factor)
                    check(t.w.toLong() * t.h <= maxIn) { "Zone trop grande pour ×$factor : réduis le recadrage ou choisis ×2" }
                    val cropped = Bitmap.createBitmap(s, t.x, t.y, t.w, t.h)
                    val px = IntArray(t.w * t.h)
                    cropped.getPixels(px, 0, t.w, 0, 0, t.w, t.h)
                    val out = NativeEngine.upscale(px, t.w, t.h, factor, mode.nativeId, grain.toInt(), 256)
                        ?: error("Échec du traitement natif")
                    cropped to Bitmap.createBitmap(out, t.w * factor, t.h * factor, Bitmap.Config.ARGB_8888)
                }
            }
            busy = false
            result.onSuccess { (b, a) -> before = b; after = a; status = UiStatus("Terminé : ${a.width}×${a.height} px.", StatusKind.SUCCESS) }
                .onFailure { status = UiStatus("Erreur : ${it.message}", StatusKind.ERROR) }
        }
    }

    fun save() {
        val a = after ?: return
        scope.launch {
            busy = true
            val saved = withContext(Dispatchers.IO) { runCatching { Exporter.save(context, a, uri, factor, format) } }
            busy = false
            status = saved.fold(
                onSuccess = {
                    if (it != null) UiStatus(ExifPolicy.exportStatus(format, it.exifWritten), StatusKind.SUCCESS)
                    else UiStatus("Échec de l'enregistrement. Réessaie.", StatusKind.ERROR)
                },
                onFailure = { UiStatus("Erreur d'export : ${it.message}", StatusKind.ERROR) }
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Atelier", style = MaterialTheme.typography.titleLarge)
                    Text("Local • 0 % génératif", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = { picker.launch("image/*") }, enabled = !busy) {
                    Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Importer")
                }
                IconButton(onClick = { showSettings = true }, enabled = src != null) {
                    Icon(Icons.Rounded.Tune, contentDescription = "Réglages")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )

        Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
            val b = before
            val a = after
            when {
                b != null && a != null -> BeforeAfter(b, a, Modifier.fillMaxSize())
                src != null -> Image(bitmap = src.asImageBitmap(), contentDescription = "Photo importée", modifier = Modifier.fillMaxSize())
                else -> EmptyState(busy, status, onImport = { picker.launch("image/*") })
            }
            if (busy) CircularProgressIndicator()
        }

        if (src != null) {
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChoiceRow(listOf(2, 4), factor, { "×$it" }, { factor = it }, Modifier.weight(1f))
                    FilledTonalButton(onClick = { showSettings = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, Modifier.size(18.dp))
                        Text("Réglages", Modifier.padding(start = 8.dp))
                    }
                }
                if (after == null) {
                    Button(onClick = { enhance() }, enabled = !busy && cropTile != null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, Modifier.size(18.dp))
                        Text("Améliorer", Modifier.padding(start = 8.dp))
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { save() }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.Save, contentDescription = null, Modifier.size(18.dp))
                            Text("Enregistrer", Modifier.padding(start = 8.dp))
                        }
                        FilledTonalButton(onClick = { enhance() }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Text("Améliorer", Modifier.padding(start = 8.dp))
                        }
                    }
                }
                StatusLine(status)
            }
        }
    }

    if (showSettings && src != null) {
        ModalBottomSheet(
            onDismissRequest = { showSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Réglages", style = MaterialTheme.typography.titleLarge)
                SectionLabel("Mode")
                ChoiceRow(ProcessingMode.entries, mode, { it.shortLabel() }, { mode = it })
                SectionLabel("Recadrage HD")
                ChoiceRow(CropPreset.entries, crop, { it.label }, { crop = it }, checkIcon = false)
                Text("Zoom du recadrage : ${"%.1f".format(zoom)}×", style = MaterialTheme.typography.bodyMedium.tnum())
                Slider(value = zoom, onValueChange = { zoom = it }, valueRange = 1f..4f)
                SectionLabel("Grain d'origine")
                Text("${grain.toInt()} %", style = MaterialTheme.typography.bodyMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = grain, onValueChange = { grain = it }, valueRange = 0f..100f)
                SectionLabel("Format d'export")
                ChoiceRow(ExportFormat.entries, format, { it.label }, { format = it })
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                if (cropTile != null) {
                    Text(
                        "Sortie : %.1f MP • ~%.0f Mo • %s".format(
                            OutputEstimator.megapixels(cropTile.w, cropTile.h, factor),
                            OutputEstimator.estimatedMegabytes(cropTile.w, cropTile.h, factor, format),
                            if (format == ExportFormat.JPEG98) "EXIF conservés" else "PNG : EXIF si supporté"
                        ),
                        style = MaterialTheme.typography.bodyMedium.tnum()
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(busy: Boolean, status: UiStatus, onImport: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Aucune photo", style = MaterialTheme.typography.titleMedium)
        Text(
            "Importe une photo pour l'agrandir fidèlement, sans rien inventer.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (status.kind == StatusKind.ERROR) StatusLine(status)
        Button(onClick = onImport, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Importer une photo") }
    }
}
