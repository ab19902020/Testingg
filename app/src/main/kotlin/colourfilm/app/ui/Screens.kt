package colourfilm.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import colourfilm.app.AppViewModel
import colourfilm.app.media.VideoInfo
import colourfilm.app.model.ModelManager
import colourfilm.app.work.ColouriseService
import colourfilm.app.work.Job
import colourfilm.app.work.JobStatus
import colourfilm.app.work.RunState
import colourfilm.core.ColouriseSettings
import colourfilm.core.StorageEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val AI_NOTE = "Colours are AI estimates. Black-and-white film doesn't record the original colours, " +
    "so results are plausible guesses, not historically accurate."

// ---------------------------------------------------------------- Home

@Composable
fun HomeScreen(vm: AppViewModel, pad: PaddingValues, onChoose: () -> Unit) {
    val jobs by vm.jobs.collectAsState()
    val busy by vm.busy.collectAsState()
    LaunchedEffect(Unit) { vm.refreshJobs() }
    LazyColumn(
        Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("ColourFilm", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("Turn your black-and-white videos into colour.", color = Muted, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            BigButton(if (busy) "Opening…" else "Choose video", Icons.Filled.VideoLibrary, enabled = !busy, onClick = onChoose)
            Spacer(Modifier.height(6.dp))
            Text("Processing happens on this phone. Your videos are not uploaded.", color = Muted, fontSize = 13.sp)
        }
        if (jobs.isNotEmpty()) item {
            Spacer(Modifier.height(8.dp))
            Text("Recent projects", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        items(jobs, key = { it.id }) { job -> JobRow(vm, job) }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun JobRow(vm: AppViewModel, job: Job) {
    val ctx = LocalContext.current
    val thumb by produceState<Bitmap?>(null, job.id, job.output) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val m = MediaMetadataRetriever()
                try {
                    if (job.output != null && File(job.output).exists()) m.setDataSource(job.output) else m.setDataSource(ctx, job.uri)
                    m.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 240)
                } finally { m.release() }
            }.getOrNull()
        }
    }
    var confirm by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Surface1).clickable { vm.openJob(job) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Surface2)) {
            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(job.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            val (label, color) = when (job.status) {
                JobStatus.DONE -> (if (job.preview) "Preview ready" else "Ready") to Good
                JobStatus.RUNNING, JobStatus.QUEUED -> "Colourising…" to Accent
                JobStatus.PAUSED -> "Paused" to Warn
                JobStatus.INTERRUPTED -> "Interrupted — tap to resume" to Warn
                JobStatus.FAILED -> "Stopped: tap for details" to Bad
                JobStatus.CANCELLED -> "Cancelled" to Muted
            }
            Text(label, color = color, fontSize = 14.sp)
            Text("${if (job.preview) "5 s preview · " else ""}${formatTime(job.durationUs)}", color = Muted, fontSize = 13.sp)
        }
        IconButton(onClick = { confirm = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.Delete, "Delete project", tint = Muted) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete project?") },
        text = { Text("This removes ColourFilm's copy and any unfinished work. Your original video and anything saved to the gallery are kept.") },
        confirmButton = { TextButton({ confirm = false; vm.delete(job) }) { Text("Delete") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Keep") } },
    )
}

// ---------------------------------------------------------------- Review

@Composable
fun ReviewScreen(vm: AppViewModel, info: VideoInfo, pad: PaddingValues) {
    val settings by vm.settings.collectAsState()
    val includeAudio by vm.includeAudio.collectAsState()
    val modelState by vm.models.state.collectAsState()
    var advanced by remember { mutableStateOf(false) }
    val player = rememberPlayer(info.uri)
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TopBar("Review", onBack = { vm.back() })
        VideoBox(player, info.displayWidth.toFloat() / info.displayHeight)
        Text(info.displayName, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)

        Card {
            InfoRow("Duration", formatTime(info.durationUs))
            InfoRow("Resolution", "${info.displayWidth} × ${info.displayHeight}" + if (info.rotation != 0) " (rotated ${info.rotation}°)" else "")
            InfoRow("Frame rate", if (info.frameRate > 0) "%.2f fps".format(info.frameRate) else "variable/unknown")
            InfoRow("Video codec", info.videoMime.removePrefix("video/").uppercase())
            InfoRow("Sound", info.audioMime?.removePrefix("audio/")?.uppercase()?.let { if (info.audioPassthrough) "$it (copied as-is)" else "$it → AAC" } ?: "None")
            InfoRow("File size", formatBytes(info.sizeBytes))
            val fps = if (info.frameRate > 0) info.frameRate else 30f
            val need = StorageEstimate.bytesNeeded(info.durationUs, StorageEstimate.videoBitrate(info.outWidth, info.outHeight, fps), 192_000)
            InfoRow("Space needed while working", "≈ ${formatBytes(need)}")
        }
        info.problems.forEach { Notice(it, Bad) }
        info.resizeReason?.let { Notice(it) }
        info.warnings.forEach { Notice(it) }

        if (modelState !is ModelManager.State.Ready) Notice(
            "First use downloads the AI model (206 MB) from Qualcomm AI Hub. Only the model is downloaded — your video stays on this phone.", Muted)

        BigButton("Colourise", Icons.Filled.AutoAwesome, enabled = info.canProcess) { player.pause(); vm.start(info) }
        if (info.durationUs > 12_000_000) SecondaryButton("Try a 5-second preview from ${formatTime(player.currentPosition * 1000)}",
            Icons.Filled.PlayCircle, Modifier.fillMaxWidth(), enabled = info.canProcess) {
            val at = player.currentPosition * 1000; player.pause(); vm.start(info, at)
        }
        Text(AI_NOTE, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)

        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { advanced = !advanced }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Advanced options", fontSize = 17.sp, modifier = Modifier.weight(1f))
            Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
        if (advanced) AdvancedOptions(settings, includeAudio && info.audioMime != null, info.audioMime != null && info.audioDecodable,
            { vm.settings.value = it }, { vm.includeAudio.value = it })
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AdvancedOptions(
    s: ColouriseSettings, audio: Boolean, audioAvailable: Boolean,
    onChange: (ColouriseSettings) -> Unit, onAudio: (Boolean) -> Unit,
) {
    Card {
        Text("Colour intensity: ${(s.intensity * 100).toInt()}%", fontSize = 15.sp)
        Slider(s.intensity, { onChange(s.copy(intensity = (it * 20).toInt() / 20f)) }, valueRange = 0.5f..1.5f, steps = 19)
        Text("Look", fontSize = 15.sp)
        Choice(listOf("Natural", "Vivid"), s.look.ordinal) { onChange(s.copy(look = ColouriseSettings.Look.entries[it])) }
        Text(if (s.look == ColouriseSettings.Look.NATURAL) "The model's own colour estimate." else
            "30% more saturated, limited so colours stay displayable.", color = Muted, fontSize = 13.sp)
        Text("Processing", fontSize = 15.sp)
        Choice(listOf("Best", "Balanced", "Fast"), s.speed.ordinal) { onChange(s.copy(speed = ColouriseSettings.Speed.entries[it])) }
        Text(when (s.speed) {
            ColouriseSettings.Speed.BEST -> "AI model runs on every frame. Slowest."
            ColouriseSettings.Speed.BALANCED -> "AI model runs at least every 2nd frame and whenever motion tracking is unsure. About 2× faster."
            ColouriseSettings.Speed.FAST -> "AI model runs at least every 4th frame; colours follow tracked motion in between. About 3× faster; fast action may look softer."
        }, color = Muted, fontSize = 13.sp)
        Text("Colour stability", fontSize = 15.sp)
        val stab = listOf(ColouriseSettings.Stability.LIGHT, ColouriseSettings.Stability.STANDARD, ColouriseSettings.Stability.STRONG)
        Choice(listOf("Light", "Standard", "Strong"), stab.indexOf(s.stability).coerceAtLeast(0)) { onChange(s.copy(stability = stab[it])) }
        Text("How firmly colours are held steady along tracked motion. Stronger reduces flicker; lighter reacts faster to genuine changes.",
            color = Muted, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Edge-aware colour", fontSize = 15.sp)
                Text("Keeps colour inside object outlines (less bleeding).", color = Muted, fontSize = 13.sp)
            }
            Switch(s.edgeRefine, { onChange(s.copy(edgeRefine = it)) })
        }
        if (audioAvailable) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep original sound", fontSize = 15.sp, modifier = Modifier.weight(1f))
            Switch(audio, onAudio)
        }
    }
}

@Composable
private fun Choice(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(i == selected, { onSelect(i) }, SegmentedButtonDefaults.itemShape(i, options.size), Modifier.heightIn(min = 48.dp)) { Text(o) }
        }
    }
}

// ---------------------------------------------------------------- Processing

@Composable
fun ProcessingScreen(vm: AppViewModel, jobId: String, pad: PaddingValues) {
    val run by vm.run.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val job = jobs.firstOrNull { it.id == jobId } ?: vm.store.load(jobId)
    val live = run?.takeIf { it.jobId == jobId }
    LaunchedEffect(live?.phase) { if (live?.phase == RunState.Phase.DONE) vm.go(colourfilm.app.Screen.Result(jobId)) }
    var confirmCancel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TopBar("Colourising", onBack = { vm.back() })
        if (job == null) { Text("This project no longer exists."); return@Column }
        Text(job.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (job.preview) Text("5-second preview from ${formatTime(job.startUs)}", color = Muted)

        val active = live != null && live.phase !in setOf(RunState.Phase.FAILED, RunState.Phase.PAUSED, RunState.Phase.CANCELLED, RunState.Phase.DONE)
        Card {
            if (active && live != null) {
                Text(live.message, fontSize = 17.sp)
                if (live.fraction != null && live.phase == RunState.Phase.PROCESSING) {
                    LinearProgressIndicator({ live.fraction }, Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)))
                    InfoRow("Processed", "${formatTime(live.processedUs)} of ${formatTime(live.totalUs)}")
                    InfoRow("Time left", live.etaMs?.let { "about " + ColouriseService.formatDuration(it) } ?: "measuring…")
                    live.speedRatio?.let { InfoRow("Measured speed", "%.2f× real time".format(it)) }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                live.backend?.let { InfoRow("AI runs on", it) }
                live.msPerModelRun?.let { InfoRow("Model time per run", "%.0f ms".format(it)) }
                live.thermal?.let { Notice(it) }
            } else {
                val doneUs = job.segments.sumOf { minOf(job.endUs, job.startUs + (it.index + 1) * job.segmentUs) - (job.startUs + it.index * job.segmentUs) }
                val status = live?.message?.takeIf { live.phase != RunState.Phase.DONE } ?: when (job.status) {
                    JobStatus.INTERRUPTED -> "Processing was interrupted (the app was closed or the phone restarted)."
                    JobStatus.PAUSED -> "Paused."
                    JobStatus.FAILED -> job.error ?: "Processing stopped."
                    JobStatus.QUEUED -> "Waiting to start…"
                    else -> job.status.name.lowercase()
                }
                Text(status, fontSize = 16.sp, color = if (job.status == JobStatus.FAILED) Bad else Color.Unspecified)
                if (doneUs > 0) InfoRow("Saved progress", "${formatTime(doneUs)} of ${formatTime(job.durationUs)}")
            }
        }
        if (active) {
            Text("You can leave the app — processing continues in the background with a notification. " +
                "Pausing keeps finished parts; it resumes from the last saved point (up to 15 s of video is redone).",
                color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Pause", Icons.Filled.Pause, Modifier.weight(1f)) { vm.pause() }
                SecondaryButton("Cancel", Icons.Filled.Close, Modifier.weight(1f)) { confirmCancel = true }
            }
        } else if (job.status != JobStatus.DONE) {
            BigButton("Resume", Icons.Filled.PlayArrow) { vm.resume(job) }
            SecondaryButton("Discard", Icons.Filled.Delete, Modifier.fillMaxWidth()) { vm.cancel(jobId) }
        }
    }
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Cancel colourising?") },
        text = { Text("All progress for this video will be discarded. Use Pause instead to continue later.") },
        confirmButton = { TextButton({ confirmCancel = false; vm.cancel(jobId) }) { Text("Discard progress") } },
        dismissButton = { TextButton({ confirmCancel = false }) { Text("Keep going") } },
    )
}

// ---------------------------------------------------------------- Result

@Composable
fun ResultScreen(vm: AppViewModel, jobId: String, pad: PaddingValues, launch: (Intent) -> Unit) {
    val jobs by vm.jobs.collectAsState()
    val busy by vm.busy.collectAsState()
    val job = jobs.firstOrNull { it.id == jobId } ?: vm.store.load(jobId)
    if (job?.output == null || !File(job.output).exists()) {
        Column(Modifier.padding(pad).padding(20.dp)) {
            TopBar("Result", onBack = { vm.back() }); Text("The exported file is missing. It may have been deleted.")
        }
        return
    }
    val out = Uri.fromFile(File(job.output))
    val player = rememberPlayer(out)
    var compareAtUs by remember { mutableLongStateOf(minOf(job.durationUs / 2, 3_000_000L)) }
    Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TopBar(if (job.preview) "Preview" else "Your colour video", onBack = { vm.back() })
        Text("Before / after — drag the divider", color = Muted, fontSize = 14.sp)
        CompareView(job, compareAtUs)
        Slider(compareAtUs.toFloat(), { compareAtUs = it.toLong() }, valueRange = 0f..maxOf(1f, job.durationUs.toFloat() - 1))
        Text("Play", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        val ar = remember(job.output) { videoAspect(job.output) }
        VideoBox(player, ar)
        BigButton(if (job.savedUri != null) "Saved ✓ — save again" else "Save to gallery", Icons.Filled.Download, enabled = !busy) { vm.saveToGallery(job) }
        SecondaryButton("Share", Icons.Filled.Share, Modifier.fillMaxWidth()) { vm.shareIntent(job)?.let(launch) }
        if (job.preview) SecondaryButton("Colourise the full video", Icons.Filled.AutoAwesome, Modifier.fillMaxWidth()) {
            vm.openVideo(job.uri)
        }
        Card {
            InfoRow("Format", "H.264 MP4")
            InfoRow("File size", formatBytes(File(job.output).length()))
            job.backend?.let { InfoRow("AI ran on", it) }
            if (job.processingMs > 0 && job.framesDone > 0) InfoRow("Measured processing", "%.1f frames/s".format(job.framesDone * 1000.0 / job.processingMs))
        }
        Text(AI_NOTE, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        Spacer(Modifier.height(24.dp))
    }
}

private fun videoAspect(path: String): Float = runCatching {
    val m = MediaMetadataRetriever()
    try {
        m.setDataSource(path)
        val w = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
        val h = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
        val r = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (r % 180 == 0) w / h else h / w
    } finally { m.release() }
}.getOrDefault(16f / 9f)

@Composable
private fun CompareView(job: Job, atUs: Long) {
    val ctx = LocalContext.current
    val frames by produceState<Pair<Bitmap?, Bitmap?>>(null to null, job.id, atUs / 250_000) {
        value = withContext(Dispatchers.IO) {
            fun grab(setup: (MediaMetadataRetriever) -> Unit, t: Long): Bitmap? = runCatching {
                val m = MediaMetadataRetriever()
                try { setup(m); m.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, 1280, 1280) } finally { m.release() }
            }.getOrNull()
            grab({ it.setDataSource(ctx, job.uri) }, job.startUs + atUs) to grab({ it.setDataSource(job.output) }, atUs)
        }
    }
    var split by remember { mutableFloatStateOf(0.5f) }
    val (before, after) = frames
    val ratio = after?.let { it.width.toFloat() / it.height } ?: (16f / 9f)
    Box(
        Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(14.dp)).background(Color.Black)
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> split = (change.position.x / size.width).coerceIn(0f, 1f) }
            }
            .pointerInput(Unit) { detectTapGestures { split = (it.x / size.width).coerceIn(0f, 1f) } },
    ) {
        after?.let { Image(it.asImageBitmap(), "Colourised frame", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        before?.let {
            Image(it.asImageBitmap(), "Original frame", Modifier.fillMaxSize().drawWithContent {
                clipRect(right = size.width * split) { this@drawWithContent.drawContent() }
            }, contentScale = ContentScale.Fit)
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val x = size.width * split
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), 3.dp.toPx())
            drawCircle(Color.White, 14.dp.toPx(), Offset(x, size.height / 2))
        }
        Text("Original", Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(6.dp, 2.dp), fontSize = 12.sp)
        Text("Colour", Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(6.dp, 2.dp), fontSize = 12.sp)
        if (before == null && after == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}

// ---------------------------------------------------------------- shared bits

@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.size(48.dp)) { Icon(Icons.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun rememberPlayer(uri: Uri): ExoPlayer {
    val ctx = LocalContext.current
    val player = remember(uri) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(uri)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@Composable
private fun VideoBox(player: ExoPlayer, aspect: Float) {
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.4f, 3f)).clip(RoundedCornerShape(14.dp)).background(Color.Black),
    )
}
