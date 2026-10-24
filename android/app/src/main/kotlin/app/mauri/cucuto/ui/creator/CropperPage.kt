package app.mauri.cucuto.ui.creator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.cucuto.R
import app.mauri.cucuto.core.creator.SpriteCrop

/**
 * Framing a picture: pinch to zoom and drag to move it inside a square, with the app's cookie shape drawn on top so the
 * person sees how the character will look. A zoom slider does the same for one finger and for TalkBack.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CropperPage(
    cropper: CropperUi,
    saving: Boolean,
    onBack: () -> Unit,
    onCrop: (SpriteCrop) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = remember(cropper.picture) { cropper.picture.asImageBitmap() }
    val currentCrop by rememberUpdatedState(cropper.crop)
    val currentOnCrop by rememberUpdatedState(onCrop)
    val frameLabel = stringResource(R.string.creator_crop_frame)
    val zoomLabel = stringResource(R.string.creator_crop_zoom)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.creator_crop_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.creator_crop_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BoxWithConstraints(
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clipToBounds()
                    .semantics { contentDescription = frameLabel }
                    .pointerInput(cropper.aspect) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val side = size.width.toFloat()
                            currentOnCrop(currentCrop.transformed(zoom, pan.x / side, pan.y / side, cropper.aspect))
                        }
                    },
            ) {
                val crop = cropper.crop
                // "Cover" fit, then the crop's zoom and pan (in viewport fractions).
                val aspect = cropper.aspect
                val fitWidth = if (aspect >= 1f) aspect else 1f
                val fitHeight = if (aspect >= 1f) 1f else 1f / aspect
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(maxWidth * fitWidth, maxWidth * fitHeight)
                            .graphicsLayer {
                                scaleX = crop.zoom
                                scaleY = crop.zoom
                                translationX = crop.panX * maxWidth.toPx()
                                translationY = crop.panY * maxWidth.toPx()
                            },
                )
                CookieOverlay(Modifier.fillMaxSize())
            }
            Slider(
                value = cropper.crop.zoom,
                onValueChange = { onCrop(cropper.crop.copy(zoom = it).clamped(cropper.aspect)) },
                valueRange = SpriteCrop.MIN_ZOOM..SpriteCrop.MAX_ZOOM,
                modifier = Modifier.widthIn(max = 480.dp).semantics { contentDescription = zoomLabel },
            )
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (saving) {
                    LoadingIndicator(Modifier.size(48.dp))
                } else {
                    Button(onClick = onDone, shapes = ButtonDefaults.shapes()) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                        Text(
                            stringResource(R.string.creator_crop_done),
                            Modifier.padding(start = ButtonDefaults.IconSpacing),
                        )
                    }
                }
            }
        }
    }
}

/** Dims what falls outside the app's cookie shape (the one characters are shown in) and outlines it. */
@Composable
private fun CookieOverlay(modifier: Modifier = Modifier) {
    val shape = MaterialShapes.Cookie9Sided.toShape()
    val outline = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val cookie =
            when (val o = shape.createOutline(size, layoutDirection, this)) {
                is Outline.Generic -> o.path
                is Outline.Rounded -> Path().apply { addRoundRect(o.roundRect) }
                is Outline.Rectangle -> Path().apply { addRect(o.rect) }
            }
        val dim =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addPath(cookie)
            }
        drawPath(dim, Color.Black.copy(alpha = DIM_ALPHA))
        drawPath(cookie, outline, style = Stroke(width = 2.dp.toPx()))
    }
}

private const val DIM_ALPHA = 0.45f
