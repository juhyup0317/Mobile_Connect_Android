package com.example.mobile_connect_android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Brand {
    const val company = "Songdo IT Solution Inc."
    const val tagline = "Files, editor and terminal for your servers."
    const val monogram = "SD"
    const val name = "Scuti Developer"
}

object Palette {
    val ink = Color(0xFF04050D)
    val navy = Color(0xFF080F29)
    val indigo = Color(0xFF1F1F61)
    val glow = Color(0xFF1A479E)
}

@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "aurora")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "aurora_time"
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        val dx = 0.16f * kotlin.math.sin(time * 0.45f) * width
        val dy = 0.12f * kotlin.math.cos(time * 0.35f) * height

        drawRect(color = Palette.ink)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Palette.glow.copy(alpha = 0.7f), Color.Transparent),
                center = Offset(width * 0.5f + dx, height * 0.5f + dy),
                radius = width * 0.8f
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Palette.indigo.copy(alpha = 0.6f), Color.Transparent),
                center = Offset(width * 0.8f - dx, height * 0.2f),
                radius = width * 0.7f
            )
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Palette.navy.copy(alpha = 0.8f), Color.Transparent),
                center = Offset(width * 0.2f, height * 0.8f - dy),
                radius = width * 0.7f
            )
        )
    }
}

@Composable
fun BrandMark(size: Dp = 72.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.225f))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = Brand.monogram,
            fontSize = (size.value * 0.4f).sp,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
    }
}

@Composable
fun BrandHeader(showsMark: Boolean = true, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showsMark) {
            BrandMark()
        }
        Text(
            text = Brand.name,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center
        )
        Text(
            text = Brand.tagline,
            fontSize = 14.sp,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}
