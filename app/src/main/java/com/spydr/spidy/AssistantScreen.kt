package com.spydr.spidy

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AssistantScreen(
    messages: List<Message>,
    status: String,
    listening: Boolean,
    onMicClick: () -> Unit,
    onStartService: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val micScale by if (listening) {
        infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )
    } else {
        androidx.compose.runtime.rememberUpdatedState(1.0f)
    }

    // Siri-Style Overlay Structure
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f) // Takes up only the bottom half of the screen
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xED0F172A)) // Glassmorphism backdrop tint
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // Header status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Spidy Assist",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = status,
                            color = if (listening) Color(0xFF10B981) else Color(0xFF94A3B8),
                            fontSize = 13.sp
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Scrollable Feed
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(messages) { message ->
                        MessageBubble(message)
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Action Tray
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingActionButton(
                        onClick = onMicClick,
                        containerColor = if (listening) Color(0xFFEF4444) else Color(0xFF3B82F6),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier.scale(micScale)
                    ) {
                        Icon(imageVector = if (listening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val bubbleShape = if (message.user) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 2.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 2.dp, bottomEnd = 16.dp)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start
    ) {
        Card(
            shape = bubbleShape,
            colors = CardDefaults.cardColors(
                containerColor = if (message.user) Color(0xFF2563EB) else Color(0xFF334155)
            ),
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .wrapContentWidth(align = if (message.user) Alignment.End else Alignment.Start)
        ) {
            Text(
                text = message.text,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
