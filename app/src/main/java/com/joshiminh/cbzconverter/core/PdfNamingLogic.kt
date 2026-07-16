package com.joshiminh.cbzconverter.core

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class PdfNamingStrategy(
    private val context: Context,
    private val fileNameCache: MutableMap<Uri, String>,
    private val cbzParentName: MutableMap<Uri, String>,
    private val contextHelper: ContextHelper
) {

    fun getPdfFileNames(
        filesUri: List<Uri>,
        useParentDirectoryName: Boolean,
        overrideFileName: String,
        overrideMergeFiles: Boolean
    ): List<String> {
        val baseNames = filesUri.map { it.displayName() }
        val baseNamesNoExt = baseNames.map { it.substringBeforeLast('.', it) }

        // Resolve placeholders by falling back to file name itself
        val adjustedBaseNamesNoExt = baseNamesNoExt.toMutableList()
        if (!useParentDirectoryName) {
            filesUri.forEachIndexed { index, uri ->
                if (isPlaceholderName(adjustedBaseNamesNoExt[index])) {
                    adjustedBaseNamesNoExt[index] = "cbz_${index + 1}"
                }
            }

            return when {
                overrideFileName.isNotBlank() && overrideMergeFiles -> {
                    mutableListOf("${overrideFileName}.pdf").apply {
                        if (filesUri.size > 1) {
                            addAll(adjustedBaseNamesNoExt.drop(1).map { "$it.pdf" })
                        }
                    }
                }
                overrideFileName.isNotBlank() -> {
                    if (filesUri.size == 1) {
                        listOf("${overrideFileName}.pdf")
                    } else {
                        List(filesUri.size) { index -> "${overrideFileName}_${index + 1}.pdf" }
                    }
                }
                else -> {
                    adjustedBaseNamesNoExt.map { "$it.pdf" }
                }
            }
        }

        val mangaNames = filesUri.mapIndexed { index, uri ->
            cbzParentName[uri]
                ?: run {
                    val initialParent = DocumentFile.fromSingleUri(context, uri)?.parentFile?.name
                        ?: uri.pathSegments.dropLast(1).lastOrNull()
                    initialParent
                }
                ?: run {
                    val base = baseNamesNoExt[index]
                    if (!isPlaceholderName(base)) base else "Unknown"
                }
        }

        val chapters = List(baseNamesNoExt.size) { null }

        val defaultNames = filesUri.mapIndexed { index, _ ->
            val mangaName = mangaNames[index]
            val chapter = chapters[index]
            val suffix = when {
                chapter != null -> "_${chapter}"
                filesUri.size == 1 -> ""
                else -> "_${index + 1}"
            }
            "$mangaName$suffix.pdf"
        }.toMutableList().apply {
            if (overrideMergeFiles && filesUri.isNotEmpty()) {
                val base = mangaNames.first()
                this[0] = "$base.pdf"
            }
        }

        return when {
            overrideFileName.isNotBlank() && overrideMergeFiles -> {
                mutableListOf("${overrideFileName}.pdf").apply {
                    if (filesUri.size > 1) addAll(defaultNames.drop(1))
                }
            }
            overrideFileName.isNotBlank() -> {
                if (filesUri.size == 1) {
                    listOf("${overrideFileName}.pdf")
                } else {
                    List(filesUri.size) { index -> "${overrideFileName}_${index + 1}.pdf" }
                }
            }
            else -> defaultNames
        }
    }

    private fun isPlaceholderName(name: String): Boolean {
        var base = name.lowercase().substringBeforeLast('.')
        base = base
            .replace(Regex("\\s*\\(\\d+\\)$"), "")
            .replace(Regex("[-_\\s]*\\d+$"), "")
            .trim()
        val placeholders = listOf("unknown", "document", "file", "download", "content")
        return placeholders.contains(base)
    }

    fun resolveFileNameConflicts(names: List<String>, outputFolder: DocumentFile): List<String> {
        val existing = outputFolder.listFiles()
            .mapNotNull { it.name }
            .toMutableSet()
        return names.map { base ->
            var candidate = base
            val dotIndex = candidate.lastIndexOf('.')
            val namePart = if (dotIndex != -1) candidate.substring(0, dotIndex) else candidate
            val extension = if (dotIndex != -1) candidate.substring(dotIndex) else ""
            var version = 1
            while (existing.contains(candidate)) {
                candidate = "$namePart $version$extension"
                version++
            }
            existing.add(candidate)
            candidate
        }
    }

    private fun Uri.displayName(): String =
        fileNameCache[this] ?: contextHelper.getFileName(this).also { resolved ->
            fileNameCache[this] = resolved
        }
}