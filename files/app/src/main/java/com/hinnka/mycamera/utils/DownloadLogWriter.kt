package com.hinnka.mycamera.utils

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.util.Log
import com.hinnka.mycamera.MyCameraApplication
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 默认将应用日志写入公共 Download 目录（无需任何存储权限，Android 11+ File API 直写）：
 *
 *   /storage/emulated/0/Download/MyCamera/logs/
 *     app_<启动时间>.txt      应用自身日志（PLog 全量镜像，含启动分段追踪）
 *     logcat_<启动时间>.txt   本进程 logcat 镜像（含系统打印的崩溃堆栈/native 信号）
 *     crash_<崩溃时间>.txt    未捕获崩溃完整快照（堆栈+设备信息+最近应用日志）
 *
 * 同时在应用外部私有目录保留一份兜底副本；Download 不可用时自动降级到私有目录。
 * 单次会话日志超过保留上限时自动清理最旧的会话文件（崩溃文件始终保留）。
 */
object DownloadLogWriter {
    private const val TAG = "DownloadLogWriter"

    /** 总开关：默认开启。 */
    const val ENABLED = true

    private const val FOLDER_NAME = "MyCamera"
    private const val MAX_SESSION_FILES = 10
    private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024

    private val started = AtomicBoolean(false)

    /** 单线程顺序写，避免并发 IO；低优先级守护线程，不影响相机性能。 */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "download-log-io").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    private var primaryDir: File? = null   // Download/MyCamera/logs
    private var fallbackDir: File? = null   // Android/data/<pkg>/files/logs
    private var appWriter: FileWriter? = null
    private var logcatWriter: FileWriter? = null
    private var sessionStamp: String = ""

    @Volatile
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private val crashHandler = object : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                writeCrashSnapshot(thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "write crash snapshot failed", t)
            }
            // 给 logcat 镜像线程一点时间，把崩溃前的系统日志刷进镜像文件
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {
            }
            val prev = previousHandler
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    // ------------------------------------------------------------------ install

    /**
     * 尽早调用（推荐 attachBaseContext）：创建目录、写会话头、启动 logcat 镜像线程。
     * logcat 以 dump+follow 模式运行，线程启动前的缓冲区日志也会补齐。
     */
    fun install(context: Context) {
        if (!ENABLED || !started.compareAndSet(false, true)) return
        try {
            val download = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val dir = File(download, "$FOLDER_NAME/logs")
            if (dir.isDirectory || dir.mkdirs()) {
                primaryDir = dir
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Download dir unavailable, will use fallback", t)
        }
        try {
            fallbackDir = File(context.getExternalFilesDir(null), "logs").apply { mkdirs() }
        } catch (t: Throwable) {
            Log.w(TAG, "Fallback dir unavailable", t)
        }

        sessionStamp = fileStamp()
        val header = buildString {
            appendLine("=== MyCamera session $sessionStamp ===")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("version=${resolveVersionLabel()}")
            appendLine("pageSize=${resolvePageSize()}")
            appendLine("pid=${Process.myPid()} uid=${Process.myUid()}")
            appendLine()
        }
        tryOpenWriter("app")?.let { w ->
            appWriter = w
            w.write(header)
            w.flush()
        }
        tryOpenWriter("logcat")?.let { w ->
            logcatWriter = w
            w.write(header)
            w.flush()
        }
        Log.i(TAG, "Logging to Download enabled: primary=${primaryDir?.path} fallback=${fallbackDir?.path}")

        cleanupOldSessions()
        startLogcatMirror()
    }

    // ------------------------------------------------------------------ app log

    /** PLog 镜像入口：异步追加，绝不阻塞调用线程。 */
    fun appendAppLog(entry: PLog.LogEntry) {
        if (!ENABLED || !started.get()) return
        val line = entry.getFormattedLog()
        ioExecutor.execute {
            try {
                val w = appWriter ?: return@execute
                w.write(line)
                w.write("\n")
                w.flush()
            } catch (t: Throwable) {
                Log.w(TAG, "app log write failed", t)
            }
        }
    }

    // ------------------------------------------------------------------ crash

    /**
     * 安装未捕获异常处理器（链式）：崩溃时先同步落盘完整快照，再交回原有链
     * （Bugly / 系统默认），不影响既有崩溃上报行为。
     * 可重复调用：Bugly 初始化之后再次调用即可接管最新链头。
     */
    fun installCrashHandler() {
        if (!ENABLED) return
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === crashHandler) return
        previousHandler = current
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)
        Log.i(TAG, "Crash handler installed, chained after=${current?.javaClass?.name}")
    }

    private fun writeCrashSnapshot(thread: Thread, throwable: Throwable) {
        val stamp = fileStamp()
        val content = buildString {
            appendLine("=== FATAL CRASH $stamp ===")
            appendLine("thread=${thread.name} (id=${thread.id})")
            appendLine("device=${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("version=${resolveVersionLabel()}")
            appendLine()
            appendLine("--- stacktrace ---")
            appendLine(Log.getStackTraceString(throwable))
            appendLine()
            appendLine("--- recent application log (newest first) ---")
            val recent = runCatching { PLog.getAllLogs() }.getOrDefault(emptyList())
            if (recent.isEmpty()) {
                appendLine("(no buffered app log)")
            } else {
                recent.forEach { appendLine(it.getFormattedLog()) }
            }
            appendLine()
            appendLine("--- end of crash snapshot ---")
        }
        // 崩溃文件最重要：主备目录各写一份
        for (dir in listOfNotNull(primaryDir, fallbackDir)) {
            try {
                val file = File(dir, "crash_$stamp.txt")
                file.parentFile?.mkdirs()
                file.writeText(content)
            } catch (t: Throwable) {
                Log.w(TAG, "crash snapshot write failed for ${dir.path}", t)
            }
        }
    }

    // ------------------------------------------------------------------ logcat mirror

    /**
     * 持续镜像本进程 logcat（main+crash 缓冲区）。
     * - 无需 READ_LOGS 权限：logcat 对普通应用自动按 UID 过滤，只输出本进程日志。
     * - 系统打印的 FATAL EXCEPTION 堆栈、native 崩溃信号（libc: Fatal signal ...）
     *   都在本进程内打印，因此会被完整捕获 —— 这是定位"打开就闪退"的关键通道。
     */
    private fun startLogcatMirror() {
        val thread = Thread({
            try {
                val pid = Process.myPid()
                val process = ProcessBuilder(
                    "logcat",
                    "--pid=$pid",
                    "-v", "threadtime",
                    "-b", "main,crash",
                    "*:V"
                )
                    .redirectErrorStream(false)
                    .start()
                val reader = BufferedReader(InputStreamReader(process.inputStream), 64 * 1024)
                while (true) {
                    val line = reader.readLine() ?: break
                    val w = logcatWriter ?: break
                    w.write(line)
                    w.write("\n")
                    w.flush()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "logcat mirror stopped", t)
            }
        }, "logcat-mirror")
        thread.isDaemon = true
        thread.priority = Thread.MIN_PRIORITY
        thread.start()
    }

    // ------------------------------------------------------------------ helpers

    private fun fileStamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 依次尝试 Download 目录与私有兜底目录，打开可写的会话文件。 */
    private fun tryOpenWriter(prefix: String): FileWriter? {
        for (dir in listOfNotNull(primaryDir, fallbackDir)) {
            try {
                val file = File(dir, "${prefix}_$sessionStamp.txt")
                file.parentFile?.mkdirs()
                return FileWriter(file, true)
            } catch (t: Throwable) {
                Log.w(TAG, "open $prefix log failed at ${dir.path}", t)
            }
        }
        return null
    }

    /** 清理过旧的会话文件（crash_* 始终保留），并控制目录总大小。 */
    private fun cleanupOldSessions() {
        ioExecutor.execute {
            for (dir in listOfNotNull(primaryDir, fallbackDir)) {
                try {
                    val sessionFiles = dir.listFiles { f ->
                        (f.name.startsWith("app_") || f.name.startsWith("logcat_")) && f.isFile
                    }?.sortedBy { it.name } ?: continue
                    // 文件名时间戳相同即为同一次会话，成对保留
                    val stamps = sessionFiles
                        .map { it.name.substringAfter('_').substringBefore('.') }
                        .distinct()
                    val toDrop = stamps.dropLast(MAX_SESSION_FILES - 1).toSet()
                    sessionFiles.filter { f ->
                        f.name.substringAfter('_').substringBefore('.') in toDrop
                    }.forEach { it.delete() }

                    // 总量保护：超限时从最旧的会话文件开始删
                    var total = dir.listFiles()?.sumOf { it.length() } ?: 0L
                    if (total > MAX_TOTAL_BYTES) {
                        dir.listFiles()
                            ?.filter { it.name.startsWith("app_") || it.name.startsWith("logcat_") }
                            ?.sortedBy { it.name }
                            ?.forEach {
                                if (total <= MAX_TOTAL_BYTES) return@forEach
                                val len = it.length()
                                if (it.delete()) total -= len
                            }
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "cleanup failed at ${dir.path}", t)
                }
            }
        }
    }

    private fun resolveVersionLabel(): String = try {
        val ctx = MyCameraApplication.instance
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${info.versionName}(${info.versionCode})"
    } catch (t: Throwable) {
        "unknown"
    }

    /**
     * 设备内核页大小（4096=传统设备，16384=16KB 新设备）。
     * 16KB 设备上加载未按 16KB 对齐的 so 会直接闪退，此值是定位启动崩溃的关键证据。
     */
    private fun resolvePageSize(): String = try {
        "${android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)}"
    } catch (t: Throwable) {
        "unknown"
    }
}
