package com.pixr.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.roundToInt

/** Comparateur Avant / Après : glisser (ou toucher) pour déplacer le séparateur. */
@Composable
fun BeforeAfter(before: Bitmap, after: Bitmap, modifier: Modifier = Modifier) {
    var frac by remember { mutableFloatStateOf(0.5f) }
    val beforeImg = remember(before) { before.asImageBitmap() }
    val afterImg = remember(after) { after.asImageBitmap() }
    val handle = MaterialTheme.colorScheme.onSurface
    val handleCore = MaterialTheme.colorScheme.surfaceContainerLowest
    val shadow = Color.Black.copy(alpha = 0.5f)
    Box(
        modifier
            .semantics { contentDescription = "Comparateur avant après : glisser pour déplacer le séparateur" }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    frac = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures { pos -> frac = (pos.x / size.width).coerceIn(0f, 1f) }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val scale = min(size.width / after.width, size.height / after.height)
            val dw = (after.width * scale).roundToInt()
            val dh = (after.height * scale).roundToInt()
            val left = ((size.width - dw) / 2f).roundToInt()
            val top = ((size.height - dh) / 2f).roundToInt()
            val divX = left + dw * frac
            clipRect(left = left.toFloat(), top = top.toFloat(), right = divX, bottom = (top + dh).toFloat()) {
                drawImage(beforeImg, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh))
            }
            clipRect(left = divX, top = top.toFloat(), right = (left + dw).toFloat(), bottom = (top + dh).toFloat()) {
                drawImage(afterImg, dstOffset = IntOffset(left, top), dstSize = IntSize(dw, dh))
            }
            drawLine(shadow, Offset(divX, top.toFloat()), Offset(divX, (top + dh).toFloat()), strokeWidth = 8f)
            drawLine(handle, Offset(divX, top.toFloat()), Offset(divX, (top + dh).toFloat()), strokeWidth = 3f)
            drawCircle(shadow, radius = 26f, center = Offset(divX, top + dh / 2f))
            drawCircle(handle, radius = 22f, center = Offset(divX, top + dh / 2f))
            drawCircle(handleCore, radius = 8f, center = Offset(divX, top + dh / 2f))
        }
        Text(
            "Avant",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            "Après",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
