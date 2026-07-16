package com.joshiminh.cbzconverter.core

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.CompressionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import com.itextpdf.kernel.utils.PdfMerger
import com.itextpdf.layout.Document
import java.io.File
import java.io.IOException
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.ceil

private val logger = Logger.getLogger("com.joshiminh.cbzconverter.core.ConversionUtils")
private const val COMBINED_TEMP = "combined_temp.cbz"
private const val MAX_PDF_SIZE_BYTES = 280L * 1024 * 1024 // 280 MB
private const val CHECK_INTERVAL = 20 // Check file size every N images

fun convertCbzToPdf(
    fileUri: List<Uri>,
    contextHelper: ContextHelper,
    subStepAction: (String) -> Unit = {},
    batchSize: Int = 300,
    outputFileNames: List<String>,
    merge: Boolean = false,
    compress: Boolean = false,
    outputDir: DocumentFile
): List<DocumentFile> {
    if (fileUri.isEmpty()) return emptyList()
    val outputFiles = mutableListOf<DocumentFile>()
    contextHelper.getCacheDir().let { if (it.exists()) it.deleteRecursively(); it.mkdirs() }

    return if (merge) {
        mergeCbzAndProcess(contextHelper, fileUri, subStepAction, outputFileNames, outputFiles, outputDir, batchSize, compress)
    } else {
        processIndividualCbz(fileUri, outputFileNames, contextHelper, subStepAction, outputFiles, outputDir, batchSize, compress)
    }
}

private fun processIndividualCbz(
    fileUri: List<Uri>,
    outputFileNames: List<String>,
    contextHelper: ContextHelper,
    subStepAction: (String) -> Unit,
    outputFiles: MutableList<DocumentFile>,
    outputDir: DocumentFile,
    batchSize: Int,
    compress: Boolean
): List<DocumentFile> {
    fileUri.forEachIndexed { i, uri ->
        try {
            val temp = copyCbzToCache(contextHelper, subStepAction, uri)
            createPdf(temp, subStepAction, outputFileNames[i], outputDir, outputFiles, contextHelper, batchSize, compress)
        } catch (_: IOException) {}
    }
    return outputFiles
}

private fun mergeCbzAndProcess(
    contextHelper: ContextHelper,
    fileUri: List<Uri>,
    subStepAction: (String) -> Unit,
    outputFileNames: List<String>,
    outputFiles: MutableList<DocumentFile>,
    outputDir: DocumentFile,
    batchSize: Int,
    compress: Boolean
): List<DocumentFile> {
    subStepAction("Merging files in Cache...")
    val combined = File(contextHelper.getCacheDir(), COMBINED_TEMP)
    ZipOutputStream(combined.outputStream()).use { out ->
        fileUri.forEachIndexed { i, uri ->
            try {
                val temp = copyCbzToCache(contextHelper, subStepAction, uri)
                ZipFile(temp).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        out.putNextEntry(ZipEntry("${i.toString().padStart(9, '0')}_${outputFileNames[i]}_${entry.name}"))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                temp.delete()
            } catch (_: IOException) {}
        }
    }
    createPdf(combined, subStepAction, outputFileNames.first(), outputDir, outputFiles, contextHelper, batchSize, compress)
    return outputFiles
}

private fun createPdf(
    temp: File, subStepAction: (String) -> Unit, outputName: String, outputDir: DocumentFile,
    outputFiles: MutableList<DocumentFile>, contextHelper: ContextHelper,
    batchSize: Int, compress: Boolean
) {
    try {
        ZipFile(temp).use { zip ->
            val total = zip.size()
            if (total == 0) return
            val entries = zip.entries().asSequence().sortedBy { it.name }.toList()

            var part = 0
            var currentEntries = mutableListOf<ZipEntry>()
            var tempFiles = mutableListOf<File>()

            for ((index, entry) in entries.withIndex()) {
                currentEntries.add(entry)
                subStepAction("Image ${index + 1}/$total")

                // Periodically check if the current batch temp file exceeds size limit
                if (currentEntries.size % CHECK_INTERVAL == 0 && tempFiles.isNotEmpty()) {
                    val lastTemp = tempFiles.last()
                    if (lastTemp.length() >= MAX_PDF_SIZE_BYTES) {
                        // Finalize current part
                        val name = if (part == 0) outputName else outputName.replace(".pdf", "_part-${part + 1}.pdf")
                        val file = contextHelper.createDocumentFile(outputDir, name, "application/pdf")
                        mergePdf(file, tempFiles, compress, contextHelper)
                        outputFiles.add(file)
                        part++
                        tempFiles = mutableListOf()
                        currentEntries = mutableListOf()
                    }
                }

                // Write in batches to manage memory
                if (currentEntries.size >= batchSize) {
                    val tempBatch = File(contextHelper.getCacheDir(), "temp_batch_${part}_${tempFiles.size}.pdf")
                    writePdf(currentEntries, tempBatch, zip, contextHelper, subStepAction, compress) { current ->
                        "Part ${part + 1} - Image ${index + 1 - currentEntries.size + current}/$total"
                    }
                    tempFiles.add(tempBatch)
                    currentEntries = mutableListOf()

                    // Check if accumulated temp files exceed size limit
                    val totalSize = tempFiles.sumOf { it.length() }
                    if (totalSize >= MAX_PDF_SIZE_BYTES) {
                        val name = if (part == 0) outputName else outputName.replace(".pdf", "_part-${part + 1}.pdf")
                        val file = contextHelper.createDocumentFile(outputDir, name, "application/pdf")
                        mergePdf(file, tempFiles, compress, contextHelper)
                        outputFiles.add(file)
                        part++
                        tempFiles = mutableListOf()
                    }
                }
            }

            // Handle remaining entries
            if (currentEntries.isNotEmpty()) {
                val tempBatch = File(contextHelper.getCacheDir(), "temp_batch_${part}_final.pdf")
                writePdf(currentEntries, tempBatch, zip, contextHelper, subStepAction, compress) { current ->
                    "Part ${part + 1} - Image ${total - currentEntries.size + current}/$total"
                }
                tempFiles.add(tempBatch)
            }

            // Merge remaining temp files into final output
            if (tempFiles.isNotEmpty()) {
                val name = if (part == 0) outputName else outputName.replace(".pdf", "_part-${part + 1}.pdf")
                val file = contextHelper.createDocumentFile(outputDir, name, "application/pdf")
                mergePdf(file, tempFiles, compress, contextHelper)
                outputFiles.add(file)
            }
        }
    } finally { temp.delete() }
}

private fun writePdf(
    entries: List<ZipEntry>, out: File, zip: ZipFile, contextHelper: ContextHelper,
    subStepAction: (String) -> Unit, compress: Boolean, msg: (Int) -> String
) {
    val props = if (compress) WriterProperties().setCompressionLevel(CompressionConstants.BEST_COMPRESSION) else null
    val writer = if (props != null) PdfWriter(out.absolutePath, props) else PdfWriter(out.absolutePath)
    PdfDocument(writer).use { pdfDoc ->
        Document(pdfDoc, PageSize.LETTER).use { doc ->
            doc.setMargins(0f, 0f, 0f, 0f)
            entries.forEachIndexed { i, entry ->
                subStepAction(msg(i + 1))
                ImageProcessor.extractImageAndAddToPDF(zip, entry, doc, contextHelper.getCacheDir(), subStepAction, compress)
            }
        }
    }
}

private fun mergePdf(target: DocumentFile, files: MutableList<File>, compress: Boolean, contextHelper: ContextHelper) {
    val stream = contextHelper.openOutputStream(target.uri) ?: throw IOException("Stream error")
    val props = if (compress) WriterProperties().setCompressionLevel(CompressionConstants.BEST_COMPRESSION) else null
    val writer = if (props != null) PdfWriter(stream, props) else PdfWriter(stream)
    PdfDocument(writer).use { final ->
        val merger = PdfMerger(final)
        files.forEach { file ->
            PdfDocument(PdfReader(file)).use { doc ->
                merger.merge(doc, 1, doc.numberOfPages)
                final.flushCopiedObjects(doc)
            }
            file.delete()
        }
        merger.close()
    }
    files.clear()
}

private fun copyCbzToCache(contextHelper: ContextHelper, subStepAction: (String) -> Unit, uri: Uri): File {
    val temp = File(contextHelper.getCacheDir(), "temp.cbz")
    val input = contextHelper.openInputStream(uri) ?: throw IOException("Copy error")
    input.use { s -> temp.outputStream().use { o -> s.copyTo(o) } }
    return temp
}
