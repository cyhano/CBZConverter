package com.joshiminh.cbzconverter.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.joshiminh.cbzconverter.core.MainViewModel

@Composable
fun MihonScreen(activity: ComponentActivity, viewModel: MainViewModel) {
    val isConverting by viewModel.isCurrentlyConverting.collectAsState()
    val taskStatus by viewModel.currentTaskStatus.collectAsState()
    val subTaskStatus by viewModel.currentSubTaskStatus.collectAsState()
    val fileName by viewModel.selectedFileName.collectAsState()
    val fileUri by viewModel.selectedFileUri.collectAsState()
    val canMerge by viewModel.canMergeSelection.collectAsState()
    val batchSize by viewModel.batchSize.collectAsState()
    val pageWidth by viewModel.pageWidth.collectAsState()
    val overrideMerge by viewModel.overrideMergeFiles.collectAsState()
    val outUri by viewModel.overrideOutputDirectoryUri.collectAsState()
    val hasOut by viewModel.hasWritableOutputDirectory.collectAsState()
    val compress by viewModel.compressOutputPdf.collectAsState()
    val autoName by viewModel.autoNameWithChapters.collectAsState()

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { viewModel.updateSelectedFileUrisFromUserInput(it) }
    val dirPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let { viewModel.updateOverrideOutputPathFromUserInput(it) } }

    Scaffold { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            MihonMode(
                viewModel, activity, isConverting, fileName, fileUri, canMerge,
                taskStatus, subTaskStatus, batchSize, pageWidth, overrideMerge, outUri, hasOut,
                compress, autoName, filePicker, dirPicker
            ) { viewModel.checkPermissionAndSelectFileAction(activity, filePicker) }
        }
    }
}
