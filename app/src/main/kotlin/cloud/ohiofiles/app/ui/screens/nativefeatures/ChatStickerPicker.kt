package cloud.ohiofiles.app.ui.screens.nativefeatures

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun BlobatarSticker(mood: String, modifier: Modifier = Modifier) {
    val fill = when (mood) { "love", "shy" -> MaterialTheme.colorScheme.tertiaryContainer; "sad", "sleepy", "sick" -> MaterialTheme.colorScheme.secondaryContainer; "mad", "scared" -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.primaryContainer }
    val ink = when (mood) { "love", "shy" -> MaterialTheme.colorScheme.onTertiaryContainer; "sad", "sleepy", "sick" -> MaterialTheme.colorScheme.onSecondaryContainer; "mad", "scared" -> MaterialTheme.colorScheme.onErrorContainer; else -> MaterialTheme.colorScheme.onPrimaryContainer }
    Canvas(modifier.size(104.dp).semantics { contentDescription = "${mood.replaceFirstChar(Char::uppercase)} Blobatar sticker" }) {
        val w = size.width; val h = size.height
        val blob = Path().apply { moveTo(w*.12f,h*.62f); cubicTo(w*.01f,h*.16f,w*.36f,h*.01f,w*.53f,h*.08f); cubicTo(w*.87f,h*-.03f,w*.97f,h*.28f,w*.9f,h*.59f); cubicTo(w*1.02f,h*.99f,w*.67f,h*.96f,w*.52f,h*.88f); cubicTo(w*.26f,h*1.01f,w*.02f,h*.88f,w*.12f,h*.62f); close() }
        drawPath(blob, fill)
        val stroke = w*.035f
        val eyeY = h*.44f
        if (mood in listOf("sleepy", "smug", "sick")) {
            drawLine(ink, Offset(w*.30f,eyeY), Offset(w*.40f,eyeY), stroke, StrokeCap.Round); drawLine(ink, Offset(w*.60f,eyeY), Offset(w*.70f,eyeY), stroke, StrokeCap.Round)
        } else {
            drawCircle(ink, w*.027f, Offset(w*.35f,eyeY))
            if (mood == "wink") drawLine(ink, Offset(w*.60f,eyeY), Offset(w*.70f,eyeY), stroke, StrokeCap.Round) else drawCircle(ink,w*.027f,Offset(w*.65f,eyeY))
        }
        when (mood) {
            "surprised", "scared" -> drawOval(ink, Offset(w*.45f,h*.57f), Size(w*.10f,h*.15f))
            "sad", "sick", "unsure" -> drawArc(ink, 205f, 130f, false, Offset(w*.36f,h*.60f), Size(w*.28f,h*.20f), style = Stroke(stroke, cap = StrokeCap.Round))
            "thinking", "idle", "sleepy" -> drawLine(ink, Offset(w*.43f,h*.65f), Offset(w*.58f,h*.65f), stroke, StrokeCap.Round)
            else -> drawArc(ink, 15f, 150f, false, Offset(w*.35f,h*.50f), Size(w*.30f,h*.22f), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (mood == "mad") { drawLine(ink, Offset(w*.27f,h*.30f),Offset(w*.42f,h*.35f),stroke,StrokeCap.Round); drawLine(ink,Offset(w*.58f,h*.35f),Offset(w*.73f,h*.30f),stroke,StrokeCap.Round) }
        if (mood in listOf("love", "shy", "happy")) { drawOval(ink.copy(alpha=.14f),Offset(w*.21f,h*.54f),Size(w*.14f,h*.08f)); drawOval(ink.copy(alpha=.14f),Offset(w*.65f,h*.54f),Size(w*.14f,h*.08f)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ChatStickerPicker(onChoose: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Say it with a Blobatar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Text("Choose a mood, then tap Send in your conversation.", modifier = Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.fillMaxWidth().heightIn(max = 440.dp), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(blobatarMoods, key = { it }) { mood -> FilledTonalButton(onClick = { onChoose(mood) }, shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(8.dp)) { Column { BlobatarSticker(mood, Modifier.size(68.dp)); Text(mood.replaceFirstChar(Char::uppercase), style = MaterialTheme.typography.labelMedium) } } }
        }
    }
}
