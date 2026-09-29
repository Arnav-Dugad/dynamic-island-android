package com.arnav.island.settings.reply

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arnav.island.IslandApp
import com.arnav.island.util.ColorExtractor
import com.arnav.island.util.Launch
import kotlinx.coroutines.launch

/**
 * What the island hands to the reply sheet. Held in memory only (never written anywhere) and
 * dropped as soon as the sheet closes.
 */
class ReplyRequest(
    val key: String,
    val title: String,
    val appLabel: String,
    val packageName: String,
    val message: String,
    val label: String,
    val accent: Int,
    val action: Notification.Action,
) {
    /** The first free-form input the app asked for, and its suggested answers. */
    val input: RemoteInput? get() = action.remoteInputs.orEmpty().firstOrNull { it.allowFreeFormInput }

    companion object {
        @Volatile
        private var pending: ReplyRequest? = null

        fun launch(context: Context, request: ReplyRequest) {
            pending = request
            val intent = Intent(context, ReplyActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            Launch.startActivity(context, intent)
        }

        internal fun current(): ReplyRequest? = pending

        /**
         * A test conversation for demos. Its reply goes to a broadcast inside Island that
         * nothing listens to, so the whole flow can be tried without messaging anyone.
         */
        fun test(context: Context): ReplyRequest {
            val intent = Intent(ACTION_TEST_REPLY).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val input = RemoteInput.Builder("test_reply")
                .setLabel("Reply")
                .setChoices(arrayOf("On my way", "Sounds good", "Call you later"))
                .build()
            val action = Notification.Action.Builder(null as Icon?, "Reply", pending).addRemoteInput(input).build()
            return ReplyRequest(
                key = "test",
                title = "Test group",
                appLabel = "Island",
                packageName = context.packageName,
                message = "Test Blake: Group chats show who is talking",
                label = "Reply",
                accent = 0xFF2FBF71.toInt(),
                action = action,
            )
        }

        private const val ACTION_TEST_REPLY = "com.arnav.island.TEST_REPLY"

        internal fun clear(request: ReplyRequest) {
            if (pending === request) pending = null
        }
    }
}

/**
 * Quick reply: a small black card that drops out of the island with the keyboard already up.
 * The text is delivered through the notification's own reply action, so the app receives it
 * exactly as if it had been typed in the notification shade.
 */
class ReplyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ReplyRequest.current()
        if (request == null) {
            finish()
            return
        }
        setContent { ReplySheet(request, ::send, ::close) }
    }

    private fun send(request: ReplyRequest, text: String): Boolean {
        val input = request.input ?: return false
        val fillIn = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(arrayOf(input), fillIn, Bundle().apply { putCharSequence(input.resultKey, text) })
        RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
        return Launch.send(this, request.action.actionIntent, fillIn)
    }

    private fun close(request: ReplyRequest) {
        ReplyRequest.clear(request)
        finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

private val CardBlack = Color(0xFF000000)
private val Hairline = Color(0x1FFFFFFF)
private val FieldFill = Color(0x1CFFFFFF)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xB3FFFFFF)
private val TextTertiary = Color(0x73FFFFFF)

@Composable
private fun ReplySheet(request: ReplyRequest, send: (ReplyRequest, String) -> Boolean, close: (ReplyRequest) -> Unit) {
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val accent = remember(request.accent) {
        Color(if (request.accent != 0) ColorExtractor.legibleOnBlack(request.accent) else 0xFF4C9BFF.toInt())
    }
    val icon = remember(request.packageName) {
        (view.context.applicationContext as? IslandApp)?.graph?.apps?.cachedIcon(request.packageName, 96)?.asImageBitmap()
    }
    val choices = remember(request) { request.input?.choices.orEmpty().map { it.toString() }.filter { it.isNotBlank() }.take(4) }

    var text by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // The card grows out of the island (top centre) and folds back into it when it closes.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 420f))
    }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun dismiss() {
        scope.launch {
            reveal.animateTo(0f, spring(dampingRatio = 1f, stiffness = 900f))
            close(request)
        }
    }

    fun deliver(message: String) {
        val body = message.trim()
        if (body.isEmpty() || sent) return
        if (send(request, body)) {
            sent = true
            failed = false
            view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
            scope.launch {
                kotlinx.coroutines.delay(420)
                reveal.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f))
                close(request)
            }
        } else {
            failed = true
            if (Build.VERSION.SDK_INT >= 30) view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        }
    }

    BackHandler { dismiss() }

    Box(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { dismiss() },
        contentAlignment = Alignment.TopCenter,
    ) {
        val v = reveal.value
        Column(
            Modifier
                .statusBarsPadding()
                .padding(top = 6.dp, start = 12.dp, end = 12.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    scaleX = 0.36f + 0.64f * v
                    scaleY = 0.18f + 0.82f * v
                    alpha = (v * 1.6f).coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(34.dp))
                .background(CardBlack)
                .border(1.dp, Hairline, RoundedCornerShape(34.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // Contents fade in slightly after the shape so the morph reads first.
            val contentAlpha = ((v - 0.35f) / 0.65f).coerceIn(0f, 1f)
            Row(Modifier.alpha(contentAlpha), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Image(icon, null, Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)))
                } else {
                    Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(FieldFill), contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Rounded.Reply, null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            request.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (request.title != request.appLabel) {
                            Text("  ${request.appLabel}", color = TextTertiary, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                    if (request.message.isNotBlank()) {
                        Text(request.message, color = TextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            if (choices.isNotEmpty() && !sent) {
                Spacer(Modifier.size(12.dp))
                Row(
                    Modifier.alpha(contentAlpha).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    choices.forEach { choice ->
                        Text(
                            choice, color = TextPrimary, fontSize = 14.sp, maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(accent.copy(alpha = 0.22f))
                                .clickable { deliver(choice) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.size(12.dp))
            Row(Modifier.alpha(contentAlpha), verticalAlignment = Alignment.Bottom) {
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(FieldFill)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (text.isEmpty()) {
                        Text(
                            if (failed) "Couldn't send. ${request.appLabel} may have closed this chat." else "${request.label}…",
                            color = if (failed) Color(0xFFFF6B61) else TextTertiary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it; failed = false },
                        enabled = !sent,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { deliver(text) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Spacer(Modifier.width(8.dp))
                SendButton(accent, enabled = text.isNotBlank() || sent, sent = sent) { deliver(text) }
            }
        }
    }
}

@Composable
private fun SendButton(accent: Color, enabled: Boolean, sent: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (enabled) 1f else 0.82f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), label = "send-scale")
    val fill by animateFloatAsState(if (enabled) 1f else 0f, tween(160), label = "send-fill")
    Box(
        Modifier
            .size(44.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(lerp(FieldFill, if (sent) Color(0xFF34C759) else accent, fill))
            .clickable(enabled = enabled && !sent, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = sent,
            transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "send-icon",
        ) { done ->
            Icon(
                if (done) Icons.Rounded.Check else Icons.Rounded.ArrowUpward,
                contentDescription = if (done) "Sent" else "Send",
                tint = if (enabled) Color.Black else TextTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun lerp(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)
