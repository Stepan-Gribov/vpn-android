package com.v2ray.ang.viewmodel

import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live view over this app's logcat. One `logcat -v time` process first dumps the existing
 * buffer and then follows new lines, which covers both the UI process and the
 * `:RunSoLibV2RayDaemon` core process (GoLog, crashes, System.err) without extra permissions.
 */
class LogcatViewModel : ViewModel() {

    data class LogLine(
        val raw: String,
        val time: String,
        val priority: Int,
        val tag: String,
        val message: String,
    )

    companion object {
        const val PRIORITY_ALL = 0

        private const val MAX_LINES = 4000
        private const val FLUSH_INTERVAL_MS = 300L
        private val LINE_REGEX =
            Regex("""^(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d+)\s+([VDIWEF])/(.*?)\(\s*\d+\):\s?(.*)$""")
    }

    /** Fired (throttled) whenever the visible log content may have changed. */
    val updateAction by lazy { MutableLiveData<Unit>() }

    private val allLogs = ArrayDeque<LogLine>()

    @Volatile
    private var keyword = ""

    @Volatile
    private var minPriority = PRIORITY_ALL

    private var tailJob: Job? = null

    @Volatile
    private var tailProcess: Process? = null
    private val dirty = AtomicBoolean(false)

    @Synchronized
    fun visibleLines(): List<LogLine> = allLogs.filter { matches(it) }

    private fun matches(line: LogLine): Boolean =
        line.priority >= minPriority &&
                (keyword.isEmpty() || line.raw.contains(keyword, ignoreCase = true))

    fun setMinPriority(priority: Int) {
        minPriority = priority
        updateAction.value = Unit
    }

    fun setKeyword(value: String?) {
        keyword = value?.trim().orEmpty()
        updateAction.value = Unit
    }

    fun startTail() {
        if (tailJob?.isActive == true) return

        tailJob = viewModelScope.launch(Dispatchers.IO) {
            val flusher = launch {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    if (dirty.getAndSet(false)) {
                        updateAction.postValue(Unit)
                    }
                }
            }

            try {
                val process = Runtime.getRuntime().exec(arrayOf("logcat", "-v", "time"))
                tailProcess = process
                process.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                    }
                }
            } catch (e: IOException) {
                // Expected when stopTail() destroys the process mid-read.
                if (isActive) {
                    LogUtil.e(AppConfig.TAG, "Logcat tail failed", e)
                }
            } finally {
                flusher.cancel()
            }
        }
    }

    fun stopTail() {
        // Cancel first so the reader sees an inactive job when destroy() unblocks readLine;
        // the reverse order logs a spurious IOException on every exit from the screen.
        tailJob?.cancel()
        tailJob = null
        tailProcess?.destroy()
        tailProcess = null
    }

    fun clear() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to clear logcat", e)
            }
            synchronized(this@LogcatViewModel) { allLogs.clear() }
            updateAction.postValue(Unit)
        }
    }

    private fun appendLine(rawLine: String) {
        if (rawLine.isBlank() || rawLine.startsWith("--------- beginning of")) {
            return
        }

        val match = LINE_REGEX.matchEntire(rawLine)
        val line = if (match != null) {
            val (time, level, tag, message) = match.destructured
            LogLine(rawLine, time, priorityOf(level.first()), tag.trim(), message)
        } else {
            LogLine(rawLine, "", Log.INFO, "", rawLine)
        }

        synchronized(this) {
            allLogs.addLast(line)
            while (allLogs.size > MAX_LINES) {
                allLogs.removeFirst()
            }
        }
        dirty.set(true)
    }

    private fun priorityOf(level: Char): Int = when (level) {
        'V' -> Log.VERBOSE
        'D' -> Log.DEBUG
        'I' -> Log.INFO
        'W' -> Log.WARN
        'E' -> Log.ERROR
        'F' -> Log.ASSERT
        else -> Log.INFO
    }

    override fun onCleared() {
        stopTail()
        super.onCleared()
    }
}
