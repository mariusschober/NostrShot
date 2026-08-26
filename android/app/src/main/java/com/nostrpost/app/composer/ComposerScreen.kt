package com.nostrpost.app.composer

import android.view.HapticFeedbackConstants
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ComposerScreen(
    state: ComposerState,
    onContentChange: (String) -> Unit,
    onPublish: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetry: () -> Unit = onPublish,
    onDismissError: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }
    val view = LocalView.current
    val infinite = rememberInfiniteTransition(label = "cursor")
    val cursorAlpha by infinite.animateFloat(
        initialValue = 1f, targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cursorPulse"
    )

    val placeholder = remember {
        val now = LocalTime.now()
        val hour = now.hour
        val prefix = when (hour) {
            in 5..11 -> "Morning ·"
            in 12..16 -> "Afternoon ·"
            in 17..21 -> "Evening ·"
            else -> "Night ·"
        }
        val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
        "$prefix $time  —  What's on your mind?"
    }

    fun tick() { try { view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) {} }
    fun confirm() { try { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM) } catch (_: Exception) {} }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        try { focusRequester.requestFocus() } catch (_: Exception) {}
        tick()
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f),
                        modifier = Modifier.alpha(0.9f)
                    )
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .combinedClickable(
                                onClick = { tick(); onSettingsClick() },
                                onLongClick = { tick(); onSettingsClick() }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("· · ·", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f))
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
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
                        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = state.errorMessage ?: "",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (state.errorMessage?.contains("publish", true) == true ||
                                state.errorMessage?.contains("offline", true) == true) {
                                TextButton(onClick = { tick(); onRetry() }) { Text("Retry") }
                            } else {
                                TextButton(onClick = { onDismissError() }) { Text("Dismiss") }
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
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(successScale)) {
                                    Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Published", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                                }
                            } else {
                                Button(
                                    onClick = { confirm(); onPublish() },
                                    enabled = state.content.trim().isNotEmpty() && !state.isPublishing,
                                    modifier = Modifier
                                        .height(50.dp)
                                        .defaultMinSize(minWidth = 128.dp),
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
                                        Text("Publishing…")
                                    } else {
                                        Text("Publish", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f),
                        modifier = Modifier.align(Alignment.End).padding(bottom = 2.dp)
                    )
                }
            }
        }
    }
}
