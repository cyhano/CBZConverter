package com.joshiminh.cbzconverter.core

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val COMBINED_TEMP_EPUB = "combined_temp_epub.cbz"
private const val MAX_EPUB_SIZE_BYTES = 280L * 1024 * 1024 // 280 MB
private const val IMAGES_DIR = "OEBPS/images"
private const val XHTML_DIR = "OEBPS/xhtml"

fun convertCbzToEpub(
    fileUri: List<Uri>,
    contextHelper: ContextHelper,
    subStepAction: (String) -> Unit = {},
    outputFileNames: List<String>,
    merge: Boolean = false,
    outputDir: DocumentFile
): List<DocumentFile> {
    if (fileUri.isEmpty()) return emptyList()
    val outputFiles = mutableListOf<DocumentFile>()
    contextHelper.getCacheDir().let { if (it.exists()) it.deleteRecursively(); it.mkdirs() }

    return if (merge) {
        mergeCbzAndProcessEpub(contextHelper, fileUri, subStepAction, outputFileNames, outputFiles, outputDir)
    } else {
        processIndividualCbzEpub(fileUri, outputFileNames, contextHelper, subStepAction, outputFiles, outputDir)
    }
}

private fun processIndividualCbzEpub(
    fileUri: List<Uri>,
    outputFileNames: List<String>,
    contextHelper: ContextHelper,
    subStepAction: (String) -> Unit,
    outputFiles: MutableList<DocumentFile>,
    outputDir: DocumentFile
): List<DocumentFile> {
    fileUri.forEachIndexed { i, uri ->
        try {
            val temp = copyCbzToCacheEpub(contextHelper, subStepAction, uri)
            val epubName = outputFileNames[i].replace(".pdf", ".epub")
            createEpub(temp, subStepAction, epubName, outputDir, outputFiles, contextHelper)
        } catch (_: IOException) {}
    }
    return outputFiles
}

private fun mergeCbzAndProcessEpub(
    contextHelper: ContextHelper,
    fileUri: List<Uri>,
    subStepAction: (String) -> Unit,
    outputFileNames: List<String>,
    outputFiles: MutableList<DocumentFile>,
    outputDir: DocumentFile
): List<DocumentFile> {
    subStepAction("Merging files in Cache...")
    val combined = File(contextHelper.getCacheDir(), COMBINED_TEMP_EPUB)
    ZipOutputStream(combined.outputStream()).use { out ->
        fileUri.forEachIndexed { i, uri ->
            try {
                val temp = copyCbzToCacheEpub(contextHelper, subStepAction, uri)
                ZipFile(temp).use { zip ->
                    zip.entries().asSequence().forEach { entry ->
                        out.putNextEntry(ZipEntry("${i.toString().padStart(9, '0')}_${entry.name}"))
                        zip.getInputStream(entry).use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                temp.delete()
            } catch (_: IOException) {}
        }
    }
    val epubName = outputFileNames.first().replace(".pdf", ".epub")
    createEpub(combined, subStepAction, epubName, outputDir, outputFiles, contextHelper)
    return outputFiles
}

private fun createEpub(
    temp: File, subStepAction: (String) -> Unit, outputName: String,
    outputDir: DocumentFile, outputFiles: MutableList<DocumentFile>, contextHelper: ContextHelper
) {
    try {
        ZipFile(temp).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { it.name.lowercase().endsWith(".jpg") || it.name.lowercase().endsWith(".jpeg") || it.name.lowercase().endsWith(".png") || it.name.lowercase().endsWith(".webp") || it.name.lowercase().endsWith(".gif") || it.name.lowercase().endsWith(".bmp") }
                .sortedBy { it.name }
                .toList()
            if (entries.isEmpty()) return

            val total = entries.size
            val bookTitle = outputName.replace(".epub", "")

            // Check if we need to split by size
            val estimatedSize = entries.sumOf { it.size.coerceAtLeast(0) }
            if (estimatedSize > MAX_EPUB_SIZE_BYTES) {
                // Split into parts
                var part = 0
                var currentEntries = mutableListOf<ZipEntry>()
                var currentSize = 0L

                for ((index, entry) in entries.withIndex()) {
                    currentEntries.add(entry)
                    currentSize += entry.size.coerceAtLeast(0)
                    subStepAction("Image ${index + 1}/$total")

                    if (currentSize >= MAX_EPUB_SIZE_BYTES || index == total - 1) {
                        val partName = if (part == 0) outputName else outputName.replace(".epub", "_part-${part + 1}.epub")
                        val file = contextHelper.createDocumentFile(outputDir, partName, "application/epub+zip")
                        writeEpub(file, contextHelper, currentEntries, zip, bookTitle + if (part > 0) " Part ${part + 1}" else "", subStepAction, part + 1, total)
                        outputFiles.add(file)
                        part++
                        currentEntries = mutableListOf()
                        currentSize = 0L
                    }
                }
            } else {
                val file = contextHelper.createDocumentFile(outputDir, outputName, "application/epub+zip")
                writeEpub(file, contextHelper, entries, zip, bookTitle, subStepAction, 1, total)
                outputFiles.add(file)
            }
        }
    } finally { temp.delete() }
}

private fun writeEpub(
    outputFile: DocumentFile,
    contextHelper: ContextHelper,
    entries: List<ZipEntry>,
    zip: ZipFile,
    bookTitle: String,
    subStepAction: (String) -> Unit,
    partNum: Int,
    totalImages: Int
) {
    val stream = contextHelper.openOutputStream(outputFile.uri) ?: throw IOException("Stream error")
    ZipOutputStream(stream).use { out ->
        // 1. mimetype (must be first, uncompressed)
        val mimeEntry = ZipEntry("mimetype")
        mimeEntry.method = ZipEntry.STORED
        val mimeBytes = "application/epub+zip".toByteArray()
        mimeEntry.size = mimeBytes.size.toLong()
        mimeEntry.crc = java.util.zip.CRC32().also { it.update(mimeBytes) }.value
        out.putNextEntry(mimeEntry)
        out.write(mimeBytes)
        out.closeEntry()

        // 2. META-INF/container.xml
        out.putNextEntry(ZipEntry("META-INF/container.xml"))
        out.write(containerXml.toByteArray())
        out.closeEntry()

        // 3. OEBPS/content.opf
        val imageList = entries.mapIndexed { i, _ -> "image_${(i + 1).toString().padStart(4, '0')}" }
        out.putNextEntry(ZipEntry("OEBPS/content.opf"))
        out.write(contentOpf(bookTitle, imageList).toByteArray())
        out.closeEntry()

        // 4. OEBPS/toc.ncx
        out.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
        out.write(tocNcx(bookTitle, imageList).toByteArray())
        out.closeEntry()

        // 5. XHTML pages + images
        entries.forEachIndexed { i, entry ->
            val imageRef = imageList[i]
            val ext = entry.name.substringAfterLast('.', "jpg").lowercase()
            val imageName = "$imageRef.$ext"
            val xhtmlName = "$imageRef.xhtml"

            subStepAction("Part $partNum - Image ${i + 1}/${entries.size}")

            // Copy image
            out.putNextEntry(ZipEntry("$IMAGES_DIR/$imageName"))
            zip.getInputStream(entry).use { it.copyTo(out) }
            out.closeEntry()

            // Write XHTML page
            out.putNextEntry(ZipEntry("$XHTML_DIR/$xhtmlName"))
            out.write(imageXhtml(imageName, ext).toByteArray())
            out.closeEntry()
        }
    }
}

private fun copyCbzToCacheEpub(contextHelper: ContextHelper, subStepAction: (String) -> Unit, uri: Uri): File {
    val temp = File(contextHelper.getCacheDir(), "temp_epub.cbz")
    val input = contextHelper.openInputStream(uri) ?: throw IOException("Copy error")
    input.use { s -> temp.outputStream().use { o -> s.copyTo(o) } }
    return temp
}

private const val containerXml = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

private fun contentOpf(bookTitle: String, images: List<String>): String {
    val manifest = StringBuilder()
    val spine = StringBuilder()
    images.forEach { img ->
        manifest.append("    <item id=\"${img}_page\" href=\"xhtml/${img}.xhtml\" media-type=\"application/xhtml+xml\"/>\n")
        manifest.append("    <item id=\"${img}_img\" href=\"images/${img}.jpg\" media-type=\"image/jpeg\"/>\n")
        spine.append("    <itemref idref=\"${img}_page\"/>\n")
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>$bookTitle</dc:title>
    <dc:language>en</dc:language>
    <dc:identifier id="bookid">cbzconverter-${System.currentTimeMillis()}</dc:identifier>
  </metadata>
  <manifest>
$manifest  </manifest>
  <spine>
$spine  </spine>
</package>"""
}

private fun tocNcx(bookTitle: String, images: List<String>): String {
    val navPoints = StringBuilder()
    images.forEachIndexed { i, img ->
        navPoints.append("    <navPoint id=\"${img}_page\" playOrder=\"${i + 1}\">\n")
        navPoints.append("      <navLabel><text>Page ${i + 1}</text></navLabel>\n")
        navPoints.append("      <content src=\"xhtml/${img}.xhtml\"/>\n")
        navPoints.append("    </navPoint>\n")
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="cbzconverter"/>
  </head>
  <docTitle><text>$bookTitle</text></docTitle>
  <navMap>
$navPoints  </navMap>
</ncx>"""
}

private fun imageXhtml(imageName: String, ext: String): String {
    val mime = when (ext) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
    return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>Page</title>
  <style type="text/css">html,body{margin:0;padding:0;width:1200px;max-width:100%;margin:auto;} img{width:100%;display:block;}</style>
</head>
<body>
  <div><img src="../images/$imageName" alt="page" /></div>
</body>
</html>"""
}
