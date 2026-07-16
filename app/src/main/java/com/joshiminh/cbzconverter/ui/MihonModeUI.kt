package com.joshiminh.cbzconverter.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joshiminh.cbzconverter.R
import com.joshiminh.cbzconverter.core.MainViewModel

@Composable
fun MihonMode(
    viewModel: MainViewModel, activity: ComponentActivity, isCurrentlyConverting: Boolean,
    selectedFileName: String, selectedFilesUri: List<Uri>, canMergeSelection: Boolean,
    currentTaskStatus: String, currentSubTaskStatus: String,
    batchSize: Int, pageWidth: Float, overrideMerge: Boolean, overrideOutUri: Uri?,
    hasOut: Boolean, compress: Boolean, autoName: Boolean,
    fileLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
    dirLauncher: ManagedActivityResultLauncher<Uri?, Uri?>,
    onSelectFiles: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    Column(Modifier.fillMaxSize().padding(8.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        SectionCard(
            title = "CBZ Selection",
            iconResId = R.drawable.cbz,
            action = {
                IconButton(onClick = { viewModel.updateSelectedFileUrisFromUserInput(emptyList()) }) { Icon(Icons.Filled.Delete, "Clear") }
            }
        ) {
            ManualSelectionCard(selectedFileName, selectedFilesUri, isCurrentlyConverting, onSelectFiles)
        }

        Spacer(Modifier.height(8.dp))
        SectionCard("Selected File(s)") {
            if (selectedFilesUri.isEmpty()) Card(Modifier.fillMaxWidth()) { Text("None", Modifier.padding(12.dp)) }
            else Card(Modifier.fillMaxWidth()) { SelectedFilesList(selectedFilesUri, viewModel::getSelectedFileInfo, { f, t -> viewModel.moveSelectedFile(f, t) }, { viewModel.setFileSelection(it, false) }) }
        }

        Spacer(Modifier.height(8.dp))
        ElevatedCard(Modifier.fillMaxWidth()) {
            var exp by rememberSaveable { mutableStateOf(true) }
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Configurations", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { exp = !exp }) { Icon(if (exp) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, "Exp") }
                }
                AnimatedVisibility(exp) {
                    Column {
                        ConfigNumberItem("Batch Size", "Memory batch size", batchSize.toString(), !isCurrentlyConverting) { viewModel.updateBatchSizeFromUserInput(it); focusManager.clearFocus() }
                        Spacer12Divider()
                        ConfigNumberItem("Page Width", "Target page width (pt)", pageWidth.toInt().toString(), !isCurrentlyConverting) { viewModel.updatePageWidthFromUserInput(it); focusManager.clearFocus() }
                        ConfigSwitchItem("Merge All", "Combine selected CBZs", overrideMerge, !isCurrentlyConverting) { viewModel.toggleMergeFilesOverride(it) }
                        Spacer12Divider()
                        ConfigSwitchItem("Compress", "Reduce file size", compress, !isCurrentlyConverting) { viewModel.toggleCompressOutputPdf(it) }
                        Spacer12Divider()
                        ConfigSwitchItem("Autonaming", "Use chapter numbers", autoName, !isCurrentlyConverting) { viewModel.toggleAutoNameWithChapters(it) }
                        Spacer12Divider()
                        ConfigButtonItem("Output Folder", "Where to save PDFs", overrideOutUri?.toString() ?: "Not set", "Select Folder", !isCurrentlyConverting) { viewModel.checkPermissionAndSelectDirectoryAction(activity, dirLauncher) }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        SectionCard("Status") {
            val color = when {
                currentTaskStatus.contains("Completed") -> Color(0xFF4CAF50)
                currentTaskStatus.contains("Failed") -> Color(0xFFF44336)
                else -> Color.Unspecified
            }
            Text("Progress: $currentTaskStatus", fontWeight = FontWeight.SemiBold, color = color)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.height(130.dp)) { items(currentSubTaskStatus.lines()) { Text(it) } }
        }

        Spacer(Modifier.height(12.dp))
        Button(onClick = { if (selectedFilesUri.isNotEmpty()) viewModel.convertToPDF(selectedFilesUri) }, enabled = selectedFilesUri.isNotEmpty() && !isCurrentlyConverting && hasOut, modifier = Modifier.fillMaxWidth()) { Text("Export") }
    }
}
