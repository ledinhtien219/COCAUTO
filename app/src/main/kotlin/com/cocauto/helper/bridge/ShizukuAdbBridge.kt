package com.cocauto.helper.bridge

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.DisplayMetrics
import android.view.WindowManager
import com.cocauto.helper.BuildConfig
import com.cocauto.helper.model.BotStateStore
import com.cocauto.helper.model.ControlBackendMode
import com.cocauto.helper.model.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

/**
 * On-device ADB Shell bridge powered by Shizuku UserService (uid 2000).
 * Runs 100% standalone on the phone (activated via Android Wireless Debugging) with
 * ZERO AccessibilityService and ZERO MediaProjection required!
 */
class ShizukuAdbBridge(private val context: Context) : DeviceBridge {
    override val mode: ControlBackendMode = ControlBackendMode.SHIZUKU_ADB

    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile
    private var shellService: IShizukuShellService? = null

    private var activeConnection: ServiceConnection? = null

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShizukuShellUserService::class.java.name),
    )
        .daemon(false)
        .processNameSuffix("shizuku_adb")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    fun isShizukuBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasShizukuPermission(): Boolean {
        if (!isShizukuBinderAlive()) return false
        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun requestShizukuPermission(requestCode: Int = SHIZUKU_PERM_REQ_CODE) {
        if (isShizukuBinderAlive() && !hasShizukuPermission()) {
            runCatching { Shizuku.requestPermission(requestCode) }
        }
    }

    private suspend fun ensureBound(): IShizukuShellService? {
        shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        if (!hasShizukuPermission()) return null

        return withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                        val iface = service?.let { IShizukuShellService.Stub.asInterface(it) }
                        shellService = iface
                        if (cont.isActive) cont.resume(iface)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        shellService = null
                    }
                }
                activeConnection = conn
                runCatching {
                    Shizuku.bindUserService(userServiceArgs, conn)
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    override suspend fun isConnected(): Boolean {
        if (!hasShizukuPermission()) return false
        return ensureBound() != null
    }

    override suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!isShizukuBinderAlive()) {
                error("Chưa khởi chạy Shizuku! Hãy mở app Shizuku và bật qua Gỡ lỗi không dây (Wireless Debugging).")
            }
            if (!hasShizukuPermission()) {
                error("Chưa cấp quyền Shizuku cho CoC Auto. Bấm 'Cấp quyền Shizuku' rồi thử lại.")
            }
            val svc = ensureBound() ?: error("Không bind được Shizuku UserService.")
            val uid = svc.execCommand("id -u").trim()
            val (w, h) = getScreenSize()
            val msg = "Shizuku ADB sẵn sàng (uid=$uid, ${w}x${h})"
            BotStateStore.log(msg, LogLevel.SUCCESS)
            msg
        }
    }

    override suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        val svc = ensureBound() ?: return@withContext null
        val pfd = runCatching { svc.capturePngPipe() }.getOrNull() ?: return@withContext null
        try {
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                val bytes = input.readBytes()
                if (bytes.size < 100) return@withContext null
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun tap(xPx: Int, yPx: Int, holdMs: Long): Boolean = withContext(Dispatchers.IO) {
        val svc = ensureBound() ?: return@withContext false
        runCatching { svc.tap(xPx, yPx, holdMs) }.getOrDefault(false)
    }

    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean = withContext(Dispatchers.IO) {
        val svc = ensureBound() ?: return@withContext false
        runCatching { svc.swipe(x1, y1, x2, y2, durationMs) }.getOrDefault(false)
    }

    override suspend fun pinchZoomOut(width: Int, height: Int): Boolean = withContext(Dispatchers.IO) {
        val svc = ensureBound() ?: return@withContext false
        runCatching { svc.pinchZoomOut(width, height) }.getOrDefault(false)
    }

    override suspend fun getScreenSize(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val svc = ensureBound()
        if (svc != null) {
            val wm = runCatching { svc.execCommand("wm size") }.getOrDefault("")
            val rot = runCatching { svc.execCommand("dumpsys input | grep SurfaceOrientation | head -n 1") }.getOrDefault("")
            AdbShellOutputParser.parseScreenSize(wm, rot)?.let { return@withContext it }
        }
        readLocalDisplaySize()
    }

    override suspend fun getForegroundPackage(): String = withContext(Dispatchers.IO) {
        val svc = ensureBound() ?: return@withContext ""
        val out = runCatching {
            svc.execCommand("dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' ; dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 3")
        }.getOrDefault("")
        AdbShellOutputParser.parseForegroundPackage(out)
    }

    override suspend fun launchPackage(packageName: String): Boolean = withContext(Dispatchers.IO) {
        val svc = ensureBound() ?: return@withContext false
        val out = runCatching {
            svc.execCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
        }.getOrDefault("")
        !out.startsWith("ERR:")
    }

    override fun disconnect() {
        activeConnection?.let { conn ->
            runCatching { Shizuku.unbindUserService(userServiceArgs, conn, true) }
        }
        activeConnection = null
        shellService = null
    }

    private fun readLocalDisplaySize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    companion object {
        const val SHIZUKU_PERM_REQ_CODE = 9101
    }
}
