package com.joshiminh.cbzconverter.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.joshiminh.cbzconverter.core.SelectedFileInfo

@Composable
fun ManualSelectionCard(selectedFileName: String, selectedFilesUri: List<Uri>, isCurrentlyConverting: Boolean, onSelectFiles: () -> Unit) {
    val firstLine = selectedFileName.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
    val summary = when {
        selectedFilesUri.isEmpty() -> "No files selected."
        firstLine.isNotBlank() && selectedFilesUri.size > 1 -> "$firstLine (+${selectedFilesUri.size - 1} more)"
        firstLine.isNotBlank() -> firstLine
        else -> "${selectedFilesUri.size} file(s) selected."
    }
    Column(Modifier.fillMaxWidth()) {
        Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSelectFiles, enabled = !isCurrentlyConverting, modifier = Modifier.fillMaxWidth()) { Text("Select CBZ File(s)") }
    }
}

@Composable
fun SelectedFilesList(selectedFiles: List<Uri>, resolveInfo: (Uri) -> SelectedFileInfo, onMove: (Int, Int) -> Unit, onRemove: (Uri) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        itemsIndexed(items = selectedFiles, key = { _, uri -> uri.toString() }) { index, uri ->
            val info = resolveInfo(uri)
            Card(Modifier.fillMaxWidth().padding(4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(info.displayName, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (info.parentName != null) Text(info.parentName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { if (index > 0) onMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Default.ArrowUpward, "Up") }
                    IconButton(onClick = { if (index < selectedFiles.size - 1) onMove(index, index + 1) }, enabled = index < selectedFiles.size - 1) { Icon(Icons.Default.ArrowDownward, "Down") }
                    IconButton(onClick = { onRemove(uri) }) { Icon(Icons.Default.Close, "Remove", tint = Color.Red) }
                }
            }
        }
    }
}
