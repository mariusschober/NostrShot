package com.nostrpost.app.composer

import android.content.Intent
import android.net.Uri
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nostrpost.app.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ComposerScreen(
    state: ComposerState,
    onContentChange: (String) -> Unit,
    onPublish: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit = onPublish,
    onDismissError: () -> Unit = {},
    onOpenSettingsFromError: () -> Unit = onSettingsClick,
    onDone: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val publishedNote = state.publishedNote
    val infinite = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cursorPulse"
    )

    val now = remember { LocalTime.now() }
    val periodLabel = when (now.hour) {
        in 5..11 -> stringResource(R.string.composer_period_morning)
        in 12..16 -> stringResource(R.string.composer_period_afternoon)
        in 17..21 -> stringResource(R.string.composer_period_evening)
        else -> stringResource(R.string.composer_period_night)
    }
    val placeholder = stringResource(
        R.string.composer_placeholder,
        periodLabel,
        now.format(DateTimeFormatter.ofPattern("HH:mm"))
    )

    fun tick() { try { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) {} }
    fun confirm() { try { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) } catch (_: Exception) {} }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
        tick()
    }

    // Keep the published confirmation actionable for a moment, then close the capture app.
    LaunchedEffect(state.showSuccess) {
        if (state.showSuccess) {
            kotlinx.coroutines.delay(8000)
            onDone()
        }
    }

    val successScale by animateFloatAsState(
        targetValue = if (state.showSuccess) 1f else 0.7f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 700f),
        label = "successScale"
    )

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .systemBarsPadding()
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.alpha(0.95f)
                    )
                    IconButton(
                        onClick = { tick(); onSettingsClick() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = stringResource(R.string.cd_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 18.dp, bottom = 12.dp),
                    contentAlignment = Alignment.TopStart
                ) {
                    if (state.content.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = TextStyle(
                                fontSize = 23.sp,
                                lineHeight = 32.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                fontWeight = FontWeight.Normal
                            ),
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    BasicTextField(
                        value = state.content,
                        onValueChange = {
                            onContentChange(it)
                            if (it.length == 1) tick()
                        },
                        modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                        textStyle = TextStyle(
                            fontSize = 23.sp,
                            lineHeight = 32.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary.copy(alpha = cursorAlpha)),
                        decorationBox = { inner -> inner() }
                    )
                }

                AnimatedVisibility(
                    visible = state.errorMessage != null,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = state.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                when (state.errorAction) {
                                    ComposerErrorAction.RETRY -> {
                                        TextButton(onClick = { tick(); onRetry() }) { Text(stringResource(R.string.action_retry)) }
                                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) }
                                    }
                                    ComposerErrorAction.OPEN_SETTINGS -> {
                                        TextButton(onClick = onOpenSettingsFromError) { Text(stringResource(R.string.action_settings)) }
                                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) }
                                    }
                                    ComposerErrorAction.DISMISS -> {
                                        TextButton(onClick = onDismissError) { Text(stringResource(R.string.action_dismiss)) }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth()) {
                        AnimatedContent(
                            targetState = state.showSuccess,
                            transitionSpec = {
                                (fadeIn(tween(180)) + scaleIn(spring(stiffness = 700f)))
                                    .togetherWith(fadeOut(tween(120)))
                            },
                            label = "publishContent"
                        ) { success ->
                            if (success) {
                                Column(
                                    horizontalAlignment = Alignment.End,
                                    modifier = Modifier
                                        .alpha(successScale)
                                        .semantics {
                                            liveRegion = LiveRegionMode.Polite
                                            stateDescription = "Published"
                                        }
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(R.string.status_published), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                    }
                                    if (publishedNote != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = {
                                                clipboard.setText(AnnotatedString(publishedNote.nevent))
                                                Toast.makeText(context, context.getString(R.string.note_link_copied), Toast.LENGTH_SHORT).show()
                                            }) { Text(stringResource(R.string.action_copy_link)) }
                                            TextButton(onClick = {
                                                try {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(publishedNote.url)))
                                                } catch (_: Exception) {
                                                    Toast.makeText(context, context.getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
                                                }
                                            }) { Text(stringResource(R.string.action_open)) }
                                            TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) }
                                        }
                                    }
                                }
                            } else {
                                Button(
                                    onClick = { confirm(); onPublish() },
                                    enabled = state.content.trim().isNotEmpty() && !state.isPublishing,
                                    modifier = Modifier
                                        .height(50.dp)
                                        .defaultMinSize(minWidth = 128.dp)
                                        .semantics {
                                            if (state.isPublishing) stateDescription = "Publishing"
                                        },
                                    shape = MaterialTheme.shapes.extraLarge,
                                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp),
                                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp, pressedElevation = 1.dp)
                                ) {
                                    if (state.isPublishing) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.action_publishing))
                                    } else {
                                        Text(stringResource(R.string.action_publish), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.content.length > 200) {
                    Text(
                        text = "${state.content.length}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        modifier = Modifier.align(Alignment.End).padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}
