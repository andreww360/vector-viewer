package com.anddesign.vectorviewer.ui.viewer

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.print.PrintManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.anddesign.vectorviewer.R
import com.anddesign.vectorviewer.data.Repos
import com.anddesign.vectorviewer.render.LoadedDocument
import com.anddesign.vectorviewer.render.PdfFilePrintAdapter
import com.anddesign.vectorviewer.ui.components.Pill
import com.anddesign.vectorviewer.ui.theme.Accent
import com.anddesign.vectorviewer.util.Backdrop
import com.anddesign.vectorviewer.util.FileUtils
import com.anddesign.vectorviewer.util.LengthUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Paint as APaint

/** Busca la app de Claude entre las apps que reciben imágenes compartidas. */
private fun findClaudePackage(context: Context): String? {
    val probe = Intent(Intent.ACTION_SEND).setType("image/png")
    return context.packageManager.queryIntentActivities(probe, 0)
        .map { it.activityInfo.packageName }
        .firstOrNull { it.contains("anthropic", ignoreCase = true) }
}

private class SharpFrame(val bitmap: Bitmap, val page: Int, val scale: Float, val offset: Offset)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(uri: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ViewerViewModel = viewModel(
        key = "viewer:$uri",
        factory = viewModelFactory { initializer { ViewerViewModel(app, uri) } },
    )

    LaunchedEffect(Unit) { vm.ensureLoaded() }
    DisposableEffect(Unit) {
        onDispose {
            // Al salir del visor (no al rotar) liberamos el documento y los bitmaps.
            if ((context as? Activity)?.isChangingConfigurations != true) vm.release()
        }
    }

    val state = vm.state
    val ready = (state as? ViewerViewModel.UiState.Ready)?.doc
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var measureMode by rememberSaveable { mutableStateOf(false) }
    var backdrop by rememberSaveable { mutableStateOf(Repos.library.backdrop) }
    var unit by rememberSaveable { mutableStateOf(Repos.library.unit) }
    var showInfo by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val exportError = stringResource(R.string.export_error)
    val shareTitle = stringResource(R.string.share_title)
    val printError = stringResource(R.string.print_error)
    val claudeMissing = stringResource(R.string.claude_missing)

    fun exportPng(dpi: Int) {
        menuOpen = false
        scope.launch {
            try {
                val out = vm.exportPng(context, dpi)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, out)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("$exportError: ${e.message}")
            }
        }
    }

    fun shareOriginal(openWith: Boolean) {
        menuOpen = false
        val d = ready ?: return
        val u = vm.localFileUri(context) ?: return
        val mime = d.format.mimeType
        val intent = if (openWith) {
            Intent(Intent.ACTION_VIEW).apply { setDataAndType(u, mime) }
        } else {
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, u)
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(Intent.createChooser(intent, shareTitle)) }
    }

    fun exportPdf() {
        menuOpen = false
        scope.launch {
            try {
                val u = vm.uriFor(context, vm.exportPdfFile(context))
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, u)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, shareTitle))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("$exportError: ${e.message}")
            }
        }
    }

    fun print() {
        menuOpen = false
        val name = ready?.name ?: return
        scope.launch {
            try {
                val file = vm.exportPdfFile(context)
                val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                pm.print(name, PdfFilePrintAdapter(file, name), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("$printError: ${e.message}")
            }
        }
    }

    fun sendToClaude() {
        menuOpen = false
        scope.launch {
            try {
                val u = vm.exportForClaude(context)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, u)
                    putExtra(Intent.EXTRA_TEXT, vm.claudeContext(unit))
                    clipData = ClipData.newRawUri("", u)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val claudePkg = findClaudePackage(context)
                if (claudePkg != null) {
                    send.setPackage(claudePkg)
                    context.startActivity(send)
                } else {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Claude%20by%20Anthropic")))
                    }
                    snackbar.showSnackbar(claudeMissing)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("$exportError: ${e.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        ready?.name ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (ready != null) {
                        IconButton(onClick = { showInfo = true }) {
                            Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.info))
                        }
                        if (ready.document.hasRealScale) {
                            IconButton(onClick = { measureMode = !measureMode }) {
                                Icon(
                                    Icons.Outlined.Straighten,
                                    contentDescription = stringResource(R.string.measure),
                                    tint = if (measureMode) Accent else LocalContentColor.current,
                                )
                            }
                        }
                        IconButton(onClick = {
                            backdrop = backdrop.next()
                            Repos.library.backdrop = backdrop
                        }) {
                            Icon(Icons.Outlined.Contrast, contentDescription = stringResource(R.string.background))
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.send_to_claude)) },
                                    leadingIcon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = Accent) },
                                    onClick = { sendToClaude() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_pdf)) },
                                    leadingIcon = { Icon(Icons.Outlined.PictureAsPdf, contentDescription = null) },
                                    onClick = { exportPdf() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.print)) },
                                    leadingIcon = { Icon(Icons.Outlined.Print, contentDescription = null) },
                                    onClick = { print() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_png_300)) }, onClick = { exportPng(300) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.export_png_600)) }, onClick = { exportPng(600) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.share_original)) }, onClick = { shareOriginal(false) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.open_with)) }, onClick = { shareOriginal(true) })
                            }
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF1E1E1E)),
        ) {
            when (state) {
                is ViewerViewModel.UiState.Loading ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                is ViewerViewModel.UiState.Error ->
                    ErrorView(state.message, Modifier.align(Alignment.Center).padding(32.dp))
                is ViewerViewModel.UiState.Ready ->
                    DocumentViewport(vm, state.doc, backdrop, measureMode, unit) {
                        unit = it
                        Repos.library.unit = it
                    }
            }
            if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }

    if (showInfo && ready != null) {
        InfoDialog(ready, vm.page, unit) { showInfo = false }
    }
}

@Composable
private fun DocumentViewport(
    vm: ViewerViewModel,
    doc: LoadedDocument,
    backdrop: Backdrop,
    measureMode: Boolean,
    unit: LengthUnit,
    onUnitChange: (LengthUnit) -> Unit,
) {
    val page = vm.page
    val pageCount = doc.document.pageCount
    val pageSize = doc.document.pageSize(page)

    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var fitScale by remember { mutableFloatStateOf(1f) }
    var sharp by remember { mutableStateOf<SharpFrame?>(null) }
    val measurePts = remember { mutableStateListOf<Offset>() } // coordenadas del documento (pt)

    fun fit() {
        if (viewport.width == 0 || viewport.height == 0) return
        val s = min(viewport.width / pageSize.width, viewport.height / pageSize.height) * 0.92f
        fitScale = s
        scale = s
        offset = Offset((viewport.width - pageSize.width * s) / 2f, (viewport.height - pageSize.height * s) / 2f)
    }

    LaunchedEffect(page, viewport) {
        fit()
        measurePts.clear()
    }
    LaunchedEffect(measureMode) { if (!measureMode) measurePts.clear() }

    // Tras 160 ms sin gestos, re-renderiza la zona visible a resolución nativa.
    LaunchedEffect(page, scale, offset, viewport) {
        if (viewport.width == 0) return@LaunchedEffect
        delay(160)
        val bmp = vm.renderViewport(page, viewport.width, viewport.height, scale, offset.x, offset.y)
        if (bmp != null) sharp = SharpFrame(bmp, page, scale, offset)
    }

    val checker = remember { checkerShader() }
    val bmpPaint = remember { APaint(APaint.FILTER_BITMAP_FLAG) }
    val bgPaint = remember { APaint() }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(fitScale * 0.25f, fitScale * 80f)
                        val z = newScale / scale
                        offset = (offset - centroid) * z + centroid + pan
                        scale = newScale
                    }
                }
                .pointerInput(measureMode, page) {
                    detectTapGestures(
                        onDoubleTap = { p ->
                            if (scale > fitScale * 1.5f) {
                                fit()
                            } else {
                                val target = fitScale * 4f
                                val z = target / scale
                                offset = (offset - p) * z + p
                                scale = target
                            }
                        },
                        onTap = { p ->
                            if (measureMode) {
                                if (measurePts.size >= 2) measurePts.clear()
                                measurePts.add((p - offset) / scale)
                            }
                        },
                    )
                },
        ) {
            val pr = RectF(offset.x, offset.y, offset.x + pageSize.width * scale, offset.y + pageSize.height * scale)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                when (backdrop) {
                    Backdrop.WHITE -> { bgPaint.shader = null; bgPaint.color = android.graphics.Color.WHITE }
                    Backdrop.CHECKER -> { bgPaint.shader = checker }
                    Backdrop.DARK -> { bgPaint.shader = null; bgPaint.color = android.graphics.Color.rgb(40, 40, 40) }
                }
                nc.drawRect(pr, bgPaint)
                vm.base?.let { nc.drawBitmap(it, null, pr, bmpPaint) }
                sharp?.takeIf { it.page == page }?.let { f ->
                    val r = scale / f.scale
                    val m = Matrix().apply {
                        setScale(r, r)
                        postTranslate(offset.x - f.offset.x * r, offset.y - f.offset.y * r)
                    }
                    nc.drawBitmap(f.bitmap, m, bmpPaint)
                }
            }
            drawRect(
                color = Color.White.copy(alpha = 0.18f),
                topLeft = Offset(pr.left, pr.top),
                size = Size(pr.width(), pr.height()),
                style = Stroke(1f),
            )

            if (measurePts.isNotEmpty()) {
                val sp = measurePts.map { it * scale + offset }
                if (sp.size == 2) drawLine(Accent, sp[0], sp[1], strokeWidth = 2.dp.toPx())
                sp.forEach {
                    drawCircle(Accent, radius = 7.dp.toPx(), center = it)
                    drawCircle(Color.White, radius = 3.dp.toPx(), center = it)
                }
            }
        }

        if (vm.base == null) CircularProgressIndicator(Modifier.align(Alignment.Center))

        Column(
            Modifier.align(Alignment.BottomCenter).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (doc.document.isPreviewOnly) Pill(stringResource(R.string.preview_only), warning = true)

            if (measureMode) {
                val text = if (measurePts.size == 2) {
                    val a = measurePts[0]
                    val b = measurePts[1]
                    val dx = abs(b.x - a.x).toDouble()
                    val dy = abs(b.y - a.y).toDouble()
                    "${unit.format(hypot(dx, dy))}   Δx ${unit.format(dx)} · Δy ${unit.format(dy)}"
                } else {
                    stringResource(R.string.measure_hint)
                }
                Pill(text, onClick = { onUnitChange(unit.next()) })
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pageCount > 1) {
                    FilledTonalIconButton(onClick = { vm.goToPage(page - 1) }, enabled = page > 0) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.prev_page))
                    }
                    Pill(stringResource(R.string.page_of, page + 1, pageCount))
                    FilledTonalIconButton(onClick = { vm.goToPage(page + 1) }, enabled = page < pageCount - 1) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.next_page))
                    }
                }
                Pill(sizeLabel(doc, page, unit), onClick = { onUnitChange(unit.next()) })
            }
        }
    }
}

private fun sizeLabel(doc: LoadedDocument, page: Int, unit: LengthUnit): String {
    val s = doc.document.pageSize(page)
    return if (doc.document.hasRealScale) {
        unit.formatSize(s.width.toDouble(), s.height.toDouble())
    } else {
        "${(s.width / 0.75f).roundToInt()} × ${(s.height / 0.75f).roundToInt()} px"
    }
}

@Composable
private fun InfoDialog(doc: LoadedDocument, page: Int, unit: LengthUnit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        title = { Text(doc.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow(stringResource(R.string.info_format), doc.format.label)
                InfoRow(stringResource(R.string.info_size), FileUtils.formatBytes(doc.sizeBytes))
                InfoRow(stringResource(R.string.info_pages), doc.document.pageCount.toString())
                InfoRow(stringResource(R.string.info_artboard), sizeLabel(doc, page, unit))
                if (doc.document.hasRealScale && unit != LengthUnit.IN) {
                    InfoRow("", sizeLabel(doc, page, LengthUnit.IN))
                }
                doc.document.engineNote?.let { InfoRow(stringResource(R.string.info_engine), it) }
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        if (label.isNotEmpty()) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorView(message: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Accent)
        Text(stringResource(R.string.loading_error_title), fontWeight = FontWeight.Bold, color = Color.White)
        Text(message, color = Color(0xFFBDBDBD))
    }
}

private fun checkerShader(): BitmapShader {
    val s = 12
    val b = Bitmap.createBitmap(s * 2, s * 2, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    c.drawColor(android.graphics.Color.WHITE)
    val p = APaint().apply { color = android.graphics.Color.rgb(217, 217, 217) }
    c.drawRect(0f, 0f, s.toFloat(), s.toFloat(), p)
    c.drawRect(s.toFloat(), s.toFloat(), 2f * s, 2f * s, p)
    return BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}
