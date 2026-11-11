package com.monochrome.annas.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.monochrome.annas.data.LibraryItem

fun openItem(context: Context, item: LibraryItem) {
    try {
        val uri: Uri = if (item.uri != null) Uri.parse(item.uri) else {
            val file = java.io.File(item.localPath ?: return)
            FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, item.mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}
