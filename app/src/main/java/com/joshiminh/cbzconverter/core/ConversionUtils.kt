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
private const val MAX_PDF_SIZE_BYTES = 260L * 1024 * 1024 // 260 MB target (leaves room for PDF overhead)
private const val CHECK_INTERVAL = 10 // Check file size every N images

fun convertCbzToPdf(
    fileUri: List<Uri>,
    contextHelper: ContextHelper,
    subStepAction: (String) -> Unit = {},
    batchSize: Int = 300,
    outputFileNames: List<String>,
    merge: Boolean = false,
    compress: Boolean = false,
    targetPageWidth: Float = 1200f,
    outputDir: DocumentFile
): List<DocumentFile> {
    if (fileUri.isEmpty()) return emptyList()
    val outputFiles = mutableListOf<DocumentFile>()
    contextHelper.getCacheDir().let { if (it.exists()) it.deleteRecursively(); it.mkdirs() }

    return if (merge) {
        mergeCbzAndProcess(contextHelper, fileUri, subStepAction, outputFileNames, outputFiles, outputDir, batchSize, compress, targetPageWidth)
    } else {
        processIndividualCbz(fileUri, outputFileNames, contextHelper, subStepAction, outputFiles, outputDir, batchSize, compress, targetPageWidth)
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
    compress: Boolean,
    targetPageWidth: Float
): List<DocumentFile> {
    fileUri.forEachIndexed { i, uri ->
        try {
            val temp = copyCbzToCache(contextHelper, subStepAction, uri)
            createPdf(temp, subStepAction, outputFileNames[i], outputDir, outputFiles, contextHelper, batchSize, compress, targetPageWidth)
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
    compress: Boolean,
    targetPageWidth: Float
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
    createPdf(combined, subStepAction, outputFileNames.first(), outputDir, outputFiles, contextHelper, batchSize, compress, targetPageWidth)
    return outputFiles
}

private fun createPdf(
    temp: File, subStepAction: (String) -> Unit, outputName: String, outputDir: DocumentFile,
    outputFiles: MutableList<DocumentFile>, contextHelper: ContextHelper,
    batchSize: Int, compress: Boolean, targetPageWidth: Float
) {
    try {
        ZipFile(temp).use { zip ->
            val total = zip.size()
            if (total == 0) return
            val entries = zip.entries().asSequence().sortedBy { it.name }.toList()

            var part = 0
            var currentEntries = mutableListOf<ZipEntry>()
            var tempFiles = mutableListOf<File>()
            var tempFilesSize = 0L

            for ((index, entry) in entries.withIndex()) {
                currentEntries.add(entry)
                subStepAction("Image ${index + 1}/$total")

                // Write in batches to manage memory
                if (currentEntries.size >= batchSize) {
                    val tempBatch = File(contextHelper.getCacheDir(), "temp_batch_${part}_${tempFiles.size}.pdf")
                    writePdf(currentEntries, tempBatch, zip, contextHelper, subStepAction, compress, targetPageWidth) { current ->
                        "Part ${part + 1} - Image ${index + 1 - currentEntries.size + current}/$total"
                    }
                    val batchSize = tempBatch.length()
                    tempFiles.add(tempBatch)
                    tempFilesSize += batchSize
                    currentEntries = mutableListOf()

                    // Check if accumulated temp files exceed size limit — split immediately
                    if (tempFilesSize >= MAX_PDF_SIZE_BYTES) {
                        val name = if (part == 0) outputName else outputName.replace(".pdf", "_part-${part + 1}.pdf")
                        val file = contextHelper.createDocumentFile(outputDir, name, "application/pdf")
                        mergePdf(file, tempFiles, compress, contextHelper)
                        outputFiles.add(file)
                        part++
                        tempFiles = mutableListOf()
                        tempFilesSize = 0L
                    }
                }
            }

            // Handle remaining entries
            if (currentEntries.isNotEmpty()) {
                val tempBatch = File(contextHelper.getCacheDir(), "temp_batch_${part}_final.pdf")
                writePdf(currentEntries, tempBatch, zip, contextHelper, subStepAction, compress, targetPageWidth) { current ->
                    "Part ${part + 1} - Image ${total - currentEntries.size + current}/$total"
                }
                tempFiles.add(tempBatch)
                tempFilesSize += tempBatch.length()
            }

            // Merge remaining temp files into final output
            if (tempFiles.isNotEmpty()) {
                // If remaining temp files still exceed limit, split them too
                if (tempFilesSize >= MAX_PDF_SIZE_BYTES && tempFiles.size > 1) {
                    // Split remaining files in half
                    val mid = tempFiles.size / 2
                    val firstHalf = tempFiles.subList(0, mid).toMutableList()
                    val secondHalf = tempFiles.subList(mid, tempFiles.size).toMutableList()

                    val name1 = if (part == 0) outputName else outputName.replace(".pdf", "_part-${part + 1}.pdf")
                    val file1 = contextHelper.createDocumentFile(outputDir, name1, "application/pdf")
                    mergePdf(file1, firstHalf, compress, contextHelper)
                    outputFiles.add(file1)
                    part++

                    val name2 = outputName.replace(".pdf", "_part-${part + 1}.pdf")
                    val file2 = contextHelper.createDocumentFile(outputDir, name2, "application/pdf")
                    mergePdf(file2, secondHalf, compress, contextHelper)
                    outputFiles.add(file2)
                } else {
                    val name = if (part == 0) outputName else outputName.replace(".pdf", "_part-${part + 1}.pdf")
                    val file = contextHelper.createDocumentFile(outputDir, name, "application/pdf")
                    mergePdf(file, tempFiles, compress, contextHelper)
                    outputFiles.add(file)
                }
            }
        }
    } finally { temp.delete() }
}

private fun writePdf(
    entries: List<ZipEntry>, out: File, zip: ZipFile, contextHelper: ContextHelper,
    subStepAction: (String) -> Unit, compress: Boolean, targetPageWidth: Float, msg: (Int) -> String
) {
    val props = if (compress) WriterProperties().setCompressionLevel(CompressionConstants.BEST_COMPRESSION) else null
    val writer = if (props != null) PdfWriter(out.absolutePath, props) else PdfWriter(out.absolutePath)
    PdfDocument(writer).use { pdfDoc ->
        Document(pdfDoc, PageSize.LETTER).use { doc ->
            doc.setMargins(0f, 0f, 0f, 0f)
            entries.forEachIndexed { i, entry ->
                subStepAction(msg(i + 1))
                ImageProcessor.extractImageAndAddToPDF(zip, entry, doc, contextHelper.getCacheDir(), subStepAction, compress, targetPageWidth)
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
