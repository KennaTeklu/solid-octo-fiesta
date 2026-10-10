package com.peakform.fitness

import android.app.Application
import android.util.Log

/**
 * ProLog — adb-only console pipeline (P1 fix, structural).
 * Three sinks, zero UI:
 *   1. logcat  — tag ProConsole (web-parity channel) / ProNative (engine channel)
 *   2. ring    — 500-entry in-memory buffer for crash reports
 *   3. file    — files/logs/pro.log (single rotating file, 512KB cap)
 */
object ProLog {
    const val TAG = "ProConsole"
    const val NATIVE_TAG = "ProNative"
    private const val RING_SIZE = 500
    private const val FILE_MAX = 512 * 1024

    private val ring = ArrayDeque<String>(RING_SIZE)
    @Volatile private var appContext: android.content.Context? = null
    @Volatile private var fileLines: Int = 0

    fun init(ctx: android.content.Context) {
        appContext = ctx.applicationContext
    }

    fun d(scope: String, msg: String) = log('D', scope, msg)
    fun i(scope: String, msg: String) = log('I', scope, msg)
    fun w(scope: String, msg: String) = log('W', scope, msg)
    fun e(scope: String, msg: String, tr: Throwable? = null) = log('E', scope, if (tr != null) "$msg :: ${tr.stackTraceToString().take(1200)}" else msg)

    private fun log(level: Char, scope: String, msg: String) {
        val line = "${ts()} $level/$scope: $msg"
        synchronized(ring) {
            if (ring.size >= RING_SIZE) ring.removeFirst()
            ring.addLast(line)
        }
        Log.println(levelToPriority(level), TAG, "[$scope] $msg")
        if (scope != "JS") Log.println(levelToPriority(level), NATIVE_TAG, "[$scope] $msg")
        appendFile(line)
    }

    private fun levelToPriority(l: Char) = when (l) {
        'E' -> Log.ERROR; 'W' -> Log.WARN; 'I' -> Log.INFO; else -> Log.DEBUG
    }

    private fun ts(): String {
        val c = java.util.Calendar.getInstance()
        return String.format("%02d:%02d:%02d.%03d", c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND), c.get(java.util.Calendar.MILLISECOND))
    }

    private fun appendFile(line: String) {
        val ctx = appContext ?: return
        try {
            val dir = java.io.File(ctx.filesDir, "logs").apply { mkdirs() }
            val f = java.io.File(dir, "pro.log")
            if (f.length() > FILE_MAX) f.delete()
            java.io.File(dir, "pro.log").appendText(line + "\n")
            fileLines++
        } catch (_: Exception) { }
    }

    fun dumpRing(): List<String> = synchronized(ring) { ring.toList() }

    /** Called from ProApp uncaught-exception hook so crashes leave a trace on device. */
    fun crash(thread: Thread, tr: Throwable) {
        e("CRASH", "uncaught on ${thread.name}: ${tr.javaClass.simpleName}: ${tr.message}", tr)
    }
}

class ProApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ProLog.init(this)
        com.peakform.fitness.ui.FontVault.init(this)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            ProLog.crash(t, e)
            prev?.uncaughtException(t, e)
        }
        ProLog.i("APP", "Pro native starting (pid=${android.os.Process.myPid()})")
        // N6: periodic reminder scan (retest / deload / period / backup) - survives reboot
        try {
            com.peakform.fitness.core.Notify.ensureChannels(this)
            com.peakform.fitness.core.Notify.schedule(this)
        } catch (e: Exception) {
            ProLog.w("APP", "notify schedule failed: " + (e.message ?: ""))
        }
    }
}
