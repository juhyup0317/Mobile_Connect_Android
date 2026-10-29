package com.example.mobile_connect_android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mobile_connect_android.data.KeyId
import com.example.mobile_connect_android.data.Mods

enum class ModifierLevel { OFF, ONCE, LOCKED }

class KeyBarState {
    var ctrl by mutableStateOf(ModifierLevel.OFF)
    var alt by mutableStateOf(ModifierLevel.OFF)
    var shift by mutableStateOf(ModifierLevel.OFF)

    val mods: Mods
        get() = Mods(ctrl != ModifierLevel.OFF, alt != ModifierLevel.OFF, shift != ModifierLevel.OFF)

    fun level(m: KeyId.Modifier): ModifierLevel = when (m) {
        KeyId.Modifier.CTRL -> ctrl
        KeyId.Modifier.ALT -> alt
        KeyId.Modifier.SHIFT -> shift
    }

    fun tap(m: KeyId.Modifier) {
        fun next(l: ModifierLevel) = when (l) {
            ModifierLevel.OFF -> ModifierLevel.ONCE
            ModifierLevel.ONCE -> ModifierLevel.LOCKED
            ModifierLevel.LOCKED -> ModifierLevel.OFF
        }
        when (m) {
            KeyId.Modifier.CTRL -> ctrl = next(ctrl)
            KeyId.Modifier.ALT -> alt = next(alt)
            KeyId.Modifier.SHIFT -> shift = next(shift)
        }
    }

    fun consume() {
        if (ctrl == ModifierLevel.ONCE) ctrl = ModifierLevel.OFF
        if (alt == ModifierLevel.ONCE) alt = ModifierLevel.OFF
        if (shift == ModifierLevel.ONCE) shift = ModifierLevel.OFF
    }
}

@Composable
fun BarButton(
    title: String,
    label: String,
    active: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (active) Color(0xFF2196F3) else Color.White.copy(alpha = 0.15f)
    val textColor = if (active) Color.White else Color.Unspecified

    Box(
        modifier = modifier
            .height(36.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
            if (locked) {
                Box(
                    modifier = Modifier
                        .height(3.dp)
                        .fillMaxWidth(0.5f)
                        .background(Color.White, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
fun KeyBarView(
    keys: List<KeyId>,
    state: KeyBarState,
    onKey: (KeyId) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color(0xFF1E1E24))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        keys.forEach { key ->
            val level = key.modifier?.let { state.level(it) } ?: ModifierLevel.OFF
            BarButton(
                title = key.title,
                label = key.label,
                active = level != ModifierLevel.OFF,
                locked = level == ModifierLevel.LOCKED,
                onClick = { onKey(key) },
                modifier = Modifier.padding(end = 6.dp)
            )
        }
    }
}
