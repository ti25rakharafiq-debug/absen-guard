package com.absenguard.app

import android.net.Uri
import android.widget.Button
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun MainActivity.createFullBackupButton(): Button {
    return Button(this).apply {
        text = "Backup Lengkap (Jadwal, Absen, PDF & Rekaman)"
        setOnClickListener {
            val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            backupPicker.launch("AbsenGuard-Backup-$stamp.absenguard")
        }
    }
}

fun MainActivity.createFullRestoreButton(): Button {
    return Button(this).apply {
        text = "Restore Lengkap (Pilih 1 File Backup)"
        setOnClickListener {
            restorePicker.launch(arrayOf("*/*"))
        }
    }
}

fun MainActivity.runFullExportBackup(uri: Uri) {
    Toast.makeText(this, "Membungkus seluruh data & PDF...", Toast.LENGTH_SHORT).show()
    Thread {
        val ok = FullBackupManager.exportFullBackup(this, db, uri)
        runOnUiThread {
            if (ok) {
                Toast.makeText(this, "Backup lengkap tersimpan dalam 1 file!", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Backup gagal", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}

fun MainActivity.runFullRestoreBackup(uri: Uri) {
    Toast.makeText(this, "Memulihkan data & seluruh file...", Toast.LENGTH_SHORT).show()
    Thread {
        val ok = FullBackupManager.restoreFullBackup(this, db, uri)
        runOnUiThread {
            if (ok) {
                Toast.makeText(this, "Seluruh jadwal, absen, PDF & rekaman berhasil dipulihkan!", Toast.LENGTH_LONG).show()
                refreshAll()
            } else {
                Toast.makeText(this, "File backup tidak valid atau rusak", Toast.LENGTH_SHORT).show()
            }
        }
    }.start()
}
