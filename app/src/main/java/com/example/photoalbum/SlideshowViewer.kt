package com.example.photoalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
fun rememberBatteryStatus(): Pair<Int, Boolean> {
    val context = LocalContext.current
    var batteryLevel by remember { mutableIntStateOf(100) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100) / scale
                }
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyIntent = context.registerReceiver(receiver, filter)
        if (stickyIntent != null) {
            val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100) / scale
            }
            val status = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    return batteryLevel to isCharging
}

@Composable
fun rememberCurrentDateTimeString(): String {
    val formatter = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }
    var dateTimeText by remember { mutableStateOf(formatter.format(Date())) }

    LaunchedEffect(Unit) {
        while (true) {
            dateTimeText = formatter.format(Date())
            delay(1.seconds)
        }
    }
    return dateTimeText
}

@Composable
fun SlideshowViewer(
    photoUris: List<Uri>,
    currentIndex: Int,
    isSlideshowRunning: Boolean,
    showOverlay: Boolean,
    onToggleOverlay: () -> Unit,
    onChangeIndex: (Int) -> Unit,
    onExitViewer: () -> Unit
) {
    val context = LocalContext.current
    val currentUri = photoUris.getOrNull(currentIndex) ?: return

    LaunchedEffect(currentIndex, photoUris.size) {
        if (photoUris.size > 1) {
            val nextUri = photoUris[(currentIndex + 1) % photoUris.size]
            val preloadRequest = ImageRequest.Builder(context)
                .data(nextUri)
                .size(1920, 1920)
                .scale(Scale.FIT)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .build()
            context.imageLoader.enqueue(preloadRequest)
        }
    }

    val (batteryLevel, isCharging) = rememberBatteryStatus()
    val currentDateTime = rememberCurrentDateTimeString()

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds()
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleOverlay,
                onLongClick = onExitViewer
            )
            .pointerInput(photoUris.size) {
                detectDragGestures(
                    onDragStart = {
                        dragOffsetX = 0f
                        dragOffsetY = 0f
                    },
                    onDragEnd = {
                        val threshold = 80f
                        val total = photoUris.size
                        if (total > 1) {
                            when {
                                dragOffsetX < -threshold || dragOffsetY < -threshold -> {
                                    onChangeIndex((currentIndex + 1) % total)
                                }
                                dragOffsetX > threshold || dragOffsetY > threshold -> {
                                    onChangeIndex((currentIndex - 1 + total) % total)
                                }
                            }
                        }
                    }
                ) { change, dragAmount ->
                    change.consume()
                    dragOffsetX += dragAmount.x
                    dragOffsetY += dragAmount.y
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = currentUri,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            label = "PhotoCrossfade"
        ) { targetUri ->
            var startKenBurns by remember(targetUri) { mutableStateOf(false) }

            LaunchedEffect(targetUri, isSlideshowRunning) {
                startKenBurns = isSlideshowRunning
            }

            val scale by animateFloatAsState(
                targetValue = if (startKenBurns && isSlideshowRunning) 1.08f else 1.0f,
                animationSpec = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
                label = "KenBurnsScale"
            )

            val panDirection = remember(targetUri) {
                if (targetUri.hashCode() % 2 == 0) 1f else -1f
            }
            val translationX by animateFloatAsState(
                targetValue = if (startKenBurns && isSlideshowRunning) 18f * panDirection else 0f,
                animationSpec = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
                label = "KenBurnsPanX"
            )

            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(targetUri)
                    .size(1920, 1920)
                    .scale(Scale.FIT)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false)
                    .build(),
                contentDescription = stringResource(R.string.cd_fullscreen_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.translationX = translationX
                    }
            )
        }

        AnimatedVisibility(
            visible = showOverlay,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Text(
                        text = currentDateTime,
                        color = Color.White.copy(alpha = 0.9f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Text(
                        text = if (isCharging) "⚡ $batteryLevel%" else "🔋 $batteryLevel%",
                        color = if (batteryLevel <= 20 && !isCharging) {
                            Color(0xFFFF6B6B)
                        } else {
                            Color.White.copy(alpha = 0.9f)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                ) {
                    val statusText = if (isSlideshowRunning) {
                        stringResource(
                            R.string.viewer_status_running_format,
                            currentIndex + 1,
                            photoUris.size
                        )
                    } else {
                        stringResource(
                            R.string.viewer_status_paused_format,
                            currentIndex + 1,
                            photoUris.size
                        )
                    }
                    Text(
                        text = statusText,
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
