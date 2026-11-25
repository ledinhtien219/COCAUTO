package com.cocauto.helper.bridge

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * Standalone entry point executed under ADB shell (`uid=2000`) via:
 *   `CLASSPATH=<apkPath> app_process / com.cocauto.helper.bridge.ShellPinchZoomMain <width> <height>`
 * Performs a smooth native 2-finger pinch zoom-out gesture via InputManager.injectInputEvent
 * so AdbTcpBridge (127.0.0.1:5555) zooms out Clash of Clans maps identically to Shizuku!
 */
object ShellPinchZoomMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val width = args.getOrNull(0)?.toIntOrNull()?.coerceIn(320, 6000) ?: 2400
        val height = args.getOrNull(1)?.toIntOrNull()?.coerceIn(320, 6000) ?: 1080
        val repeatCount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 5) ?: 2
        val ok = performPinchZoomOut(width, height, repeatCount)
        println(if (ok) "PINCH_OK" else "PINCH_FAIL")
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun performPinchZoomOut(width: Int, height: Int, repeatCount: Int = 1): Boolean {
        return try {
            // Android 14+ (API 34–37) moved getInstance()/injectInputEvent to InputManagerGlobal;
            // Android 8–13 uses InputManager directly.
            val (managerObj, injectMethod) = resolveInputInjector()

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

            val p0StartX = width * 0.20f
            val p0StartY = height * 0.24f
            val p0EndX = width * 0.45f
            val p0EndY = height * 0.47f

            val p1StartX = width * 0.80f
            val p1StartY = height * 0.72f
            val p1EndX = width * 0.55f
            val p1EndY = height * 0.53f

            repeat(repeatCount.coerceIn(1, 5)) { cycle ->
                val downTime = SystemClock.uptimeMillis()
                val c0 = MotionEvent.PointerCoords().apply {
                    x = p0StartX; y = p0StartY; pressure = 1f; size = 1f
                }
                val c1 = MotionEvent.PointerCoords().apply {
                    x = p1StartX; y = p1StartY; pressure = 1f; size = 1f
                }

                // Finger 0 DOWN
                injectTouch(injectMethod, managerObj, downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, arrayOf(c0))
                // Finger 1 POINTER_DOWN
                val p1DownAction = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                injectTouch(injectMethod, managerObj, downTime, downTime + 15L, p1DownAction, 2, props, arrayOf(c0, c1))

                // Smooth converging MOVE steps
                val steps = 12
                for (s in 1..steps) {
                    val frac = s.toFloat() / steps
                    c0.x = p0StartX + (p0EndX - p0StartX) * frac
                    c0.y = p0StartY + (p0EndY - p0StartY) * frac
                    c1.x = p1StartX + (p1EndX - p1StartX) * frac
                    c1.y = p1StartY + (p1EndY - p1StartY) * frac
                    SystemClock.sleep(20L)
                    injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 2, props, arrayOf(c0, c1))
                }

                // Finger 1 POINTER_UP & Finger 0 UP
                val p1UpAction = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), p1UpAction, 2, props, arrayOf(c0, c1))
                injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis() + 10L, MotionEvent.ACTION_UP, 1, props, arrayOf(c0))

                if (cycle + 1 < repeatCount) {
                    SystemClock.sleep(140L)
                }
            }
            true
        } catch (e: Throwable) {
            val root = e.cause ?: e
            System.err.println("PINCH_ERR:${root.javaClass.simpleName}:${root.message}")
            false
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun resolveInputInjector(): Pair<Any, java.lang.reflect.Method> {
        runCatching {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = globalClass.getMethod("getInstance").invoke(null)
            val inject = globalClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            if (instance != null) return instance to inject
        }
        val legacyClass = InputManager::class.java
        val instance = legacyClass.getMethod("getInstance").invoke(null)
            ?: error("InputManager.getInstance() returned null")
        val inject = legacyClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        return instance to inject
    }

    private fun injectTouch(
        injectMethod: java.lang.reflect.Method,
        inputManager: Any,
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
