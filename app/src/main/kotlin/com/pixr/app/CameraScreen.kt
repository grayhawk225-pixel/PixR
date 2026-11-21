package com.pixr.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.Settings
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CameraScreen(onOpenAtelier: () -> Unit) {
    val context = LocalContext.current
    val info = remember { runCatching { CameraBurstController.detect(context) }.getOrNull() }
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        when {
            info == null -> BlockedState(
                title = "Caméra arrière indisponible",
                text = "Aucune caméra arrière exploitable n'a été détectée sur cet appareil. L'Atelier reste disponible pour traiter tes photos existantes.",
                actions = { Button(onClick = onOpenAtelier, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ouvrir l'Atelier") } }
            )
            !hasPermission -> BlockedState(
                title = "Autorisation caméra requise",
                text = "PixR capture en RAW directement sur l'appareil, sans envoi réseau. Détecté : ${info.summary}.",
                actions = {
                    Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Autoriser la caméra") }
                    TextButton(
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Ouvrir les réglages de l'application") }
                }
            )
            else -> LiveCamera(info, onOpenAtelier)
        }
    }
}

@Composable
private fun BlockedState(title: String, text: String, actions: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
    ) {
        Icon(Icons.Outlined.NoPhotography, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        actions()
    }
}

@Composable
private fun StabilityIndicator() {
    val context = LocalContext.current
    var level by remember { mutableStateOf(StabilityLevel.STABLE) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        var smoothed = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                smoothed = StabilityRules.smooth(smoothed, StabilityRules.magnitude(e.values[0], e.values[1], e.values[2]))
                val next = StabilityRules.classify(smoothed)
                if (next != level) level = next
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (gyro != null) sm.registerListener(listener, gyro, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(listener) }
    }
    val (icon, tint) = when (level) {
        StabilityLevel.STABLE -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.onSurface
        StabilityLevel.WARNING -> Icons.Rounded.Warning to MaterialTheme.colorScheme.tertiary
        StabilityLevel.UNSTABLE -> Icons.Rounded.Error to MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = tint)
        Text(level.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColumnScope.LiveCamera(info: CameraInfo, onOpenAtelier: () -> Unit) {
    val context = LocalContext.current
    var texture by remember { mutableStateOf<SurfaceTexture?>(null) }
    var resumed by remember { mutableStateOf(true) }
    var controller by remember { mutableStateOf<CameraBurstController?>(null) }
    var status by remember { mutableStateOf(UiStatus(info.summary)) }
    var busy by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var textMode by remember { mutableStateOf(false) }
    var thumb by remember { mutableStateOf<Bitmap?>(null) }
    val prefs = remember { context.getSharedPreferences("pixr_camera_prefs", Context.MODE_PRIVATE) }
    var ev by remember { mutableFloatStateOf(prefs.getFloat("ev", 0f)) }
    var look by remember {
        mutableStateOf(RenderLook.entries.firstOrNull { it.name == prefs.getString("look", null) } ?: RenderLook.NATURAL)
    }
    var showRender by remember { mutableStateOf(false) }
    // Développement différé par défaut sur RAW : prise rapide, fusion et débruitage poussés en arrière-plan.
    var deferred by remember { mutableStateOf(info.strategy == CaptureStrategy.RAW && prefs.getBoolean("deferred", true)) }
    var showDevelop by remember { mutableStateOf(false) }
    var queued by remember { mutableIntStateOf(RawJobStore.pending(context).size) }
    val workInfos by WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("pixr_develop").collectAsState(initial = emptyList())
    val running = workInfos.any { it.state == WorkInfo.State.RUNNING }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(workInfos) { queued = RawJobStore.pending(context).size }
    // Demande unique de la permission de notification (Android 13+) : sert à prévenir quand un développement est fini.
    LaunchedEffect(Unit) {
        if (deferred && android.os.Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notif_asked", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("notif_asked", true).apply()
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) resumed = false
            if (e == Lifecycle.Event.ON_RESUME) resumed = true
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(texture, resumed) {
        val t = texture
        var c: CameraBurstController? = null
        if (t != null && resumed) {
            c = CameraBurstController(context, info)
            c.setZoom(zoom)
            c.setExposureBias(ev)
            c.look = look
            c.deferred = deferred
            c.start(t) { status = UiStatus(it, StatusKind.ERROR); busy = false }
            controller = c
        }
        onDispose {
            c?.close()
            controller = null
            busy = false
        }
    }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        StabilityIndicator()
        val toggleModifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Mode Texte / Loin, ${if (textMode) "activé" else "désactivé"}" }
        val content: @Composable () -> Unit = {
            Icon(Icons.Rounded.Description, contentDescription = null, Modifier.size(18.dp))
            Text("Texte / Loin", Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (textMode) FilledTonalButton(onClick = { textMode = false }, modifier = toggleModifier) { content() }
            else OutlinedButton(onClick = { textMode = true }, modifier = toggleModifier) { content() }
            IconButton(onClick = { showRender = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.Tune, contentDescription = "Rendu : exposition et style")
            }
        }
    }

    val ratio = info.previewSize.height.toFloat() / info.previewSize.width.toFloat()
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(ratio).clip(RoundedCornerShape(12.dp)),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { texture = st }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { texture = null; return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                    if (isAvailable) texture = surfaceTexture
                }
            }
        )
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ChoiceRow(
            ZoomMath.STEPS, zoom, { "${it.toInt()}x" },
            { zoom = it; controller?.setZoom(it) },
            enabled = { ZoomMath.isAvailable(it, info.maxZoom) }
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            val t = thumb
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = t != null, role = Role.Button, onClickLabel = "Ouvrir l'Atelier") { onOpenAtelier() },
                contentAlignment = Alignment.Center
            ) {
                if (t != null) Image(t.asImageBitmap(), "Dernière photo capturée", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.Outlined.PhotoLibrary, contentDescription = "Aucune photo capturée", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                Modifier.size(76.dp).border(4.dp, MaterialTheme.colorScheme.onSurface, CircleShape).padding(8.dp).clip(CircleShape)
                    .background(if (busy) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.primary)
                    .semantics { contentDescription = if (busy) "Rafale en cours" else "Déclencher la rafale" }
                    .clickable(enabled = !busy && controller != null, role = Role.Button) {
                        val started = controller?.capture(zoom, textMode) { out ->
                            busy = false
                            status = UiStatus(out.message, if (out.success) StatusKind.SUCCESS else StatusKind.ERROR)
                            queued = RawJobStore.pending(context).size
                            if (out.thumbnail != null) thumb = out.thumbnail
                        } ?: false
                        if (started) { busy = true; status = UiStatus("Rafale en cours…") }
                        else status = UiStatus("Caméra pas prête. Réessaie dans un instant.", StatusKind.ERROR)
                    },
                contentAlignment = Alignment.Center
            ) {
                if (busy) Icon(Icons.Outlined.PhotoCamera, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { showDevelop = true }, modifier = Modifier.size(56.dp)) {
                BadgedBox(badge = { if (queued > 0) Badge { Text("$queued") } }) {
                    Icon(Icons.Rounded.HourglassTop, contentDescription = "Développements en arrière-plan : $queued en attente")
                }
            }
        }
        StatusLine(status)
    }

    if (showRender) {
        ModalBottomSheet(
            onDismissRequest = { showRender = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Rendu", style = MaterialTheme.typography.titleLarge)
                SectionLabel("Exposition")
                Text(
                    "%+.1f EV".format(ev),
                    style = MaterialTheme.typography.bodyMedium.tnum(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = ev,
                    onValueChange = { v -> ev = (Math.round(v * 6f) / 6f); controller?.setExposureBias(ev); prefs.edit().putFloat("ev", ev).apply() },
                    valueRange = -ExposureMath.MAX_EV..ExposureMath.MAX_EV
                )
                SectionLabel("Style")
                ChoiceRow(RenderLook.entries, look, { it.label }, { look = it; controller?.look = it; prefs.edit().putString("look", it.name).apply() })
                SectionLabel("Qualité maximale")
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Développement différé", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Prend jusqu'à 12 trames selon la mémoire, puis fusionne et débruite plus fort en arrière-plan (batterie non faible requise). Réservé au RAW.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = deferred,
                        onCheckedChange = {
                            deferred = it
                            controller?.deferred = it
                            prefs.edit().putBoolean("deferred", it).apply()
                            if (it && android.os.Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        enabled = info.strategy == CaptureStrategy.RAW
                    )
                }
                Text(
                    "Le point noir, le contraste et la saturation sont appliqués à la fusion RAW ; l'exposition agit aussi sur le viseur.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (showDevelop) {
        val log = remember(showDevelop, workInfos, queued) { DevelopLog.recent(context) }
        ModalBottomSheet(
            onDismissRequest = { showDevelop = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Développements", style = MaterialTheme.typography.titleLarge)
                StatusLine(
                    UiStatus(
                        when {
                            running -> "Développement en cours… $queued rafale(s) restante(s)."
                            queued > 0 -> "$queued rafale(s) en attente (reprise quand la batterie et la température le permettent)."
                            else -> "Aucune rafale en attente."
                        }
                    )
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                if (log.isEmpty()) {
                    Text("Aucun développement terminé pour l'instant.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
                    log.forEach { e ->
                        Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.labelMedium.tnum(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StatusLine(UiStatus(e.message, if (e.ok) StatusKind.SUCCESS else StatusKind.ERROR))
                    }
                }
            }
        }
    }
}
