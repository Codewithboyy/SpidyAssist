package com.spydr.spidy

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AssistantScreen(
    messages: List<Message>,
    status: String,
    listening: Boolean,
    onMicClick: () -> Unit,
    onStartService: () -> Unit,
    onDismiss: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    // Smooth scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Gradient shift animation for the top aura rim
    val infiniteTransition = rememberInfiniteTransition(label = "gemini_aura")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "auraOffset"
    )

    val micPulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (listening) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "micPulse"
    )

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
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- TOP GEMINI NEON LIGHT STRIP ---
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .height(3.5.dp)
                    .clip(CircleShape)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF4285F4), // Google Blue
                                Color(0xFF9B51E0), // Deep Purple
                                Color(0xFFEA4335), // Coral Red
                                Color(0xFFFBBC05), // Warm Gold
                                Color(0xFF34A853), // Green
                                Color(0xFF4285F4)  // Blue Loop
                            ),
                            startX = gradientOffset,
                            endX = gradientOffset + 800f
                        )
                    )
            )

            Spacer(modifier = Modifier.height(4.dp))

            // --- MAIN GLASSMORPHISM CARD ---
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0x66FFFFFF),
                                Color(0x11FFFFFF)
                            )
                        ),
                        shape = RoundedCornerShape(32.dp)
                    ),
                shape = RoundedCornerShape(32.dp),
                color = Color(0xF20B0F17), // Deep Obsidian Glass
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {

                    // --- CHAT STREAM DISPLAY (Top Position) ---
                    if (messages.isNotEmpty()) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 230.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(messages, key = { it.hashCode() }) { message ->
                                GlassMessageBubble(message)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    // --- BOTTOM ASSISTANT CONTROL DOCK ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: Branding & Dynamic Voice Status
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF3B82F6), Color(0xFF8B5CF6))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = "Spidy AI",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Column {
                                Text(
                                    text = "Spidy",
                                    fontSize = 19.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Text(
                                    text = status,
                                    color = if (listening) Color(0xFF34D399) else Color(0xFF94A3B8),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Center/Right: Listening Waveform Animation & Mic Trigger
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (listening) {
                                VoiceWaveformVisualizer()
                            }

                            Box(contentAlignment = Alignment.Center) {
                                if (listening) {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .graphicsLayer {
                                                scaleX = micPulseScale
                                                scaleY = micPulseScale
                                            }
                                            .clip(CircleShape)
                                            .background(Color(0x333B82F6))
                                    )
                                }

                                FloatingActionButton(
                                    onClick = onMicClick,
                                    containerColor = if (listening) Color(0xFFEF4444) else Color(0xFF2563EB),
                                    contentColor = Color.White,
                                    shape = CircleShape,
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = "Microphone",
                                        modifier = Modifier.size(22.dp)
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

// --- Dynamic Audio Visualizer Bar Component ---
@Composable
fun VoiceWaveformVisualizer() {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    val h1 by infiniteTransition.animateFloat(
        initialValue = 6f, targetValue = 22f,
        animationSpec = infiniteRepeatable(tween(300, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "h1"
    )
    val h2 by infiniteTransition.animateFloat(
        initialValue = 18f, targetValue = 8f,
        animationSpec = infiniteRepeatable(tween(250, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "h2"
    )
    val h3 by infiniteTransition.animateFloat(
        initialValue = 10f, targetValue = 26f,
        animationSpec = infiniteRepeatable(tween(350, easing = LinearOutSlowInEasing), RepeatMode.Reverse),
        label = "h3"
    )

    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(28.dp)
    ) {
        Box(Modifier.width(3.5.dp).height(h1.dp).clip(CircleShape).background(Color(0xFF60A5FA)))
        Box(Modifier.width(3.5.dp).height(h2.dp).clip(CircleShape).background(Color(0xFFA78BFA)))
        Box(Modifier.width(3.5.dp).height(h3.dp).clip(CircleShape).background(Color(0xFFF472B6)))
    }
}

// --- Glass Chat Bubble Component ---
@Composable
fun GlassMessageBubble(message: Message) {
    val bubbleShape = if (message.user) {
        RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    } else {
        RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = if (message.user) Color(0xFF2563EB) else Color(0xFF1E293B),
            modifier = Modifier.border(
                width = 0.8.dp,
                color = if (message.user) Color(0x40FFFFFF) else Color(0x20FFFFFF),
                shape = bubbleShape
            )
        ) {
            Text(
                text = message.text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}
