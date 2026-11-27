package com.absenguard.app

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object FullBackupManager {

    /**
     * Membungkus database JSON + seluruh folder fisik (PDF, rekaman suara, foto papan tulis)
     * menjadi 1 file bundel tunggal tanpa mencar-mencar.
     */
    fun exportFullBackup(context: Context, db: ScheduleDbHelper, targetUri: Uri): Boolean {
        return try {
            val outStream = context.contentResolver.openOutputStream(targetUri) ?: return false
            ZipOutputStream(BufferedOutputStream(outStream)).use { zos ->
                // 1. Masukkan seluruh data database (jadwal, absen, pengaturan, catatan) sebagai database.json
                val jsonBytes = db.exportBackupJson().toByteArray(Charsets.UTF_8)
                zos.putNextEntry(ZipEntry("database.json"))
                zos.write(jsonBytes)
                zos.closeEntry()

                // 2. Masukkan seluruh file fisik di internal storage aplikasi (PDF, audio, foto papan tulis)
                val filesRoot = context.filesDir
                if (filesRoot.exists()) {
                    addDirectoryToZip(filesRoot, filesRoot, zos)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Langsung membaca 1 file bundel backup (atau file .json lama) dan mengembalikan
     * seluruh database serta file PDF/rekaman/foto langsung ke penyimpanan privat aplikasi.
     */
    fun restoreFullBackup(context: Context, db: ScheduleDbHelper, sourceUri: Uri): Boolean {
        return try {
            val rawBytes = context.contentResolver.openInputStream(sourceUri)?.use { it.readBytes() }
                ?: return false
            if (rawBytes.isEmpty()) return false

            // Cek apakah file berupa bundel arsip (header ZIP: 'P','K', 0x03, 0x04)
            val isZipBundle = rawBytes.size >= 4 &&
                rawBytes[0] == 0x50.toByte() &&
                rawBytes[1] == 0x4B.toByte()

            if (!isZipBundle) {
                // Tetap dukung file .json backup versi lama
                val jsonText = rawBytes.toString(Charsets.UTF_8)
                return db.importBackupJson(jsonText)
            }

            var jsonRestored = false
            val filesRoot = context.filesDir
            val canonicalRootPath = filesRoot.canonicalPath + File.separator

            ZipInputStream(BufferedInputStream(rawBytes.inputStream())).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    if (!entry.isDirectory) {
                        if (entryName == "database.json") {
                            val jsonStr = zis.readBytes().toString(Charsets.UTF_8)
                            jsonRestored = db.importBackupJson(jsonStr)
                        } else if (entryName.startsWith("files/")) {
                            val relativePath = entryName.removePrefix("files/")
                            val outFile = File(filesRoot, relativePath)
                            // Proteksi keamanan path traversal
                            if (outFile.canonicalPath.startsWith(canonicalRootPath)) {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
            jsonRestored
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun addDirectoryToZip(rootDir: File, currentDir: File, zos: ZipOutputStream) {
        val children = currentDir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                addDirectoryToZip(rootDir, child, zos)
            } else if (child.isFile) {
                val relative = child.relativeTo(rootDir).path.replace("\\", "/")
                // Abaikan file database SQLite mentah karena sudah diekspor bersih via database.json
                if (relative.endsWith(".db") || relative.endsWith(".db-journal") || relative.endsWith(".db-wal") || relative.endsWith(".db-shm")) {
                    continue
                }
                zos.putNextEntry(ZipEntry("files/$relative"))
                FileInputStream(child).use { fis ->
                    BufferedInputStream(fis).copyTo(zos)
                }
                zos.closeEntry()
            }
        }
    }
}
