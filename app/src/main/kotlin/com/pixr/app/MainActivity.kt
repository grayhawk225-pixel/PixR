package com.pixr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            PixRTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { PixRApp() }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector, val iconSelected: ImageVector) {
    ATELIER("Atelier", Icons.Outlined.Tune, Icons.Rounded.Tune),
    MULTI("Multi & Lot", Icons.Outlined.Collections, Icons.Rounded.Collections),
    CAMERA("Caméra", Icons.Outlined.PhotoCamera, Icons.Rounded.PhotoCamera)
}

@Composable
fun PixRApp() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = selected == i,
                        onClick = { selected = i },
                        icon = { Icon(if (selected == i) t.iconSelected else t.icon, contentDescription = null) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            // Les écrans restent composés (taille 0 quand masqués) pour conserver photos et réglages.
            Box(if (selected == 0) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) { AtelierScreen() }
            Box(if (selected == 1) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds()) { MultiBatchScreen() }
            if (selected == 2) CameraScreen(onOpenAtelier = { selected = 0 })
        }
    }
}
