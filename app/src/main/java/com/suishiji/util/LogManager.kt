package com.suishiji.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object LogManager {
    private const val MAX_LOG_LINES = 500
    private var logFile: File? = null
    private var appContext: Context? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun init(context: Context) {
        appContext = context.applicationContext
        logFile = File(context.filesDir, "app_log.txt")
    }

    fun log(tag: String, msg: String) {
        val prefs = appContext?.getSharedPreferences("suishiji", Context.MODE_PRIVATE)
        if (prefs?.getBoolean("log_enabled", false) != true) return

        val time = dateFormat.format(Date())
        val line = "$time  $msg"

        logFile?.let { file ->
            try {
                val lines = if (file.exists()) {
                    file.readLines().toMutableList()
                } else {
                    mutableListOf()
                }
                lines.add(line)
                val excess = lines.size - MAX_LOG_LINES
                if (excess > 0) {
                    for (i in 0 until excess) {
                        lines.removeFirstOrNull()
                    }
                }
                file.writeText(lines.joinToString("\r\n") + "\r\n")
            } catch (_: Exception) {}
        }
    }

    fun getLogs(): String {
        return try {
            logFile?.readText() ?: "暂无日志"
        } catch (_: Exception) {
            "暂无日志"
        }
    }

    fun clearLogs() {
        logFile?.delete()
    }
}
