package dev.roastbook.app.ui.add

import dev.roastbook.app.ui.theme.Honey
import dev.roastbook.app.ui.components.focusRing
import android.Manifest
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.roastbook.app.R
import dev.roastbook.app.ui.components.PermissionReasonDialog
import dev.roastbook.app.ui.components.PermissionAsk
import dev.roastbook.app.ui.components.PermissionAskState
import dev.roastbook.app.ui.components.PermissionAsks
import dev.roastbook.app.ui.components.rememberPermissionAsk
import java.io.File
import java.util.concurrent.Executor

/**
 * The capture screen: a live camera preview with the current step's instruction on top, and the
 * staged photos, Library, the shutter and Done at the bottom.
 */
@Composable
fun AddScreen(onCancel: () -> Unit, onCreated: (String) -> Unit, vm: AddViewModel = hiltViewModel()) {
    val photos by vm.photos.collectAsState()
    val saving by vm.saving.collectAsState()
    val cameraReady by vm.cameraReady.collectAsState()
    // Leaving discards the staged photos; while the import runs, Back and Cancel wait for it.
    BackHandler { if (!saving) { vm.discard(); onCancel() } }
    val launchers = rememberCaptureLaunchers(onPicked = vm::addFromLibrary)
    val hasCamera = launchers.hasCamera

    val screenName = stringResource(R.string.add_title)
    Box(Modifier.fillMaxSize().background(Color.Black).semantics { paneTitle = screenName }) {
        if (hasCamera) {
            CameraPreview(launchers.imageCapture, onStarted = vm::cameraStarted, modifier = Modifier.fillMaxSize())
        } else {
            CameraPermissionPrompt(launchers, Modifier.align(Alignment.Center))
        }
        // No usable camera (none at all, or it failed to start): say so over the empty preview.
        AnnouncedLine(hasCamera && cameraReady == false, Modifier.align(Alignment.Center)) {
            Text(
                stringResource(R.string.add_camera_failed), color = Color.White, style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp),
            )
        }

        // Scrims keep the white controls and the instruction readable over any camera image (a
        // bright bag, a white counter): text contrast can't depend on what the lens happens to see.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))))

        AddTopBar(CaptureStep.of(photos.map { it.role }), cancelEnabled = !saving, onCancel = { vm.discard(); onCancel() },
            modifier = Modifier.align(Alignment.TopCenter))
        CaptureControls(vm, launchers, cameraReady = cameraReady, onCreated = onCreated, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * What the capture screen needs from Android: the camera permission and its ask, the photo picker,
 * and the camera use case the shutter fires.
 */
@Stable
internal class CaptureLaunchers(
    val camera: PermissionAskState,
    val imageCapture: ImageCapture,
    private val permission: ManagedActivityResultLauncher<String, Boolean>,
    private val picker: ManagedActivityResultLauncher<PickVisualMediaRequest, List<Uri>>,
    private val mainExecutor: Executor,
) {
    val hasCamera: Boolean get() = camera.value == PermissionAsk.GRANTED

    fun askCamera() { camera.markAsked(); permission.launch(Manifest.permission.CAMERA) }

    fun openLibrary() = picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    /** Writes one photo to [file] and reports on the main thread. */
    fun takePicture(file: File, onSaved: () -> Unit, onError: () -> Unit) {
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = onSaved()
            override fun onError(exception: ImageCaptureException) = onError()
        })
    }
}

/**
 * The camera is asked for only after one sentence of why, and only when Android would show its
 * dialog; refused for good, the button opens Android's settings instead. Library works either way.
 */
@Composable
internal fun rememberCaptureLaunchers(onPicked: (List<Uri>) -> Unit): CaptureLaunchers {
    val context = LocalContext.current
    val camera = rememberPermissionAsk(Manifest.permission.CAMERA)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> camera.answered(granted) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 8)) { uris -> onPicked(uris) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    val mainExecutor: Executor = remember { ContextCompat.getMainExecutor(context) }
    return remember(camera, permission, picker, imageCapture, mainExecutor) {
        CaptureLaunchers(camera, imageCapture, permission, picker, mainExecutor)
    }
}

/**
 * In place of the preview while the camera isn't allowed: why it's needed, and the one button that
 * helps. Before Android first asks, the reason comes as a dialog (Continue asks; Not now leaves the
 * reason here with Allow camera, and Library keeps working).
 */
@Composable
private fun CameraPermissionPrompt(launchers: CaptureLaunchers, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var reasonDismissed by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val ask = launchers.camera.value
        if (ask == PermissionAsk.FIRST_ASK && !reasonDismissed) {
            PermissionReasonDialog(
                title = stringResource(R.string.camera_reason_title),
                reason = stringResource(R.string.add_camera_why),
                onContinue = launchers::askCamera,
                onNotNow = { reasonDismissed = true },
            )
            return@Column
        }
        Text(
            stringResource(if (ask == PermissionAsk.FIRST_ASK) R.string.add_camera_why else R.string.add_camera_permission),
            color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        when (ask) {
            PermissionAsk.FIRST_ASK -> Button(modifier = Modifier.focusRing(CircleShape, onFill = true), onClick = launchers::askCamera) { Text(stringResource(R.string.add_camera_permission_button)) }
            PermissionAsk.ASK_AGAIN ->
                Button(modifier = Modifier.focusRing(CircleShape, onFill = true), onClick = launchers::askCamera) { Text(stringResource(R.string.add_camera_permission_button)) }
            else ->
                Button(modifier = Modifier.focusRing(CircleShape, onFill = true), onClick = { PermissionAsks.openAppSettings(context) }) { Text(stringResource(R.string.permission_open_settings)) }
        }
    }
}

/**
 * Cancel, and the current step's instruction as the title, where the eye starts. It wraps instead of
 * truncating (200% text, narrow windows); its scrim grows with it: solid behind the text, fading out below.
 */
@Composable
private fun AddTopBar(step: CaptureStep, cancelEnabled: Boolean, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val instruction = stringResource(
        when (step) {
            CaptureStep.FRONT -> R.string.add_step_front
            CaptureStep.BACK -> R.string.add_step_back
            CaptureStep.MORE -> R.string.add_step_more
        },
    )
    Row(
        modifier.fillMaxWidth()
            .drawBehind {
                val fade = 32.dp.toPx()
                val solidUntil = ((size.height - fade) / size.height).coerceIn(0f, 1f)
                drawRect(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.6f), solidUntil to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent))
            }
            .statusBarsPadding().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(modifier = Modifier.focusRing(CircleShape, color = Honey), onClick = onCancel, enabled = cancelEnabled) {
            Icon(Icons.Default.Close, stringResource(R.string.detail_cancel), tint = Color.White)
        }
        Spacer(Modifier.width(4.dp))
        // TalkBack: a heading, announced again whenever the step changes.
        Text(instruction, color = Color.White, style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading(); liveRegion = LiveRegionMode.Polite })
    }
}

/**
 * The bottom of the capture screen: error and undo lines, the strip of staged photos, then Library,
 * the shutter and Done.
 */
@Composable
private fun CaptureControls(
    vm: AddViewModel, launchers: CaptureLaunchers, cameraReady: Boolean?, onCreated: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val photos by vm.photos.collectAsState()
    val saving by vm.saving.collectAsState()
    val saveFailed by vm.saveFailed.collectAsState()
    val importing by vm.importing.collectAsState()
    val importFailed by vm.importFailed.collectAsState()
    val removed by vm.removed.collectAsState()
    val capturing by vm.capturing.collectAsState()
    val captureFailed by vm.captureFailed.collectAsState()
    val hasCamera = launchers.hasCamera
    Column(modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        AnnouncedLine(importFailed) { ErrorLine(R.string.add_import_failed) }
        AnnouncedLine(captureFailed) { ErrorLine(R.string.add_capture_failed) }
        AnnouncedLine(saveFailed) { ErrorLine(R.string.add_save_failed) }
        AnnouncedLine(removed.isNotEmpty()) { RemovedUndo(removed, onUndo = vm::undoRemove, onExpired = { vm.undoExpired(removed) }) }
        if (photos.isNotEmpty()) {
            // Every column is as wide as the widest label chip could be at this text size, so the
            // thumbnails sit at an even spacing whatever their labels say; more than fit scroll.
            val columnWidth = rememberThumbColumnWidth()
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(photos) { i, p ->
                    StagedThumb(
                        p, i + 1, photos.size, columnWidth, enabled = !saving,
                        onRole = { vm.setRole(i, it) }, onRemove = { vm.remove(i) },
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledIconButton(
                onClick = launchers::openLibrary,
                enabled = !saving,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.2f), contentColor = Color.White,
                ),
                modifier = Modifier.size(52.dp).focusRing(CircleShape, color = Honey),
            ) { Icon(Icons.Default.PhotoLibrary, stringResource(R.string.add_library)) }
            Shutter(
                on = !saving && hasCamera && cameraReady == true, lit = hasCamera && cameraReady == true,
                onShoot = {
                    val file = vm.newCaptureFile()
                    launchers.takePicture(file, onSaved = { vm.addCaptured(file) }, onError = { vm.captureFailed(file) })
                },
            )
            FilledIconButton(
                onClick = { vm.save(onCreated) }, enabled = photos.isNotEmpty() && !saving && importing == 0 && capturing == 0,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = Color.White.copy(alpha = 0.12f), disabledContentColor = Color.White.copy(alpha = 0.4f),
                ),
                modifier = Modifier.size(52.dp).focusRing(CircleShape, color = Honey),
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Default.Check, stringResource(R.string.add_done))
            }
        }
    }
}

/** The shutter: a white disc in a translucent ring. Off (and dimmed unless [lit]) until a camera is running. */
@Composable
private fun Shutter(on: Boolean, lit: Boolean, onShoot: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)).padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = { if (on) onShoot() },
            // No camera yet, or none that started: the shutter is off (Library still works).
            enabled = on,
            modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.White.copy(alpha = if (lit) 1f else 0.4f)).focusRing(CircleShape, onFill = true, color = Honey),
        ) {
            // The white disc is the whole affordance; the invisible icon only carries the label.
            Icon(Icons.Default.PhotoCamera, stringResource(R.string.add_take_photo), tint = Color.Transparent)
        }
    }
}

/**
 * A message TalkBack reads out when it appears. The live region is always composed and [content]
 * appears inside it while [shown]: a live region that appears together with its text isn't announced.
 */
@Composable
private fun AnnouncedLine(shown: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.semantics { liveRegion = LiveRegionMode.Polite }) { if (shown) content() }
}

@Composable
private fun ErrorLine(text: Int) {
    Text(stringResource(text), color = MaterialTheme.colorScheme.errorContainer,
        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
}

/** The live preview. [onStarted] hears true once the camera is bound, false when it can't be, null when it's let go. */
@Composable
private fun CameraPreview(imageCapture: ImageCapture, onStarted: (Boolean?) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            // A device with no camera at all, or a camera service that fails to start, ends up here.
            val bound = runCatching {
                val p = future.get().also { provider = it }
                // The back camera, or the front one on a device without one ([chooseLens]). Neither the
                // preview ([bagPreview]) nor the saved photo is mirrored.
                val lens = chooseLens(p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA), p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA))
                    ?: throw IllegalStateException("no camera")
                val selector = if (lens == Lens.BACK) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                val preview = bagPreview().also { it.surfaceProvider = previewView.surfaceProvider }
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
            }
            bound.exceptionOrNull()?.let { Log.w("Add", "camera unavailable: ${it::class.simpleName}") }
            onStarted(bound.isSuccess)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            onStarted(null)
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
