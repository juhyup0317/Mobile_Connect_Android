package com.example.mobile_connect_android.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.PaddingValues
import com.example.mobile_connect_android.data.TermNote
import com.example.mobile_connect_android.domain.AppModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class NoteDay(val title: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    WEEK("Last 7 Days"),
    EARLIER("Earlier");

    companion object {
        fun of(date: Date): NoteDay {
            val now = Calendar.getInstance()
            val target = Calendar.getInstance().apply { time = date }

            if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)) {
                return TODAY
            }

            now.add(Calendar.DAY_OF_YEAR, -1)
            if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)) {
                return YESTERDAY
            }

            now.add(Calendar.DAY_OF_YEAR, -5)
            if (target.after(now)) {
                return WEEK
            }

            return EARLIER
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(model: AppModel, bottomPadding: Dp = 0.dp) {
    val notes by model.watch.notes.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }

    val finishedCount = remember(notes) { notes.count { it.kind == TermNote.Kind.DONE } }
    val failedCount = remember(notes) { notes.count { it.kind == TermNote.Kind.FAILED } }
    val askedCount = remember(notes) { notes.count { it.kind == TermNote.Kind.ASK } }

    val groupedNotes = remember(notes) {
        notes.groupBy { NoteDay.of(Date(it.at.toLong())) }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Notifications", fontSize = 18.sp) },
                actions = {
                    if (model.watch.unread > 0) {
                        TextButton(onClick = { model.watch.markRead() }) {
                            Text("Mark All Read")
                        }
                    }
                    if (notes.isNotEmpty()) {
                        TextButton(onClick = { showClearDialog = true }) {
                            Text("Clear All")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(top = innerPadding.calculateTopPadding())
        ) {
            if (notes.isNotEmpty()) {
                // Summary cards row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryTile("Finished", finishedCount, "✓", Color(0xFF5AF78E), Modifier.weight(1f))
                    SummaryTile("Failed", failedCount, "✕", Color(0xFFFF5F56), Modifier.weight(1f))
                    SummaryTile("Asked", askedCount, "?", Color(0xFFFFC107), Modifier.weight(1f))
                }
            }

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔔", fontSize = 48.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Notifications", color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text("Command completed alerts will show up here.", color = Color.Gray, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
                    NoteDay.entries.forEach { day ->
                        val dayNotes = groupedNotes[day] ?: emptyList()
                        if (dayNotes.isNotEmpty()) {
                            item {
                                Text(
                                    text = day.title,
                                    color = Color.Gray,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                            items(dayNotes) { note ->
                                NoteRow(
                                    note = note,
                                    title = model.noteTitle(note),
                                    onClick = {
                                        model.watch.markRead(note.term)
                                        model.openTerminal(note.term)
                                    }
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AppDialog(
            title = "Clear all notifications?",
            text = "Every notification will be removed from this list.",
            onDismiss = { showClearDialog = false },
            confirmText = "Clear all",
            destructive = true,
            onConfirm = {
                showClearDialog = false
                model.watch.clear()
            }
        )
    }
}

@Composable
fun SummaryTile(label: String, count: Int, symbol: String, tint: Color, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(symbol, color = tint, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(count.toString(), color = MaterialTheme.colorScheme.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(label, color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@Composable
fun NoteRow(note: TermNote, title: String, onClick: () -> Unit) {
    val tint = when (note.kind) {
        TermNote.Kind.DONE -> Color(0xFF5AF78E)
        TermNote.Kind.FAILED -> Color(0xFFFF5F56)
        TermNote.Kind.ASK -> Color(0xFFFFC107)
    }

    val symbol = when (note.kind) {
        TermNote.Kind.DONE -> "✓"
        TermNote.Kind.FAILED -> "✕"
        TermNote.Kind.ASK -> "?"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = tint, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(note.body, color = tint, fontSize = 13.sp, maxLines = 2)
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column(horizontalAlignment = Alignment.End) {
            val date = Date(note.at.toLong())
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
            Text(timeStr, color = Color.Gray, fontSize = 12.sp)
            if (!note.read) {
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2196F3))
                )
            }
        }
    }
}
