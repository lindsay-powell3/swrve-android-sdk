package com.swrve.sdk.sample.embedded

import android.content.ClipData
import android.content.Intent
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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

private const val TAP_REWARD_TAG = "TapRewardBanner"
private const val TAP_REWARD_LOAD_DELAY_MS = 1000L

// ---------------------------------------------------------------------------
// Schema (parsed with org.json, mirrors FormBanner's self-contained approach)
// ---------------------------------------------------------------------------

private data class RewardText(val text: String, val color: String?)
private data class RewardCta(val text: String?, val url: String?, val color: String?, val backgroundColor: String?)
private data class RewardDismiss(val label: String, val color: String?)

private data class RewardPayload(
    val title: RewardText?,
    val body: RewardText?,
    val code: String?
)

private data class TapRewardSpec(
    val campaignId: String,
    val backgroundColor: String?,
    val textColor: String?,
    val accentColor: String?,
    val icon: String?,
    val revealIcon: String?,
    val prompt: RewardText?,
    val helperText: RewardText?,
    val reward: RewardPayload,
    val cta: RewardCta?,
    val dismiss: RewardDismiss,
    val eventName: String,
    val propertyKey: String?,
    val propertyValue: String
)

private fun JSONObject.rewardTextOrNull(key: String): RewardText? {
    val o = optJSONObject(key) ?: return null
    val t = o.optString("text", "")
    return if (t.isBlank()) null else RewardText(t, o.optString("color").ifBlank { null })
}

private fun parseTapReward(json: String): TapRewardSpec? = try {
    val o = JSONObject(json)
    if (o.optString("type") != "tap_reward") {
        null
    } else {
        val rewardObj = o.optJSONObject("reward")
        val ctaObj = o.optJSONObject("cta")
        val dismissObj = o.optJSONObject("dismiss")
        val onReveal = o.optJSONObject("on_reveal")
        val prop = onReveal?.optJSONObject("user_property")

        TapRewardSpec(
            campaignId = o.optString("campaign_id", "tap_reward"),
            backgroundColor = o.optString("background_color").ifBlank { null },
            textColor = o.optString("text_color").ifBlank { null },
            accentColor = o.optString("accent_color").ifBlank { null },
            icon = o.optString("icon").ifBlank { null },
            revealIcon = o.optString("reveal_icon").ifBlank { null },
            prompt = o.rewardTextOrNull("prompt"),
            helperText = o.rewardTextOrNull("helper_text"),
            reward = RewardPayload(
                title = rewardObj?.rewardTextOrNull("title"),
                body = rewardObj?.rewardTextOrNull("body"),
                code = rewardObj?.optString("code")?.ifBlank { null }
            ),
            cta = ctaObj?.let {
                RewardCta(
                    text = it.optString("text").ifBlank { null },
                    url = it.optString("url").ifBlank { null },
                    color = it.optString("color").ifBlank { null },
                    backgroundColor = it.optString("background_color").ifBlank { null }
                )
            },
            dismiss = RewardDismiss(
                label = dismissObj?.optString("label")?.ifBlank { null } ?: "No thanks",
                color = dismissObj?.optString("color")?.ifBlank { null }
            ),
            eventName = onReveal?.optString("event")?.ifBlank { null } ?: "tap_reward.revealed",
            propertyKey = prop?.optString("key")?.ifBlank { null },
            propertyValue = prop?.optString("value", "true") ?: "true"
        )
    }
} catch (e: Exception) {
    Log.w(TAP_REWARD_TAG, "Could not parse tap_reward JSON", e)
    null
}

/**
 * Finds the first Message Center embedded campaign whose payload has "type": "tap_reward".
 * Mirrors FormBanner's loadForm()/EmbeddedViewModel's offline-mode handling so this also
 * works against the bundled demo_campaigns.json when no live Swrve account is set up.
 */
private fun loadTapReward(context: android.content.Context): Pair<SwrveEmbeddedMessage, TapRewardSpec>? {
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
        val spec = raw?.let { parseTapReward(it) }
        if (spec != null) {
            Log.d(TAP_REWARD_TAG, "Showing tap_reward ${spec.campaignId} from message ${message.id}")
            return message to spec
        }
    }
    Log.d(TAP_REWARD_TAG, "No tap_reward campaign found")
    return null
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
fun TapRewardBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()

    var entry by remember { mutableStateOf<Pair<SwrveEmbeddedMessage, TapRewardSpec>?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        delay(TAP_REWARD_LOAD_DELAY_MS)
        entry = loadTapReward(context)
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
        val accentColor = hexToColor(spec.accentColor) ?: MaterialTheme.colorScheme.primary

        fun reveal() {
            if (revealed) return
            // 1. campaign click metric
            SwrveSDK.embeddedMessageButtonWasPressed(message, "tap")
            // 2. the reveal, as a custom event (which reward code the user received)
            val payload = mutableMapOf("campaign_id" to spec.campaignId)
            spec.reward.code?.let { payload["reward_code"] = it }
            SwrveSDK.event(spec.eventName, payload)
            // 3. state, as a user property (e.g. "has_unclaimed_reward")
            spec.propertyKey?.let { SwrveSDK.userUpdate(mapOf(it to spec.propertyValue)) }
            // 4. send now rather than waiting for the next batch
            SwrveSDK.sendQueuedEvents()
            revealed = true
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
                    .padding(horizontal = 18.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AnimatedContent(
                    targetState = revealed,
                    transitionSpec = {
                        (scaleIn(initialScale = 0.85f) + fadeIn()) togetherWith (scaleOut(targetScale = 1.1f) + fadeOut())
                    },
                    label = "tapRewardReveal"
                ) { isRevealed ->
                    if (!isRevealed) {
                        ClosedState(
                            spec = spec,
                            textColor = textColor,
                            accentColor = accentColor,
                            onTap = ::reveal
                        )
                    } else {
                        RevealedState(
                            spec = spec,
                            textColor = textColor,
                            accentColor = accentColor,
                            copied = copied,
                            onCopyCode = { code ->
                                coroutineScope.launch {
                                    copyToClipboard(clipboard, code)
                                    copied = true
                                    delay(1500L)
                                    copied = false
                                }
                            },
                            onCtaClick = {
                                val cta = spec.cta
                                if (cta != null && !cta.url.isNullOrBlank()) {
                                    SwrveSDK.embeddedMessageButtonWasPressed(message, cta.text ?: "cta")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, cta.url.toUri()))
                                }
                                isVisible = false
                            }
                        )
                    }
                }

                Text(
                    text = if (revealed) "Close" else spec.dismiss.label,
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
private fun ClosedState(
    spec: TapRewardSpec,
    textColor: Color,
    accentColor: Color,
    onTap: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "tapPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "tapPulseScale"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .scale(pulseScale)
                .size(88.dp)
                .clip(CircleShape)
                .background(accentColor)
                .clickable { onTap() },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = spec.icon ?: "🎁", // gift emoji fallback
                style = MaterialTheme.typography.displaySmall
            )
        }

        spec.prompt?.let {
            Text(
                text = it.text,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = hexToColor(it.color) ?: textColor,
                textAlign = TextAlign.Center
            )
        }

        spec.helperText?.let {
            Text(
                text = it.text,
                style = MaterialTheme.typography.bodySmall,
                color = hexToColor(it.color) ?: textColor.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RevealedState(
    spec: TapRewardSpec,
    textColor: Color,
    accentColor: Color,
    copied: Boolean,
    onCopyCode: (String) -> Unit,
    onCtaClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = spec.revealIcon ?: spec.icon ?: "🎉", // party popper fallback
            style = MaterialTheme.typography.displaySmall
        )

        spec.reward.title?.let {
            Text(
                text = it.text,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = hexToColor(it.color) ?: accentColor,
                textAlign = TextAlign.Center
            )
        }

        spec.reward.body?.let {
            Text(
                text = it.text,
                style = MaterialTheme.typography.bodyMedium,
                color = hexToColor(it.color) ?: textColor,
                textAlign = TextAlign.Center
            )
        }

        spec.reward.code?.let { code ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(percent = 50))
                    .border(1.dp, accentColor, RoundedCornerShape(percent = 50))
                    .clickable { onCopyCode(code) }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (copied) "Copied!" else code,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = accentColor
                )
            }
        }

        spec.cta?.let { cta ->
            if (!cta.text.isNullOrBlank()) {
                Button(
                    onClick = onCtaClick,
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = hexToColor(cta.backgroundColor) ?: accentColor,
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

private suspend fun copyToClipboard(clipboard: Clipboard, text: String) {
    try {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Reward code", text)))
    } catch (e: Exception) {
        Log.w(TAP_REWARD_TAG, "Could not copy reward code to clipboard", e)
    }
}
