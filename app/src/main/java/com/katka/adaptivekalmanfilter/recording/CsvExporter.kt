package com.katka.adaptivekalmanfilter.recording

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.katka.model.ComparisonRow
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes [ComparisonRow]s to a CSV in Downloads (MediaStore on Android 10+, direct file otherwise). */
object CsvExporter {

    private val HEADER = listOf(
        "step", "timestamp_ms",
        "raw_lat", "raw_lon", "gps_accuracy_m", "gps_speed_ms",
        "kf_lat", "kf_lon", "kf_vx", "kf_vy", "kf_sigma_pos", "kf_innov",
        "sg_lat", "sg_lon",
        "smoothed_lat", "smoothed_lon", "alpha"
    ).joinToString(",")

    /** Writes the rows to a CSV and returns its Uri; call from an IO dispatcher. */
    fun export(context: Context, rows: List<ComparisonRow>): Uri {
        val fileName = "kalman_comparison_${timestamp()}.csv"
        val csv      = buildCsv(rows)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, fileName, csv)
        } else {
            exportViaFile(context, fileName, csv)
        }
    }

    /** Opens the Android share sheet for the file. */
    fun share(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type     = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Kalman Filter Comparison")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Поделиться CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }


    private fun buildCsv(rows: List<ComparisonRow>): String {
        val sb = StringBuilder()
        sb.appendLine(HEADER)
        for (r in rows) {
            sb.appendLine(buildString {
                append(r.stepIndex);       append(',')
                append(r.timestampMs);     append(',')
                append(f8(r.rawLat));      append(',')
                append(f8(r.rawLon));      append(',')
                append(f2(r.gpsAccuracyM.toDouble())); append(',')
                append(f2(r.gpsSpeedMs.toDouble()));    append(',')
                append(f8(r.kfLat));       append(',')
                append(f8(r.kfLon));       append(',')
                append(f4(r.kfVx));        append(',')
                append(f4(r.kfVy));        append(',')
                append(f3(r.kfSigmaPos));  append(',')
                append(f3(r.kfInnov));     append(',')
                append(f8(r.sgLat));       append(',')
                append(f8(r.sgLon));       append(',')
                append(f8(r.smoothedLat)); append(',')
                append(f8(r.smoothedLon)); append(',')
                append(f4(r.alpha))
            })
        }
        return sb.toString()
    }


    private fun exportViaMediaStore(context: Context, fileName: String, csv: String): Uri {
        val resolver = context.contentResolver
        val values   = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE,    "text/csv")
            put(MediaStore.Downloads.IS_PENDING,   1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore insert failed")

        resolver.openOutputStream(uri)!!.use { it.write(csv) }

        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        return uri
    }


    private fun exportViaFile(context: Context, fileName: String, csv: String): Uri {
        val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(csv)

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }


    private fun OutputStream.write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun timestamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun f2(v: Double) = "%.2f".format(v)
    private fun f3(v: Double) = "%.3f".format(v)
    private fun f4(v: Double) = "%.4f".format(v)
    private fun f8(v: Double) = "%.8f".format(v)
}
