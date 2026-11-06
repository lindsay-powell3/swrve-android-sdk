package com.swrve.sdk.sample.embedded

import android.content.Intent
import android.graphics.Paint
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.swrve.sdk.SwrveSDK
import com.swrve.sdk.messaging.SwrveEmbeddedCampaign
import com.swrve.sdk.messaging.SwrveEmbeddedMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.random.Random

private const val SPIN_WHEEL_TAG = "SpinWheelBanner"
private const val SPIN_WHEEL_LOAD_DELAY_MS = 1000L
private const val SPIN_DURATION_MS = 3800
private const val SPIN_EXTRA_TURNS = 6

// ---------------------------------------------------------------------------
// Schema (parsed with org.json, mirrors FormBanner's/TapRewardBanner's
// self-contained approach)
// ---------------------------------------------------------------------------

private data class WheelText(val text: String, val color: String?)
private data class WheelCta(val text: String?, val url: String?, val color: String?, val backgroundColor: String?)
private data class WheelDismiss(val label: String, val color: String?)

private data class WheelSegment(
    val label: String,
    val color: String?,
    val textColor: String?,
    val code: String?
)

private data class SpinWheelSpec(
    val campaignId: String,
    val backgroundColor: String?,
    val textColor: String?,
    val title: WheelText?,
    val subtitle: WheelText?,
    val segments: List<WheelSegment>,
    val winningIndex: Int?,
    val spinButtonLabel: String,
    val cta: WheelCta?,
    val dismiss: WheelDismiss,
    val eventName: String,
    val propertyKey: String?,
    val propertyValue: String
)

private fun JSONObject.wheelTextOrNull(key: String): WheelText? {
    val o = optJSONObject(key) ?: return null
    val t = o.optString("text", "")
    return if (t.isBlank()) null else WheelText(t, o.optString("color").ifBlank { null })
}

private fun parseSpinWheel(json: String): SpinWheelSpec? = try {
    val o = JSONObject(json)
    if (o.optString("type") != "spin_wheel") {
        null
    } else {
        val segmentsJson = o.optJSONArray("segments")
        val segments = (0 until (segmentsJson?.length() ?: 0)).mapNotNull { i ->
            val s = segmentsJson!!.optJSONObject(i) ?: return@mapNotNull null
            val label = s.optString("label").ifBlank { null } ?: return@mapNotNull null
            WheelSegment(
                label = label,
                color = s.optString("color").ifBlank { null },
                textColor = s.optString("text_color").ifBlank { null },
                code = s.optString("code").ifBlank { null }
            )
        }
        if (segments.size < 2) {
            null
        } else {
            val ctaObj = o.optJSONObject("cta")
            val dismissObj = o.optJSONObject("dismiss")
            val onResult = o.optJSONObject("on_result")
            val prop = onResult?.optJSONObject("user_property")

            SpinWheelSpec(
                campaignId = o.optString("campaign_id", "spin_wheel"),
                backgroundColor = o.optString("background_color").ifBlank { null },
                textColor = o.optString("text_color").ifBlank { null },
                title = o.wheelTextOrNull("title"),
                subtitle = o.wheelTextOrNull("subtitle"),
                segments = segments,
                winningIndex = if (o.has("winning_index")) o.optInt("winning_index") else null,
                spinButtonLabel = o.optString("spin_button_label").ifBlank { null } ?: "SPIN",
                cta = ctaObj?.let {
                    WheelCta(
                        text = it.optString("text").ifBlank { null },
                        url = it.optString("url").ifBlank { null },
                        color = it.optString("color").ifBlank { null },
                        backgroundColor = it.optString("background_color").ifBlank { null }
                    )
                },
                dismiss = WheelDismiss(
                    label = dismissObj?.optString("label")?.ifBlank { null } ?: "No thanks",
                    color = dismissObj?.optString("color")?.ifBlank { null }
                ),
                eventName = onResult?.optString("event")?.ifBlank { null } ?: "spin_wheel.spun",
                propertyKey = prop?.optString("key")?.ifBlank { null },
                propertyValue = prop?.optString("value", "true") ?: "true"
            )
        }
    }
} catch (e: Exception) {
    Log.w(SPIN_WHEEL_TAG, "Could not parse spin_wheel JSON", e)
    null
}

/**
 * Finds the first Message Center embedded campaign whose payload has "type": "spin_wheel".
 * Mirrors FormBanner/TapRewardBanner's offline-mode handling so this also works against the
 * bundled demo_campaigns.json when no live Swrve account is set up.
 */
private fun loadSpinWheel(context: android.content.Context): Pair<SwrveEmbeddedMessage, SpinWheelSpec>? {
    val campaigns = if (SwrvePrefs.isOffline(SwrvePrefs.prefs(context))) {
        DemoCampaignsManager().getDemoCampaigns(context)
    } else {
        SwrveSDK.getEmbeddedMessageCenterCampaigns()
    }
    if (campaigns == null) return null
    for (item in campaigns) {
        val message: SwrveEmbeddedMessage = when (val any = item as Any) {
            is SwrveEmbeddedMessage -> any
            is SwrveEmbeddedCampaign -> any.message ?: continue
            else -> continue
        }
        val raw = message.data
        val spec = raw?.let { parseSpinWheel(it) }
        if (spec != null) {
            Log.d(SPIN_WHEEL_TAG, "Showing spin_wheel ${spec.campaignId} from message ${message.id}")
            return message to spec
        }
    }
    Log.d(SPIN_WHEEL_TAG, "No spin_wheel campaign found")
    return null
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
fun SpinWheelBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    var entry by remember { mutableStateOf<Pair<SwrveEmbeddedMessage, SpinWheelSpec>?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var spinning by remember { mutableStateOf(false) }
    var landedIndex by remember { mutableStateOf<Int?>(null) }
    var copied by remember { mutableStateOf(false) }
    val rotation = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        delay(SPIN_WHEEL_LOAD_DELAY_MS)
        entry = loadSpinWheel(context)
        isVisible = entry != null
    }

    // Impression, once per message
    LaunchedEffect(entry?.first?.id) {
        entry?.let { SwrveSDK.embeddedMessageWasShownToUser(it.first) }
    }

    AnimatedVisibility(
        visible = isVisible && entry != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val (message, spec) = entry!!

        val textColor = hexToColor(spec.textColor)
            ?: if (spec.backgroundColor != null) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.onSurface

        fun spin() {
            if (spinning || landedIndex != null) return
            spinning = true
            SwrveSDK.embeddedMessageButtonWasPressed(message, "spin")

            val winningIndex = spec.winningIndex?.takeIf { it in spec.segments.indices }
                ?: Random.nextInt(spec.segments.size)
            val segmentAngle = 360f / spec.segments.size
            val segmentMidAngle = -90f + winningIndex * segmentAngle + segmentAngle / 2f
            var targetOffset = (-90f - segmentMidAngle) % 360f
            if (targetOffset < 0f) targetOffset += 360f
            val target = 360f * SPIN_EXTRA_TURNS + targetOffset

            coroutineScope.launch {
                rotation.animateTo(
                    targetValue = target,
                    animationSpec = tween(durationMillis = SPIN_DURATION_MS, easing = FastOutSlowInEasing)
                )
                val segment = spec.segments[winningIndex]
                val payload = mutableMapOf(
                    "campaign_id" to spec.campaignId,
                    "segment" to segment.label,
                    "won" to (segment.code != null).toString()
                )
                segment.code?.let { payload["reward_code"] = it }
                SwrveSDK.event(spec.eventName, payload)
                spec.propertyKey?.let { SwrveSDK.userUpdate(mapOf(it to spec.propertyValue)) }
                SwrveSDK.sendQueuedEvents()
                spinning = false
                landedIndex = winningIndex
            }
        }

        fun dismiss() {
            SwrveSDK.embeddedMessageButtonWasPressed(message, "dismiss")
            isVisible = false
        }

        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(hexToColor(spec.backgroundColor) ?: MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                spec.title?.let {
                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = hexToColor(it.color) ?: textColor,
                        textAlign = TextAlign.Center
                    )
                }
                spec.subtitle?.let {
                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = hexToColor(it.color) ?: textColor.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center
                    )
                }

                Box(contentAlignment = Alignment.Center) {
                    SpinWheel(segments = spec.segments, rotationDegrees = rotation.value)

                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface)
                            .border(2.dp, textColor.copy(alpha = 0.2f), CircleShape)
                            .clickable(enabled = !spinning && landedIndex == null) { spin() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = spec.spinButtonLabel,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = textColor,
                            textAlign = TextAlign.Center
                        )
                    }

                    WheelPointer(
                        color = textColor,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .size(width = 20.dp, height = 16.dp)
                    )
                }

                AnimatedContent(
                    targetState = landedIndex,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "spinWheelResult"
                ) { index ->
                    if (index == null) {
                        Text(
                            text = if (spinning) "Spinning…" else "Tap the wheel to spin",
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        val segment = spec.segments[index]
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = segment.label,
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = hexToColor(segment.color) ?: textColor,
                                textAlign = TextAlign.Center
                            )

                            segment.code?.let { code ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(percent = 50))
                                        .border(1.dp, textColor.copy(alpha = 0.3f), RoundedCornerShape(percent = 50))
                                        .clickable {
                                            coroutineScope.launch {
                                                copyToClipboard(clipboard, code)
                                                copied = true
                                                delay(1500L)
                                                copied = false
                                            }
                                        }
                                        .padding(horizontal = 18.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = if (copied) "Copied!" else code,
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = textColor
                                    )
                                }

                                spec.cta?.let { cta ->
                                    if (!cta.text.isNullOrBlank()) {
                                        Button(
                                            onClick = {
                                                val url = cta.url
                                                if (!url.isNullOrBlank()) {
                                                    SwrveSDK.embeddedMessageButtonWasPressed(message, cta.text)
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                                }
                                                isVisible = false
                                            },
                                            shape = RoundedCornerShape(percent = 50),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = hexToColor(cta.backgroundColor) ?: textColor,
                                                contentColor = hexToColor(cta.color) ?: Color.White
                                            ),
                                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 6.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(48.dp)
                                        ) {
                                            Text(
                                                text = cta.text,
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = if (landedIndex != null) "Close" else spec.dismiss.label,
                    color = hexToColor(spec.dismiss.color) ?: textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { dismiss() }
                )
            }
        }
    }
}

@Composable
private fun SpinWheel(
    segments: List<WheelSegment>,
    rotationDegrees: Float,
    modifier: Modifier = Modifier
) {
    val segmentAngle = 360f / segments.size
    Canvas(
        modifier = modifier
            .size(230.dp)
            .graphicsLayer { rotationZ = rotationDegrees }
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)

        segments.forEachIndexed { i, segment ->
            val startAngle = -90f + i * segmentAngle
            drawArc(
                color = hexToColor(segment.color) ?: Color.DarkGray,
                startAngle = startAngle,
                sweepAngle = segmentAngle,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize
            )
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = startAngle,
                sweepAngle = segmentAngle,
                useCenter = true,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 2.dp.toPx())
            )

            // The label is drawn starting from "straight up" (angle -90 in drawArc's
            // convention), so it needs the same +90 correction as the segment's own
            // mid angle before rotating it into place, or it lands in the wrong wedge.
            val midAngle = startAngle + segmentAngle / 2f
            rotate(degrees = midAngle + 90f, pivot = center) {
                drawContext.canvas.nativeCanvas.drawText(
                    segment.label,
                    center.x,
                    center.y - radius * 0.62f,
                    Paint().apply {
                        color = (hexToColor(segment.textColor) ?: Color.White).toArgb()
                        textAlign = Paint.Align.CENTER
                        textSize = radius * 0.13f
                        isFakeBoldText = true
                        isAntiAlias = true
                    }
                )
            }
        }

        drawCircle(color = Color.White.copy(alpha = 0.4f), radius = radius, style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun WheelPointer(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(0f, 0f)
            lineTo(size.width, 0f)
            lineTo(size.width / 2f, size.height)
            close()
        }
        drawPath(path = path, color = color)
    }
}

private suspend fun copyToClipboard(clipboard: Clipboard, text: String) {
    try {
        clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText("Reward code", text)))
    } catch (e: Exception) {
        Log.w(SPIN_WHEEL_TAG, "Could not copy reward code to clipboard", e)
    }
}
