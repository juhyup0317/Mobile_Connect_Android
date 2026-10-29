package com.example.mobile_connect_android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.example.mobile_connect_android.data.KeyId
import com.example.mobile_connect_android.domain.AppModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyBarSettingsScreen(
    model: AppModel,
    onBack: () -> Unit,
    bottomPadding: Dp = 0.dp
) {
    val prefs by model.prefs.collectAsState()
    val keys = prefs.keys

    BackHandler(onBack = onBack)
    val previewState = remember { KeyBarState() }

    fun moveUp(index: Int) {
        if (index <= 0) return
        val mutable = keys.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(index - 1, item)
        model.updatePrefs { it.keys = mutable }
    }

    fun moveDown(index: Int) {
        if (index >= keys.size - 1) return
        val mutable = keys.toMutableList()
        val item = mutable.removeAt(index)
        mutable.add(index + 1, item)
        model.updatePrefs { it.keys = mutable }
    }

    fun removeKey(key: KeyId) {
        val mutable = keys.toMutableList()
        mutable.remove(key)
        model.updatePrefs { it.keys = mutable }
    }

    fun addKey(key: KeyId) {
        if (!keys.contains(key)) {
            val mutable = keys.toMutableList()
            mutable.add(key)
            model.updatePrefs { it.keys = mutable }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Customize Keyboard", fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp)
                    }
                },
                actions = {
                    TextButton(onClick = { model.updatePrefs { it.keys = KeyId.defaults } }) {
                        Text("Reset")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            // Live Preview Section
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Live Preview", color = Color(0xFF2196F3), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    KeyBarView(
                        keys = keys,
                        state = previewState,
                        onKey = { key ->
                            key.modifier?.let { previewState.tap(it) }
                        }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + bottomPadding)
            ) {
                // In the bar section
                item {
                    Text("In the Bar (${keys.size})", color = Color(0xFF2196F3), fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                }

                itemsIndexed(keys) { index, key ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(key.label, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(key.title, color = Color.Gray, fontSize = 13.sp)
                            }

                            Row {
                                IconButton(onClick = { moveUp(index) }, enabled = index > 0) {
                                    Text("▲", color = if (index > 0) Color.White else Color.DarkGray, fontSize = 14.sp)
                                }
                                IconButton(onClick = { moveDown(index) }, enabled = index < keys.size - 1) {
                                    Text("▼", color = if (index < keys.size - 1) Color.White else Color.DarkGray, fontSize = 14.sp)
                                }
                                IconButton(onClick = { removeKey(key) }) {
                                    Text("✕", color = Color(0xFFFF5F56), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }

                // Available groups section
                KeyId.groups.forEach { group ->
                    val availableInGroup = KeyId.entries.filter { it.group == group && !keys.contains(it) }
                    if (availableInGroup.isNotEmpty()) {
                        item {
                            Text(group, color = Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                        }

                        itemsIndexed(availableInGroup) { _, key ->
                            Surface(
                                color = Color(0xFF1E1E24),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { addKey(key) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(key.label, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                                        }
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(key.title, color = Color.Gray, fontSize = 13.sp)
                                    }

                                    Text("+", color = Color(0xFF5AF78E), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
