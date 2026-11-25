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
                arrayOf("input", "touchscreen", "swipe", "$x", "$y", "$x", "$y", "${holdMs.coerceIn(40L, 400L)}")
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

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    override fun pinchZoomOut(width: Int, height: Int): Boolean {
        return try {
            val getInstance = InputManager::class.java.getMethod("getInstance")
            val inputManager = getInstance.invoke(null) as InputManager
            val injectMethod = InputManager::class.java.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )

            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )

            val p0StartX = width * 0.24f
            val p0StartY = height * 0.28f
            val p0EndX = width * 0.44f
            val p0EndY = height * 0.46f

            val p1StartX = width * 0.76f
            val p1StartY = height * 0.72f
            val p1EndX = width * 0.56f
            val p1EndY = height * 0.54f

            val downTime = SystemClock.uptimeMillis()
            val c0 = MotionEvent.PointerCoords().apply {
                x = p0StartX; y = p0StartY; pressure = 1f; size = 1f
            }
            val c1 = MotionEvent.PointerCoords().apply {
                x = p1StartX; y = p1StartY; pressure = 1f; size = 1f
            }

            // Finger 0 DOWN
            injectTouch(injectMethod, inputManager, downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, arrayOf(c0))
            // Finger 1 POINTER_DOWN
            val p1DownAction = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            injectTouch(injectMethod, inputManager, downTime, downTime + 15L, p1DownAction, 2, props, arrayOf(c0, c1))

            // Smooth converging MOVE steps
            val steps = 10
            for (s in 1..steps) {
                val frac = s.toFloat() / steps
                c0.x = p0StartX + (p0EndX - p0StartX) * frac
                c0.y = p0StartY + (p0EndY - p0StartY) * frac
                c1.x = p1StartX + (p1EndX - p1StartX) * frac
                c1.y = p1StartY + (p1EndY - p1StartY) * frac
                SystemClock.sleep(24L)
                injectTouch(injectMethod, inputManager, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 2, props, arrayOf(c0, c1))
            }

            // Finger 1 POINTER_UP & Finger 0 UP
            val p1UpAction = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            injectTouch(injectMethod, inputManager, downTime, SystemClock.uptimeMillis(), p1UpAction, 2, props, arrayOf(c0, c1))
            injectTouch(injectMethod, inputManager, downTime, SystemClock.uptimeMillis() + 10L, MotionEvent.ACTION_UP, 1, props, arrayOf(c0))
            true
        } catch (_: Exception) {
            false
        }
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
