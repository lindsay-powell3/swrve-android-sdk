package com.swrve.sdk.sample.embedded

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.swrve.sdk.SwrveSDK
import com.swrve.sdk.messaging.SwrveEmbeddedCampaign
import com.swrve.sdk.messaging.SwrveEmbeddedMessage
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private const val FORM_TAG = "FormBanner"

// ---------------------------------------------------------------------------
// Form schema (parsed with org.json so the shared EmbeddedData model is untouched)
// ---------------------------------------------------------------------------

private data class FormText(val text: String, val color: String?)
private data class FormOption(val value: String, val label: String, val disabled: Boolean = false)

private data class FormField(
    val id: String,
    val type: String,          // text | email | number | single_choice | multi_choice | checkbox | date
    val label: String,
    val required: Boolean,
    val maxLength: Int?,
    val options: List<FormOption>,
    val helper: String?,
    val minAge: Int?
)

// A field (or a whole section) can be conditioned on another field's current answer,
// e.g. only show "Guest details" once "How many are coming?" is answered "two".
private data class ShowIf(val fieldId: String, val equals: String)

private data class FormSection(
    val label: String?,
    val showIf: ShowIf?,
    val fields: List<FormField>
)

private data class FormButton(val label: String, val color: String?, val backgroundColor: String?)

private data class FormSpec(
    val formId: String,
    val backgroundColor: String?,
    val textColor: String?,
    val accentColor: String?,
    val image: String?,
    val title: FormText?,
    val intro: FormText?,
    val sections: List<FormSection>,
    val submit: FormButton,
    val dismiss: FormButton,
    val eventName: String,
    val propertyKey: String?,
    val propertyValue: String,
    val successMessage: String
)

private fun JSONObject.textOrNull(key: String): FormText? {
    val o = optJSONObject(key) ?: return null
    val t = o.optString("text", "")
    return if (t.isBlank()) null else FormText(t, o.optString("color").ifBlank { null })
}

private fun parseFields(fieldsJson: JSONArray?): List<FormField> =
    (0 until (fieldsJson?.length() ?: 0)).mapNotNull { i ->
        val f = fieldsJson!!.optJSONObject(i) ?: return@mapNotNull null
        val opts = f.optJSONArray("options")
        FormField(
            id = f.optString("id"),
            type = f.optString("type", "text"),
            label = f.optString("label"),
            required = f.optBoolean("required", false),
            maxLength = if (f.has("max_length")) f.optInt("max_length") else null,
            options = (0 until (opts?.length() ?: 0)).mapNotNull { j ->
                val op = opts!!.optJSONObject(j) ?: return@mapNotNull null
                FormOption(
                    op.optString("value"),
                    op.optString("label", op.optString("value")),
                    op.optBoolean("disabled", false)
                )
            },
            helper = f.optString("helper").ifBlank { null },
            minAge = if (f.has("min_age")) f.optInt("min_age") else null
        )
    }.filter { it.id.isNotBlank() }

private fun parseShowIf(sectionJson: JSONObject): ShowIf? {
    val si = sectionJson.optJSONObject("show_if") ?: return null
    val field = si.optString("field")
    if (field.isBlank()) return null
    return ShowIf(field, si.optString("equals"))
}

private fun parseForm(json: String): FormSpec? = try {
    val o = JSONObject(json)
    if (o.optString("type") != "form") {
        null
    } else {
        // "sections" is the richer, optional structure (numbered headers, conditional
        // visibility); a payload with a flat "fields" array and no "sections" still works
        // as a single, always-visible, unlabeled section.
        val sectionsJson = o.optJSONArray("sections")
        val sections: List<FormSection> = if (sectionsJson != null) {
            (0 until sectionsJson.length()).mapNotNull { i ->
                val s = sectionsJson.optJSONObject(i) ?: return@mapNotNull null
                FormSection(
                    label = s.optString("label").ifBlank { null },
                    showIf = parseShowIf(s),
                    fields = parseFields(s.optJSONArray("fields"))
                )
            }
        } else {
            listOf(FormSection(label = null, showIf = null, fields = parseFields(o.optJSONArray("fields"))))
        }

        val buttons = o.optJSONObject("buttons")
        fun button(name: String, fallback: String): FormButton {
            val b = buttons?.optJSONObject(name)
            return FormButton(
                label = b?.optString("label")?.ifBlank { null } ?: fallback,
                color = b?.optString("color")?.ifBlank { null },
                backgroundColor = b?.optString("background_color")?.ifBlank { null }
            )
        }

        val onSubmit = o.optJSONObject("on_submit")
        val prop = onSubmit?.optJSONObject("user_property")

        FormSpec(
            formId = o.optString("form_id", "form"),
            backgroundColor = o.optString("background_color").ifBlank { null },
            textColor = o.optString("text_color").ifBlank { null },
            accentColor = o.optString("accent_color").ifBlank { null },
            image = o.optString("image").ifBlank { null },
            title = o.textOrNull("title"),
            intro = o.textOrNull("intro"),
            sections = sections,
            submit = button("submit", "Submit"),
            dismiss = button("dismiss", "Not now"),
            eventName = onSubmit?.optString("event")?.ifBlank { null } ?: "form.submitted",
            propertyKey = prop?.optString("key")?.ifBlank { null },
            propertyValue = prop?.optString("value", "true") ?: "true",
            successMessage = onSubmit?.optString("success_message")?.ifBlank { null } ?: "Thanks!"
        )
    }
} catch (e: Exception) {
    Log.w(FORM_TAG, "Could not parse form JSON", e)
    null
}

/**
 * Finds the first Message Center embedded campaign whose payload has "type": "form".
 * Mirrors EmbeddedViewModel.loadFloatingBanner()'s offline-mode handling so this also
 * works against the bundled demo_campaigns.json when no live Swrve account is set up.
 */
private fun loadForm(context: android.content.Context): Pair<SwrveEmbeddedMessage, FormSpec>? {
    val campaigns = if (SwrvePrefs.isOffline(SwrvePrefs.prefs(context))) {
        DemoCampaignsManager().getDemoCampaigns(context)
    } else {
        SwrveSDK.getEmbeddedMessageCenterCampaigns()
    }
    Log.d(FORM_TAG, "Message Center returned ${campaigns?.size ?: "null"} item(s)")
    if (campaigns == null) return null
    for (item in campaigns) {
        // Depending on SDK version the list holds messages or campaigns; handle both.
        val message: SwrveEmbeddedMessage = when (val any = item as Any) {
            is SwrveEmbeddedMessage -> any
            is SwrveEmbeddedCampaign -> any.message ?: run {
                Log.d(FORM_TAG, "Campaign ${any.id} has no message"); null
            } ?: continue
            else -> {
                Log.d(FORM_TAG, "Skipping unexpected item type: ${any::class.java.name}")
                continue
            }
        }
        val raw = message.data
        Log.d(FORM_TAG, "Message ${message.id} data: ${raw?.take(200)}")
        val spec = raw?.let { parseForm(it) }
        if (spec == null) {
            Log.d(FORM_TAG, "Message ${message.id} is not a valid \"type\": \"form\" payload")
            continue
        }
        Log.d(FORM_TAG, "Showing form ${spec.formId} from message ${message.id}")
        return message to spec
    }
    Log.d(FORM_TAG, "No form campaign found")
    return null
}

private fun ageFromDate(day: Int, month: Int, year: Int): Int {
    val cal = Calendar.getInstance()
    val curYear = cal.get(Calendar.YEAR)
    val curMonth = cal.get(Calendar.MONTH) + 1
    val curDay = cal.get(Calendar.DAY_OF_MONTH)
    var age = curYear - year
    if (curMonth < month || (curMonth == month && curDay < day)) age--
    return age
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------

@Composable
fun FormBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var entry by remember { mutableStateOf<Pair<SwrveEmbeddedMessage, FormSpec>?>(null) }
    var isVisible by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var showErrors by remember { mutableStateOf(false) }
    val answers = remember { mutableStateMapOf<String, String>() }
    val multi = remember { mutableStateMapOf<String, Set<String>>() }

    LaunchedEffect(Unit) {
        delay(1000L)
        entry = loadForm(context)
        isVisible = entry != null
    }

    // Impression, once per message
    LaunchedEffect(entry?.first?.id) {
        entry?.let { SwrveSDK.embeddedMessageWasShownToUser(it.first) }
    }

    LaunchedEffect(submitted) {
        if (submitted) {
            delay(2000L)
            isVisible = false
        }
    }

    AnimatedVisibility(
        visible = isVisible && entry != null,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
    ) {
        val (message, spec) = entry!!
        val allFields = remember(spec) { spec.sections.flatMap { it.fields } }

        fun dateValue(f: FormField): String {
            val d = answers["${f.id}_day"].orEmpty()
            val m = answers["${f.id}_month"].orEmpty()
            val y = answers["${f.id}_year"].orEmpty()
            return if (d.isBlank() || m.isBlank() || y.isBlank()) "" else "$d/$m/$y"
        }

        fun valueOf(f: FormField): String = when (f.type) {
            "multi_choice" -> (multi[f.id] ?: emptySet()).joinToString("|")
            "date" -> dateValue(f)
            else -> answers[f.id].orEmpty()
        }

        fun isInvalid(f: FormField): Boolean {
            val v = valueOf(f)
            return when {
                f.required && f.type == "checkbox" -> v != "true"
                f.required && v.isBlank() -> true
                f.type == "email" && v.isNotBlank() && !v.contains("@") -> true
                f.type == "date" && f.minAge != null && v.isNotBlank() -> {
                    val parts = v.split("/")
                    val age = ageFromDate(
                        parts.getOrNull(0)?.toIntOrNull() ?: 0,
                        parts.getOrNull(1)?.toIntOrNull() ?: 0,
                        parts.getOrNull(2)?.toIntOrNull() ?: 0
                    )
                    age < f.minAge
                }
                else -> false
            }
        }

        fun isSectionVisible(section: FormSection): Boolean {
            val condition = section.showIf ?: return true
            val target = allFields.firstOrNull { it.id == condition.fieldId } ?: return true
            return valueOf(target) == condition.equals
        }

        fun visibleFields(): List<FormField> =
            spec.sections.filter { isSectionVisible(it) }.flatMap { it.fields }

        fun submit() {
            val fieldsToValidate = visibleFields()
            if (fieldsToValidate.any { isInvalid(it) }) {
                showErrors = true
                return
            }
            // 1. campaign click metric
            SwrveSDK.embeddedMessageButtonWasPressed(message, "submit")
            // 2. the answers, as a custom event (string values; multi-select as a|b|c,
            //    date as d/m/yyyy); fields hidden by an unmet show_if are left out entirely.
            val payload = mutableMapOf("form_id" to spec.formId)
            fieldsToValidate.forEach { f ->
                val v = valueOf(f)
                if (v.isNotBlank()) payload[f.id] = v
                else if (f.type == "checkbox") payload[f.id] = "false"
            }
            SwrveSDK.event(spec.eventName, payload)
            // 3. state, as a user property
            spec.propertyKey?.let { SwrveSDK.userUpdate(mapOf(it to spec.propertyValue)) }
            // 4. send now rather than waiting for the next batch
            SwrveSDK.sendQueuedEvents()
            submitted = true
        }

        // Text defaults to dark when the form sets its own (light) background, so it
        // stays readable when the phone is in dark mode. Override with "text_color".
        val textColor = hexToColor(spec.textColor)
            ?: if (spec.backgroundColor != null) Color(0xFF1A1A1A) else MaterialTheme.colorScheme.onSurface
        val accentColor = hexToColor(spec.accentColor)
            ?: hexToColor(spec.submit.backgroundColor) ?: MaterialTheme.colorScheme.primary

        // Keyboard handling: the form sits above the app's bottom navigation bar, which
        // is already part of the keyboard's height. Only lift the form by the part of the
        // keyboard that actually overlaps it, so there's no gap above the keyboard.
        val density = LocalDensity.current
        val rootView = LocalView.current.rootView
        val keyboardPx = WindowInsets.ime.getBottom(density)
        var spaceBelowPx by remember { mutableIntStateOf(0) }
        val keyboardPadding = with(density) { (keyboardPx - spaceBelowPx).coerceAtLeast(0).toDp() }

        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    // distance from the bottom of this overlay to the bottom of the screen
                    spaceBelowPx = (rootView.height - coords.boundsInWindow().bottom).toInt().coerceAtLeast(0)
                },
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .padding(bottom = keyboardPadding)
                    .fillMaxWidth()
                    .heightIn(max = 620.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(hexToColor(spec.backgroundColor) ?: MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                spec.image?.let { imageUrl ->
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(imageUrl).crossfade(true).build(),
                        contentDescription = spec.title?.text,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }

                spec.title?.let {
                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = hexToColor(it.color) ?: textColor
                    )
                }

                if (submitted) {
                    Text(
                        text = spec.successMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = hexToColor(spec.intro?.color) ?: textColor
                    )
                    return@Column
                }

                spec.intro?.let {
                    Text(
                        text = it.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = hexToColor(it.color) ?: textColor
                    )
                }

                spec.sections.forEach sectionLoop@{ section ->
                    if (!isSectionVisible(section)) return@sectionLoop
                    section.label?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = textColor,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    section.fields.forEach { f ->
                        FormFieldView(
                            field = f,
                            value = answers[f.id].orEmpty(),
                            selected = multi[f.id] ?: emptySet(),
                            error = showErrors && isInvalid(f),
                            textColor = textColor,
                            accentColor = accentColor,
                            dateDay = answers["${f.id}_day"].orEmpty(),
                            dateMonth = answers["${f.id}_month"].orEmpty(),
                            dateYear = answers["${f.id}_year"].orEmpty(),
                            onValue = { answers[f.id] = it },
                            onToggle = { opt ->
                                val cur = multi[f.id] ?: emptySet()
                                multi[f.id] = if (opt in cur) cur - opt else cur + opt
                            },
                            onDatePart = { part, v -> answers["${f.id}_$part"] = v }
                        )
                    }
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val submitContentColor = hexToColor(spec.submit.color) ?: Color.White
                    Button(
                        onClick = { submit() },
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = hexToColor(spec.submit.backgroundColor) ?: accentColor,
                            contentColor = submitContentColor
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(submitContentColor.copy(alpha = 0.22f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = submitContentColor,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = spec.submit.label,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(32.dp)) // balances the leading icon so the label stays centered
                    }

                    Text(
                        text = spec.dismiss.label,
                        color = hexToColor(spec.dismiss.color) ?: textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                SwrveSDK.embeddedMessageButtonWasPressed(message, "dismiss")
                                isVisible = false
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun FormFieldView(
    field: FormField,
    value: String,
    selected: Set<String>,
    error: Boolean,
    textColor: Color,
    accentColor: Color,
    dateDay: String,
    dateMonth: String,
    dateYear: String,
    onValue: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDatePart: (part: String, value: String) -> Unit
) {
    val label = if (field.required) "${field.label} *" else field.label
    val errorColor = MaterialTheme.colorScheme.error
    val labelColor = if (error) errorColor else textColor.copy(alpha = 0.8f)
    val borderColor = textColor.copy(alpha = 0.35f)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (field.type) {
            "single_choice" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    field.options.forEach { opt ->
                        ChoicePill(
                            label = opt.label,
                            selected = value == opt.value,
                            accentColor = accentColor,
                            textColor = textColor,
                            trailingArrow = true,
                            disabled = opt.disabled,
                            onClick = { onValue(opt.value) }
                        )
                    }
                }
            }

            "multi_choice" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    field.options.forEach { opt ->
                        ChoicePill(
                            label = opt.label,
                            selected = opt.value in selected,
                            accentColor = accentColor,
                            textColor = textColor,
                            trailingArrow = false,
                            onClick = { onToggle(opt.value) }
                        )
                    }
                }
            }

            "checkbox" -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { onValue(if (value == "true") "false" else "true") }
            ) {
                ConsentDot(checked = value == "true", accentColor = accentColor, borderColor = borderColor)
                Spacer(Modifier.width(10.dp))
                Text(field.label, color = labelColor)
            }

            "date" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                val currentYear = remember { Calendar.getInstance().get(Calendar.YEAR) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    DropdownPill(
                        value = dateDay,
                        placeholder = "Day",
                        options = (1..31).map { it.toString() },
                        textColor = textColor,
                        borderColor = borderColor,
                        modifier = Modifier.weight(1f)
                    ) { onDatePart("day", it) }
                    DropdownPill(
                        value = dateMonth,
                        placeholder = "Month",
                        options = (1..12).map { it.toString() },
                        textColor = textColor,
                        borderColor = borderColor,
                        modifier = Modifier.weight(1f)
                    ) { onDatePart("month", it) }
                    DropdownPill(
                        value = dateYear,
                        placeholder = "Year",
                        options = (currentYear downTo currentYear - 100).map { it.toString() },
                        textColor = textColor,
                        borderColor = borderColor,
                        modifier = Modifier.weight(1.3f)
                    ) { onDatePart("year", it) }
                }
            }

            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor)
                OutlinedTextField(
                    value = value,
                    onValueChange = { new ->
                        val limited = field.maxLength?.let { new.take(it) } ?: new
                        onValue(if (field.type == "number") limited.filter { it.isDigit() } else limited)
                    },
                    isError = error,
                    singleLine = true,
                    shape = RoundedCornerShape(percent = 50),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = textColor,
                        unfocusedTextColor = textColor,
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = borderColor,
                        cursorColor = accentColor,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when (field.type) {
                            "email" -> KeyboardType.Email
                            "number" -> KeyboardType.Number
                            else -> KeyboardType.Text
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        field.helper?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = textColor.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun ChoicePill(
    label: String,
    selected: Boolean,
    accentColor: Color,
    textColor: Color,
    trailingArrow: Boolean,
    disabled: Boolean = false,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(percent = 50)
    val isFilled = selected && !disabled
    val contentAlpha = if (disabled) 0.4f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(shape)
            .background(if (isFilled) accentColor else Color.Transparent, shape)
            .border(1.dp, (if (isFilled) accentColor else textColor.copy(alpha = 0.35f)).copy(alpha = contentAlpha), shape)
            .then(if (disabled) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 18.dp, vertical = 10.dp)
    ) {
        if (!trailingArrow && isFilled) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Text(
            text = label,
            color = (if (isFilled) Color.White else textColor).copy(alpha = contentAlpha),
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (isFilled) FontWeight.Bold else FontWeight.Normal,
                textDecoration = if (disabled) TextDecoration.LineThrough else TextDecoration.None
            )
        )
        if (trailingArrow && !disabled) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = if (isFilled) Color.White else textColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ConsentDot(checked: Boolean, accentColor: Color, borderColor: Color) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .border(1.5.dp, if (checked) accentColor else borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        }
    }
}

@Composable
private fun DropdownPill(
    value: String,
    placeholder: String,
    options: List<String>,
    textColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(percent = 50)
    Box(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, borderColor, shape)
                .clickable { expanded = true }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = value.ifBlank { placeholder },
                color = if (value.isBlank()) textColor.copy(alpha = 0.5f) else textColor
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = textColor.copy(alpha = 0.7f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onSelect(opt); expanded = false })
            }
        }
    }
}
