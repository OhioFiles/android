package cloud.ohiofiles.app.ui.preview

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable fun ZoomablePreview(modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }; var rotation by remember { mutableFloatStateOf(0f) }; var size by remember { mutableStateOf(IntSize.Zero) }
    fun reset() { scale = 1f; pan = Offset.Zero; rotation = 0f }
    Box(modifier.clipToBounds().onSizeChanged { size = it }) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTransformGestures { _, move, zoom, turn ->
            scale = (scale * zoom).coerceIn(1f, 8f); rotation += turn
            val maximumX = size.width * (scale - 1) / 2f; val maximumY = size.height * (scale - 1) / 2f
            pan = Offset((pan.x + move.x).coerceIn(-maximumX, maximumX), (pan.y + move.y).coerceIn(-maximumY, maximumY))
        } }.pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1) reset() else scale = 2.5f }) }) {
            content(Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y; rotationZ = rotation })
        }
        Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .93f), shape = MaterialTheme.shapes.extraLarge) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { scale = (scale / 1.5f).coerceAtLeast(1f); if (scale == 1f) pan = Offset.Zero }, enabled = scale > 1) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
                Text("${(scale * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { scale = (scale * 1.5f).coerceAtMost(8f) }) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) { Icon(Icons.Outlined.RotateRight, "Rotate clockwise") }
                IconButton(onClick = ::reset) { Icon(Icons.Outlined.CenterFocusStrong, "Reset zoom and rotation") }
            }
        }
    }
}
