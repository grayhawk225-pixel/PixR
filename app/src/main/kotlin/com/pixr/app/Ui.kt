package com.pixr.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class StatusKind { INFO, SUCCESS, ERROR }

data class UiStatus(val text: String, val kind: StatusKind = StatusKind.INFO)

/** Ligne de statut : l'état est toujours porté par une icône ET un texte, jamais par la couleur seule. */
@Composable
fun StatusLine(status: UiStatus, modifier: Modifier = Modifier) {
    val (icon, desc, tint) = when (status.kind) {
        StatusKind.ERROR -> Triple(Icons.Rounded.Error, "Erreur", MaterialTheme.colorScheme.error)
        StatusKind.SUCCESS -> Triple(Icons.Rounded.CheckCircle, "Succès", MaterialTheme.colorScheme.onSurface)
        StatusKind.INFO -> Triple(Icons.Outlined.Info, "Information", MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, desc, Modifier.size(18.dp), tint = tint)
        Text(
            status.text,
            style = MaterialTheme.typography.bodySmall.tnum(),
            color = if (status.kind == StatusKind.ERROR) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Choix exclusif court (×2/×4, JPEG/PNG, 1x…4x) : SingleChoiceSegmentedButtonRow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: (T) -> Boolean = { true },
    checkIcon: Boolean = true
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                enabled = enabled(o),
                modifier = Modifier.heightIn(min = 48.dp),
                icon = { if (checkIcon) SegmentedButtonDefaults.Icon(o == selected) },
                label = { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}

fun ProcessingMode.shortLabel(): String = when (this) {
    ProcessingMode.STANDARD -> "Standard"
    ProcessingMode.COMPRESSED -> "Compressée"
    ProcessingMode.TEXT -> "Texte"
}

@Composable
fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
}
