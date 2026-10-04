package com.spydr.spidy

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlin.math.sin

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val user: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

// Design tokens
private val ColorBackground   = Color(0xFF080C12)
private val ColorSurface      = Color(0xFF0D1117)
private val ColorBorderSubtle = Color(0x1AFFFFFF)
private val ColorBorderBright = Color(0x33FFFFFF)
private val ColorAccentBlue   = Color(0xFF3B82F6)
private val ColorAccentViolet = Color(0xFF7C3AED)
private val ColorAccentPink   = Color(0xFFEC4899)
private val ColorAccentGreen  = Color(0xFF10B981)
private val ColorAccentAmber  = Color(0xFFF59E0B)
private val ColorTextPrimary  = Color(0xFFF1F5F9)
private val ColorTextSecondary = Color(0xFF64748B)
private val ColorTextMuted    = Color(0xFF334155)
private val ColorUserBubble   = Color(0xFF1D4ED8)
private val ColorAssistBubble = Color(0xFF111827)

// Spring specs
private val SpringSnappy = spring<Float>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)
private val SpringSmooth = spring<Float>(
    dampingRatio = Spring.DampingRatioLowBouncy,
    stiffness = Spring.StiffnessLow
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    messages: List<Message>,
    status: String,
    listening: Boolean,
    onMicClick: () -> Unit,
    onSendTextCommand: (String) -> Unit,
    onStartService: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { onStartService() }

    val listState = rememberLazyListState()
    val lastMessageText = messages.lastOrNull()?.text ?: ""
    LaunchedEffect(messages.size, lastMessageText) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "global")

    // Aura strip gradient sweep
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1400f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Restart),
        label = "auraOffset"
    )

    // Orb breathe — slow, calm when idle
    val orbBreath by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            tween(if (listening) 700 else 2800, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "orbBreath"
    )

    // Orb rotation
    val orbRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(8000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbRotation"
    )

    // Mic ring pulse — only when listening
    val micRing by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.45f else 1f,
        animationSpec = infiniteRepeatable(
            tween(600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "micRing"
    )

    // Animated fab color
    val fabColor by animateColorAsState(
        targetValue = when {
            textInput.isNotBlank() -> ColorAccentBlue
            listening -> Color(0xFFDC2626)
            else -> Color(0xFF1E293B)
        },
        animationSpec = tween(250),
        label = "fabColor"
    )

    val submitQuery = {
        val query = textInput.trim()
        if (query.isNotBlank()) {
            textInput = ""
            onSendTextCommand(query)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── NEON AURA STRIP ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                ColorAccentBlue,
                                ColorAccentViolet,
                                ColorAccentPink,
                                ColorAccentAmber,
                                ColorAccentGreen,
                                ColorAccentBlue,
                                Color.Transparent,
                            ),
                            startX = gradientOffset - 600f,
                            endX = gradientOffset + 800f
                        )
                    )
            )

            // Soft glow halo below strip
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.5f)
                    .height(12.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                ColorAccentViolet.copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(Modifier.height(2.dp))

            // ── MAIN GLASS CARD ──────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(ColorBorderBright, ColorBorderSubtle)
                        ),
                        shape = RoundedCornerShape(28.dp)
                    ),
                shape = RoundedCornerShape(28.dp),
                color = ColorSurface.copy(alpha = 0.97f),
                shadowElevation = 32.dp,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {

                    // ── CHAT STREAM ──────────────────────────────────────
                    AnimatedVisibility(
                        visible = messages.isNotEmpty(),
                        enter = fadeIn(tween(300)) + expandVertically(
                            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium)
                        ),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                    ) {
                        Column {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                itemsIndexed(
                                    items = messages,
                                    key = { _, item -> item.id }
                                ) { index, message ->
                                    val isNewest = index == messages.size - 1
                                    AnimatedMessageBubble(message = message, animateIn = isNewest)
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }

                    // ── HEADER ROW: ORB + NAME + STATUS + WAVEFORM ───────
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Premium animated orb
                            SpidyOrb(
                                breathing = orbBreath,
                                rotation = orbRotation,
                                listening = listening
                            )

                            Column {
                                Text(
                                    text = "Spidy",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ColorTextPrimary,
                                    letterSpacing = (-0.3).sp
                                )
                                AnimatedContent(
                                    targetState = status,
                                    transitionSpec = {
                                        fadeIn(tween(200)) + slideInVertically { it / 2 } togetherWith
                                                fadeOut(tween(150)) + slideOutVertically { -it / 2 }
                                    },
                                    label = "statusText"
                                ) { s ->
                                    Text(
                                        text = s,
                                        color = when {
                                            listening -> ColorAccentGreen
                                            s.contains("think", ignoreCase = true) -> ColorAccentAmber
                                            s.contains("speak", ignoreCase = true) -> ColorAccentViolet
                                            else -> ColorTextSecondary
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        letterSpacing = 0.2.sp
                                    )
                                }
                            }
                        }

                        // Waveform — shown when listening or speaking
                        AnimatedVisibility(
                            visible = listening,
                            enter = fadeIn(tween(300)) + scaleIn(SpringSnappy, initialScale = 0.7f),
                            exit = fadeOut(tween(200)) + scaleOut(tween(150), targetScale = 0.7f)
                        ) {
                            RichWaveformVisualizer()
                        }
                    }

                    // ── INPUT DOCK ───────────────────────────────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            placeholder = {
                                Text(
                                    text = "Ask Spidy anything…",
                                    color = ColorTextMuted,
                                    fontSize = 14.sp
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(22.dp),
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { submitQuery() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0x14FFFFFF),
                                unfocusedContainerColor = Color(0x08FFFFFF),
                                focusedBorderColor = ColorAccentBlue.copy(alpha = 0.7f),
                                unfocusedBorderColor = ColorBorderSubtle,
                                focusedTextColor = ColorTextPrimary,
                                unfocusedTextColor = ColorTextPrimary,
                                cursorColor = ColorAccentBlue
                            )
                        )

                        // FAB with ripple ring when listening
                        Box(contentAlignment = Alignment.Center) {
                            if (listening && textInput.isBlank()) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .graphicsLayer {
                                            scaleX = micRing
                                            scaleY = micRing
                                            alpha = 1f - ((micRing - 1f) / 0.45f) * 0.85f
                                        }
                                        .clip(CircleShape)
                                        .background(Color(0x55DC2626))
                                )
                            }

                            FloatingActionButton(
                                onClick = {
                                    if (textInput.isNotBlank()) submitQuery()
                                    else onMicClick()
                                },
                                containerColor = fabColor,
                                contentColor = Color.White,
                                shape = CircleShape,
                                modifier = Modifier
                                    .size(48.dp)
                                    .border(1.dp, ColorBorderSubtle, CircleShape)
                            ) {
                                AnimatedContent(
                                    targetState = textInput.isNotBlank(),
                                    transitionSpec = {
                                        fadeIn(tween(160)) + scaleIn(
                                            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessHigh),
                                            initialScale = 0.6f
                                        ) togetherWith fadeOut(tween(100)) + scaleOut(tween(100), 0.6f)
                                    },
                                    label = "fabIcon"
                                ) { hasText ->
                                    if (hasText) {
                                        Icon(Icons.Default.Send, "Send", Modifier.size(20.dp))
                                    } else {
                                        Icon(
                                            if (listening) Icons.Default.Stop else Icons.Default.Mic,
                                            if (listening) "Stop" else "Listen",
                                            Modifier.size(22.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── PREMIUM ANIMATED ORB ─────────────────────────────────────────────────────

@Composable
fun SpidyOrb(
    breathing: Float,
    rotation: Float,
    listening: Boolean
) {
    val glowColor = if (listening) ColorAccentPink else ColorAccentViolet

    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = breathing
                scaleY = breathing
                rotationZ = rotation * 0.4f
            },
        contentAlignment = Alignment.Center
    ) {
        // Outer glow ring
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(glowColor.copy(alpha = 0.15f))
                .blur(8.dp)
        )

        // Inner gradient orb
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(ColorAccentBlue, ColorAccentViolet, ColorAccentPink),
                        start = Offset(0f, 0f),
                        end = Offset(100f, 100f)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // Shimmer highlight
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .offset(x = (-6).dp, y = (-6).dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.35f))
                    .blur(4.dp)
            )

            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ── RICH WAVEFORM VISUALIZER ─────────────────────────────────────────────────

@Composable
fun RichWaveformVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    // 7 bars with varied speeds and phase offsets for organic feel
    val barSpecs = listOf(
        Triple(0.15f, 1.0f,  260),
        Triple(0.55f, 0.3f,  190),
        Triple(0.25f, 0.85f, 310),
        Triple(0.8f,  0.2f,  230),
        Triple(0.3f,  0.9f,  170),
        Triple(0.65f, 0.4f,  290),
        Triple(0.1f,  0.75f, 210),
    )

    val barColors = listOf(
        ColorAccentBlue,
        ColorAccentViolet,
        ColorAccentPink,
        ColorAccentAmber,
        ColorAccentGreen,
        ColorAccentViolet,
        ColorAccentBlue,
    )

    val heights = barSpecs.mapIndexed { i, (init, target, dur) ->
        infiniteTransition.animateFloat(
            initialValue = init,
            targetValue = target,
            animationSpec = infiniteRepeatable(
                tween(dur, easing = FastOutSlowInEasing),
                RepeatMode.Reverse
            ),
            label = "bar$i"
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(28.dp)
    ) {
        heights.forEachIndexed { i, heightState ->
            Box(
                Modifier
                    .width(2.5.dp)
                    .fillMaxHeight(heightState.value.coerceIn(0.12f, 1f))
                    .clip(CircleShape)
                    .background(barColors[i].copy(alpha = 0.85f + heightState.value * 0.15f))
            )
        }
    }
}

// ── ANIMATED MESSAGE BUBBLE ──────────────────────────────────────────────────

@Composable
fun AnimatedMessageBubble(message: Message, animateIn: Boolean) {
    var visible by remember { mutableStateOf(!animateIn) }
    LaunchedEffect(Unit) { if (animateIn) visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)) + slideInVertically(
            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
            initialOffsetY = { it / 3 }
        ) + scaleIn(
            spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium),
            initialScale = 0.92f
        )
    ) {
        GlassMessageBubble(message)
    }
}

@Composable
fun GlassMessageBubble(message: Message) {
    val bubbleShape = if (message.user) {
        RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
    } else {
        RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = if (message.user) ColorUserBubble else ColorAssistBubble,
            modifier = Modifier
                .widthIn(max = 280.dp)
                .border(
                    width = 0.7.dp,
                    color = if (message.user) Color(0x33FFFFFF) else Color(0x18FFFFFF),
                    shape = bubbleShape
                )
        ) {
            Text(
                text = message.text,
                color = ColorTextPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
            )
        }
    }
}