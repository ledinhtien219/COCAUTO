package com.cocauto.helper.bridge

import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged ADB shell process (uid 2000).
 * Provides zero-Accessibility screen capture via Pipe (raw RGBA turbo + PNG fallback),
 * direct in-process InputManagerGlobal touch/swipe/batch/pinch injection (<1ms latency!).
 */
class ShizukuShellUserService : IShizukuShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun execCommand(command: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            proc.waitFor()
            out
        } catch (e: Exception) {
            "ERR:${e.message}"
        }
    }

    override fun capturePngPipe(): ParcelFileDescriptor? = streamCommandToPipe(arrayOf("screencap", "-p"), "ShizukuPngPipe")

    override fun captureRawPipe(): ParcelFileDescriptor? = streamCommandToPipe(arrayOf("screencap"), "ShizukuRawPipe")

    private fun streamCommandToPipe(cmd: Array<String>, threadName: String): ParcelFileDescriptor? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            thread(name = threadName) {
                try {
                    val proc = Runtime.getRuntime().exec(cmd)
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        proc.inputStream.use { input ->
                            input.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                    proc.waitFor()
                } catch (_: Exception) {
                    runCatching { writeEnd.close() }
                }
            }
            readEnd
        } catch (_: Exception) {
            null
        }
    }

    override fun tap(x: Int, y: Int, holdMs: Long): Boolean {
        if (ShellPinchZoomMain.injectSingleTouch(x, y, holdMs)) return true
        return try {
            val cmd = if (holdMs > 75L) {
                arrayOf("input", "touchscreen", "swipe", "$x", "$y", "$x", "$y", "${holdMs.coerceIn(40L, 1_200L)}")
            } else {
                arrayOf("input", "tap", "$x", "$y")
            }
            val proc = Runtime.getRuntime().exec(cmd)
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val dur = durationMs.coerceIn(80L, 2_500L)
        if (ShellPinchZoomMain.injectSwipe(x1, y1, x2, y2, dur)) return true
        return try {
            val proc = Runtime.getRuntime().exec(
                arrayOf("input", "touchscreen", "swipe", "$x1", "$y1", "$x2", "$y2", "$dur"),
            )
            proc.waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    override fun pinchZoomOut(width: Int, height: Int): Boolean {
        return ShellPinchZoomMain.performPinchZoomOut(width, height, repeatCount = 2)
    }

    override fun executeTouchBatch(batchSpec: String): Boolean {
        return ShellPinchZoomMain.executeBatchSpec(batchSpec)
    }
}
