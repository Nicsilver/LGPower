package com.nic.lgpower

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

object ContactMail {
    const val ADDRESS = "nic.silver@hotmail.dk"

    fun subject(context: Context): String =
        "${context.getString(R.string.app_name)} feedback (${BuildConfig.VERSION_NAME})"

    fun body(): String = buildString {
        append("\n\n\n")
        append("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n")
    }

    // Some mail apps drop EXTRA_SUBJECT/EXTRA_TEXT on a SENDTO intent and only read the URI,
    // others do the opposite, so both carry the content. Uri.encode gives %20, which every
    // mail app decodes; "+" would show up literally.
    fun uri(context: Context): Uri =
        Uri.parse("mailto:$ADDRESS?subject=${Uri.encode(subject(context))}&body=${Uri.encode(body())}")

    fun open(context: Context) {
        val intent = Intent(Intent.ACTION_SENDTO, uri(context))
            .putExtra(Intent.EXTRA_SUBJECT, subject(context))
            .putExtra(Intent.EXTRA_TEXT, body())
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Email address", ADDRESS))
            Toast.makeText(context, "No mail app found. Address copied: $ADDRESS", Toast.LENGTH_LONG).show()
        }
    }
}
