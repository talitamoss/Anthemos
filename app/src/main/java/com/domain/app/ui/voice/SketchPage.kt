// app/src/main/java/com/domain/app/ui/voice/SketchPage.kt
package com.domain.app.ui.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domain.app.media.Saved
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Sketch (docs/anthemos-2.md, "Record and Sketch"): a pad to scribble on with
 * a finger, saved as a picture, and a strip of past sketches to open or delete.
 */
@Composable
fun SketchPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember { Saved.sketches(context) }
    var tick by remember { mutableIntStateOf(0) }
    val files = remember(tick) { saved.list() }
    val strokes = remember { mutableStateListOf<SnapshotStateList<Offset>>() }
    var pad by remember { mutableStateOf(IntSize.Zero) }
    var looking by remember { mutableStateOf<File?>(null) }
    var deleting by remember { mutableStateOf<File?>(null) }
    var leaving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val ink = MaterialTheme.colorScheme.onSurface
    val paper = MaterialTheme.colorScheme.background
    val width = with(LocalDensity.current) { 4.dp.toPx() }

    fun sync() = scope.launch { withContext(Dispatchers.IO) { runCatching { saved.sync() } } }
    fun close() = if (strokes.isEmpty()) onClose() else leaving = true

    LaunchedEffect(Unit) { sync() }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize(), color = paper) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Sketch", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                }

                Canvas(
                    Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline)
                        .onSizeChanged { pad = it }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { strokes.add(mutableStateListOf(it)) },
                                onDrag = { change, _ -> strokes.lastOrNull()?.add(change.position) }
                            )
                        }
                ) {
                    strokes.forEach { drawPath(path(it), ink, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
                }

                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) { Text("Undo") }
                    OutlinedButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) { Text("Clear") }
                    Button(
                        onClick = {
                            val png = picture(strokes, pad, ink, paper, width)
                            strokes.clear()
                            message = null
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { saved.save(png) } }
                                    .onFailure { message = "Couldn't save it: ${it.message ?: "try again"}" }
                                tick++
                                sync()
                            }
                        },
                        enabled = strokes.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("Save") }
                }

                if (files.isNotEmpty()) {
                    LazyRow(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(files, key = { it.name }) { f ->
                            val thumb = remember(f) { image(f, 160) }
                            thumb?.let {
                                Image(
                                    it,
                                    contentDescription = "A saved sketch",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(64.dp).border(1.dp, MaterialTheme.colorScheme.outline).clickable { looking = f }
                                )
                            }
                        }
                    }
                }
            }
        }

        looking?.let { f ->
            val big = remember(f) { image(f, 1200) }
            AlertDialog(
                onDismissRequest = { looking = null },
                text = {
                    big?.let { Image(it, contentDescription = "A saved sketch", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().height(360.dp).border(1.dp, MaterialTheme.colorScheme.outline)) }
                },
                confirmButton = { TextButton(onClick = { looking = null }) { Text("Close") } },
                dismissButton = { TextButton(onClick = { deleting = f; looking = null }) { Text("Delete") } }
            )
        }

        deleting?.let { f ->
            AlertDialog(
                onDismissRequest = { deleting = null },
                text = { Text("Delete this sketch, from the phone and your pod?") },
                confirmButton = {
                    TextButton(onClick = {
                        deleting = null
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { saved.delete(f) } }
                                .onFailure { message = "Couldn't delete it from the pod: ${it.message ?: "try again"}" }
                            tick++
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep") } }
            )
        }

        if (leaving) {
            AlertDialog(
                onDismissRequest = { leaving = false },
                text = { Text("Leave without saving this sketch?") },
                confirmButton = { TextButton(onClick = { leaving = false; onClose() }) { Text("Leave") } },
                dismissButton = { TextButton(onClick = { leaving = false }) { Text("Stay") } }
            )
        }
    }
}

private fun path(points: List<Offset>) = Path().apply {
    points.firstOrNull()?.let { moveTo(it.x, it.y) }
    points.drop(1).forEach { lineTo(it.x, it.y) }
}

/** The strokes as a PNG the size of the pad, on the page's own colour. */
private fun picture(strokes: List<List<Offset>>, size: IntSize, ink: Color, paper: Color, width: Float): ByteArray {
    val bitmap = Bitmap.createBitmap(size.width.coerceAtLeast(1), size.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(paper.toArgb())
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.forEach { points ->
        val p = android.graphics.Path()
        points.firstOrNull()?.let { p.moveTo(it.x, it.y) }
        points.drop(1).forEach { p.lineTo(it.x, it.y) }
        canvas.drawPath(p, paint)
    }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
}

/** A saved sketch, scaled down to about [px] across. */
private fun image(file: File, px: Int): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= px) sample *= 2
    BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()
