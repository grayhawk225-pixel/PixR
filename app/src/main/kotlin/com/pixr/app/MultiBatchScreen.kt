package com.pixr.app

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.BackoffPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private enum class MultiTab(val label: String) {
    FUSION("Fusion (3–6)"),
    LOT("Lot (2–20)")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiBatchScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    // ---------- Fusion galerie (3 à 6 photos) ----------
    var fuseUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var fuseBefore by remember { mutableStateOf<Bitmap?>(null) }
    var fuseAfter by remember { mutableStateOf<Bitmap?>(null) }
    var fuseGrain by remember { mutableFloatStateOf(70f) }
    var fuseFormat by remember { mutableStateOf(ExportFormat.JPEG98) }
    var fuseBusy by remember { mutableStateOf(false) }
    var fuseStatus by remember { mutableStateOf(UiStatus("Choisis 3 à 6 photos similaires (même cadrage).")) }
    var showFuseSettings by remember { mutableStateOf(false) }

    val fusePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(FusionRules.MAX_IMAGES)
    ) { uris ->
        fuseBefore = null
        fuseAfter = null
        fuseUris = uris
        val msg = FusionRules.countMessage(uris.size)
        fuseStatus = if (msg != null) UiStatus(msg, if (uris.isEmpty()) StatusKind.INFO else StatusKind.ERROR)
        else UiStatus("${uris.size} photos prêtes pour la fusion.")
    }

    fun fuse() {
        val uris = fuseUris
        val grain = fuseGrain.toInt()
        scope.launch {
            fuseBusy = true
            fuseStatus = UiStatus("Alignement et fusion en cours…")
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    check(NativeEngine.available) { "Moteur natif indisponible" }
                    val ref = ImageIO.decodeOriented(context, uris[0], FusionRules.maxInputPixels())
                        ?: error("Photo de référence illisible")
                    val w = ref.width
                    val h = ref.height
                    check(w >= 64 && h >= 64) { "Photos trop petites" }
                    val frames = ArrayList<IntArray>()
                    frames += ImageIO.pixels(ref)
                    for (u in uris.drop(1)) {
                        var b = ImageIO.decodeOriented(context, u, FusionRules.maxInputPixels())
                            ?: error("Une photo est illisible")
                        check(FusionRules.areSimilar(w, h, b.width, b.height)) {
                            "Les photos n'ont pas le même cadrage (ratio différent)"
                        }
                        if (b.width != w || b.height != h) b = Bitmap.createScaledBitmap(b, w, h, true)
                        frames += ImageIO.pixels(b)
                    }
                    val out = NativeEngine.fuse(frames.toTypedArray(), w, h, grain) ?: error("Échec de la fusion native")
                    ref to Bitmap.createBitmap(out, w * 2, h * 2, Bitmap.Config.ARGB_8888)
                }
            }
            fuseBusy = false
            result.onSuccess { (b, a) ->
                fuseBefore = b
                fuseAfter = a
                fuseStatus = UiStatus("Fusion terminée : ${a.width}×${a.height} px (${uris.size} photos).", StatusKind.SUCCESS)
            }.onFailure { fuseStatus = UiStatus("Erreur : ${it.message}", StatusKind.ERROR) }
        }
    }

    fun saveFusion() {
        val a = fuseAfter ?: return
        val uris = fuseUris
        scope.launch {
            fuseBusy = true
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    Exporter.save(context, a, uris.firstOrNull(), 2, fuseFormat, ExifPolicy.fusionNote(uris.size))
                }
            }
            fuseBusy = false
            fuseStatus = saved.fold(
                onSuccess = {
                    if (it != null) UiStatus(ExifPolicy.exportStatus(fuseFormat, it.exifWritten), StatusKind.SUCCESS)
                    else UiStatus("Échec de l'enregistrement. Réessaie.", StatusKind.ERROR)
                },
                onFailure = { UiStatus("Erreur d'export : ${it.message}", StatusKind.ERROR) }
            )
        }
    }

    // ---------- Lot hors-ligne (WorkManager) ----------
    var batchUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var batchMode by remember { mutableStateOf(ProcessingMode.STANDARD) }
    var batchFactor by remember { mutableIntStateOf(2) }
    var batchFormat by remember { mutableStateOf(ExportFormat.JPEG98) }
    var batchGrain by remember { mutableFloatStateOf(70f) }
    var batchBusy by remember { mutableStateOf(false) }
    var batchStatus by remember { mutableStateOf(UiStatus("Choisis 2 à 20 photos.")) }
    var workId by remember { mutableStateOf<UUID?>(null) }

    val wm = remember { WorkManager.getInstance(context) }
    val infoFlow = remember(workId) { workId?.let { wm.getWorkInfoByIdFlow(it) } ?: flowOf<WorkInfo?>(null) }
    val info by infoFlow.collectAsState(initial = null)

    val batchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(BatchRules.MAX_IMAGES)
    ) { uris ->
        batchUris = uris
        val msg = BatchRules.countMessage(uris.size)
        batchStatus = if (msg != null) UiStatus(msg, if (uris.isEmpty()) StatusKind.INFO else StatusKind.ERROR)
        else UiStatus("${uris.size} photos prêtes pour le lot.")
    }

    fun startBatch() {
        val uris = batchUris
        val mode = batchMode
        val factor = batchFactor
        val format = batchFormat
        val grain = batchGrain.toInt()
        scope.launch {
            batchBusy = true
            batchStatus = UiStatus("Préparation des photos…")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    // Copie locale : l'accès aux Uri du sélecteur n'est pas garanti en tâche de fond.
                    val dir = File(context.cacheDir, "batch").apply { mkdirs() }
                    val stamp = System.currentTimeMillis()
                    uris.mapIndexed { i, u ->
                        val f = File(dir, "${stamp}_$i.bin")
                        context.contentResolver.openInputStream(u)?.use { input ->
                            f.outputStream().use { input.copyTo(it) }
                        } ?: error("Photo ${i + 1} illisible")
                        f.absolutePath
                    }
                }
            }
            batchBusy = false
            copied.onSuccess { paths ->
                val req = OneTimeWorkRequestBuilder<PixRBatchWorker>()
                    .setInputData(
                        workDataOf(
                            PixRBatchWorker.KEY_PATHS to paths.toTypedArray(),
                            PixRBatchWorker.KEY_MODE to mode.nativeId,
                            PixRBatchWorker.KEY_FACTOR to factor,
                            PixRBatchWorker.KEY_GRAIN to grain,
                            PixRBatchWorker.KEY_FORMAT to format.name
                        )
                    )
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                    .build()
                wm.enqueue(req)
                workId = req.id
                batchStatus = UiStatus("Lot lancé en tâche de fond (${paths.size} photos).", StatusKind.SUCCESS)
            }.onFailure { batchStatus = UiStatus("Erreur : ${it.message}", StatusKind.ERROR) }
        }
    }

    val pickImages = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Multi & Lot", style = MaterialTheme.typography.titleLarge)
                    Text("Local • 0 % génératif", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        )
        ChoiceRow(
            MultiTab.entries, MultiTab.entries[tabIndex], { it.label }, { tabIndex = it.ordinal },
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        if (tabIndex == 0) {
            // ===== Fusion galerie =====
            val validFuse = FusionRules.isValidCount(fuseUris.size)
            Box(Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLowest), contentAlignment = Alignment.Center) {
                val b = fuseBefore
                val a = fuseAfter
                if (b != null && a != null) BeforeAfter(b, a, Modifier.fillMaxSize())
                else Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Collections, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (fuseUris.isEmpty()) "Aucune photo" else "${fuseUris.size} photos sélectionnées",
                        style = MaterialTheme.typography.titleMedium.tnum()
                    )
                    Text(
                        "Fusion « rafale a posteriori » : alignement par blocs, rejet des zones qui bougent, fusion ×2. La première photo sert de référence.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
                if (fuseBusy) CircularProgressIndicator()
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { fusePicker.launch(pickImages) }, enabled = !fuseBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, Modifier.size(18.dp))
                        Text(if (fuseUris.isEmpty()) "Choisir des photos" else "Changer les photos", Modifier.padding(start = 8.dp))
                    }
                    FilledTonalButton(onClick = { showFuseSettings = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, Modifier.size(18.dp))
                        Text("Réglages", Modifier.padding(start = 8.dp))
                    }
                }
                if (fuseAfter != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { saveFusion() }, enabled = !fuseBusy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.Save, contentDescription = null, Modifier.size(18.dp))
                            Text("Enregistrer", Modifier.padding(start = 8.dp))
                        }
                        FilledTonalButton(onClick = { fuse() }, enabled = !fuseBusy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, Modifier.size(18.dp))
                            Text("Refaire", Modifier.padding(start = 8.dp))
                        }
                    }
                } else {
                    Button(
                        onClick = { if (validFuse) fuse() else fusePicker.launch(pickImages) },
                        enabled = !fuseBusy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Rounded.AutoFixHigh, contentDescription = null, Modifier.size(18.dp))
                        Text(if (validFuse) "Fusionner (×2)" else "Choisir 3 à 6 photos", Modifier.padding(start = 8.dp))
                    }
                }
                StatusLine(fuseStatus)
            }
        } else {
            // ===== Lot =====
            val running = info?.state.let { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING }
            val data = info?.let { if (it.state.isFinished) it.outputData else it.progress }
            val done = data?.getInt(PixRBatchWorker.KEY_DONE, 0) ?: 0
            val total = data?.getInt(PixRBatchWorker.KEY_TOTAL, 0) ?: 0
            val msg = data?.getString(PixRBatchWorker.KEY_MSG)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Traitement en tâche de fond, hors-ligne. Pause automatique si batterie < 15 % (hors charge) ou chauffe sévère.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { batchPicker.launch(pickImages) }, enabled = !batchBusy && !running, modifier = Modifier.heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, Modifier.size(18.dp))
                        Text("Choisir 2 à 20 photos", Modifier.padding(start = 8.dp))
                    }
                    Text("${batchUris.size} sélectionnées", style = MaterialTheme.typography.bodyMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SectionLabel("Mode")
                ChoiceRow(ProcessingMode.entries, batchMode, { it.shortLabel() }, { batchMode = it })
                SectionLabel("Facteur")
                ChoiceRow(listOf(2, 4), batchFactor, { "×$it" }, { batchFactor = it })
                SectionLabel("Format d'export")
                ChoiceRow(ExportFormat.entries, batchFormat, { it.label }, { batchFormat = it })
                SectionLabel("Grain d'origine")
                Text("${batchGrain.toInt()} %", style = MaterialTheme.typography.bodyMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = batchGrain, onValueChange = { batchGrain = it }, valueRange = 0f..100f)
                if (info != null && total > 0) {
                    LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
                    Text("$done / $total • ${done * 100 / total} %", style = MaterialTheme.typography.bodyMedium.tnum())
                }
            }
            Column(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (running) {
                    OutlinedButton(onClick = { workId?.let { wm.cancelWorkById(it) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = null, Modifier.size(18.dp))
                        Text("Annuler le lot", Modifier.padding(start = 8.dp))
                    }
                } else {
                    Button(
                        enabled = !batchBusy && BatchRules.isValidCount(batchUris.size),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        onClick = { startBatch() }
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                        Text("Lancer le lot", Modifier.padding(start = 8.dp))
                    }
                }
                val stateStatus = when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> UiStatus(msg ?: "Lot terminé.", StatusKind.SUCCESS)
                    WorkInfo.State.FAILED -> UiStatus("Lot en échec. Vérifie les photos puis relance.", StatusKind.ERROR)
                    WorkInfo.State.CANCELLED -> UiStatus("Lot annulé.")
                    WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> UiStatus(msg ?: "En attente…")
                    else -> batchStatus
                }
                StatusLine(stateStatus)
            }
        }
    }

    if (showFuseSettings) {
        ModalBottomSheet(
            onDismissRequest = { showFuseSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Réglages de la fusion", style = MaterialTheme.typography.titleLarge)
                SectionLabel("Grain d'origine")
                Text("${fuseGrain.toInt()} %", style = MaterialTheme.typography.bodyMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(value = fuseGrain, onValueChange = { fuseGrain = it }, valueRange = 0f..100f)
                SectionLabel("Format d'export")
                ChoiceRow(ExportFormat.entries, fuseFormat, { it.label }, { fuseFormat = it })
            }
        }
    }
}
