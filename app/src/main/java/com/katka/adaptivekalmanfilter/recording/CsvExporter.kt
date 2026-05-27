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

/**
 * Пишет список [ComparisonRow] в CSV и сохраняет в Downloads.
 *
 * Android 10+  — MediaStore (не нужен WRITE_EXTERNAL_STORAGE)
 * Android 9-   — прямая запись в Environment.DIRECTORY_DOWNLOADS
 *
 * Возвращает Uri файла, который можно передать в Intent.ACTION_SEND.
 */
object CsvExporter {

    private val HEADER = listOf(
        "step", "timestamp_ms", "dt_ms",
        "raw_lat", "raw_lon", "gps_accuracy_m", "gps_speed_ms",
        "class_lat", "class_lon", "class_vx", "class_vy",
        "class_kx", "class_ky", "class_rxx", "class_sigma_pos", "class_innov",
        "neural_lat", "neural_lon", "neural_vx", "neural_vy",
        "neural_kx", "neural_ky", "neural_rxx", "neural_sigma_pos", "neural_innov",
        "is_neural_active"
    ).joinToString(",")

    /**
     * Сохраняет CSV и возвращает Uri.
     * Вызывать из IO-диспетчера.
     */
    fun export(context: Context, rows: List<ComparisonRow>): Uri {
        val fileName = "kalman_comparison_${timestamp()}.csv"
        val csv      = buildCsv(rows)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportViaMediaStore(context, fileName, csv)
        } else {
            exportViaFile(context, fileName, csv)
        }
    }

    /** Запускает стандартный Android share sheet для файла. */
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

    // ── Builders ──────────────────────────────────────────────────────────────

    private fun buildCsv(rows: List<ComparisonRow>): String {
        val sb = StringBuilder()
        sb.appendLine(HEADER)
        for (r in rows) {
            sb.appendLine(buildString {
                append(r.stepIndex);       append(',')
                append(r.timestampMs);     append(',')
                append(f(r.dtMs));         append(',')
                append(f8(r.rawLat));      append(',')
                append(f8(r.rawLon));      append(',')
                append(f2(r.gpsAccuracyM.toDouble())); append(',')
                append(f2(r.gpsSpeedMs.toDouble()));    append(',')
                // Классика
                append(f8(r.classLat));    append(',')
                append(f8(r.classLon));    append(',')
                append(f4(r.classVx));     append(',')
                append(f4(r.classVy));     append(',')
                append(f5(r.classKx));     append(',')
                append(f5(r.classKy));     append(',')
                append(f3(r.classRxx));    append(',')
                append(f3(r.classSigmaPos)); append(',')
                append(f3(r.classInnov));  append(',')
                // Нейросеть
                append(f8(r.neuralLat));   append(',')
                append(f8(r.neuralLon));   append(',')
                append(f4(r.neuralVx));    append(',')
                append(f4(r.neuralVy));    append(',')
                append(f5(r.neuralKx));    append(',')
                append(f5(r.neuralKy));    append(',')
                append(f3(r.neuralRxx));   append(',')
                append(f3(r.neuralSigmaPos)); append(',')
                append(f3(r.neuralInnov)); append(',')
                append(if (r.isNeuralActive) "1" else "0")
            })
        }
        return sb.toString()
    }

    // ── Android 10+ ───────────────────────────────────────────────────────────

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

    // ── Android 9 и ниже ─────────────────────────────────────────────────────

    private fun exportViaFile(context: Context, fileName: String, csv: String): Uri {
        val dir  = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(csv)

        // FileProvider нужен чтобы другие приложения могли читать файл
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun OutputStream.write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    private fun timestamp() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun f(v: Double)  = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)
    private fun f3(v: Double) = "%.3f".format(v)
    private fun f4(v: Double) = "%.4f".format(v)
    private fun f5(v: Double) = "%.5f".format(v)
    private fun f8(v: Double) = "%.8f".format(v)
}