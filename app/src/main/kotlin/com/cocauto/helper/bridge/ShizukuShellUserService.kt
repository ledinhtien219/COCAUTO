package com.cocauto.helper.bridge

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs inside Shizuku's privileged ADB shell process (uid 2000).
 * Provides zero-Accessibility screen capture via Pipe (bypassing Binder's 1MB limit),
 * native input taps/swipes, and 2-finger pinch zoom via InputManager injection.
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

    override fun capturePngPipe(): ParcelFileDescriptor? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            thread(name = "ShizukuScreencapPipe") {
                try {
                    val proc = Runtime.getRuntime().exec(arrayOf("screencap", "-p"))
                    ParcelFileDescriptor.AutoCloseOutputStream(writeEnd).use { out ->
                        proc.inputStream.use { input ->
                            input.copyTo(out, bufferSize = 32 * 1024)
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
        return try {
            val dur = durationMs.coerceIn(80L, 2_500L)
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

    private fun injectTouch(
        injectMethod: java.lang.reflect.Method,
        inputManager: InputManager,
        downTime: Long,
        eventTime: Long,
        action: Int,
        pointerCount: Int,
        props: Array<MotionEvent.PointerProperties>,
        coords: Array<MotionEvent.PointerCoords>,
    ) {
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            pointerCount,
            props.copyOf(pointerCount),
            coords.copyOf(pointerCount),
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
        try {
            injectMethod.invoke(inputManager, event, 0)
        } finally {
            event.recycle()
        }
    }
}
