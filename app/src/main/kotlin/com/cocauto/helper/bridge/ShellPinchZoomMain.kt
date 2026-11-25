package com.cocauto.helper.bridge

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent

/**
 * High-speed native touch injector executed under ADB shell (`uid=2000`) either:
 * - Directly in-process from ShizukuShellUserService (<1ms latency), OR
 * - Via a single `CLASSPATH=<apkPath> app_process / com.cocauto.helper.bridge.ShellPinchZoomMain ...`
 *   call from AdbTcpBridge (zero per-tap `input` fork overhead!).
 *
 * Supports:
 * 1. `ShellPinchZoomMain <width> <height> [repeat]` -> native 2-finger pinch zoom-out
 * 2. `ShellPinchZoomMain batch "x,y,holdMs,waitMs;..."` -> ultra-fast batch troop deployment
 */
object ShellPinchZoomMain {
    @Volatile
    private var cachedInjector: Pair<Any, java.lang.reflect.Method>? = null

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.firstOrNull() == "batch") {
            val spec = args.getOrNull(1).orEmpty()
            val ok = executeBatchSpec(spec)
            println(if (ok) "BATCH_OK" else "BATCH_FAIL")
            return
        }
        val width = args.getOrNull(0)?.toIntOrNull()?.coerceIn(320, 6000) ?: 2400
        val height = args.getOrNull(1)?.toIntOrNull()?.coerceIn(320, 6000) ?: 1080
        val repeatCount = args.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 5) ?: 2
        val ok = performPinchZoomOut(width, height, repeatCount)
        println(if (ok) "PINCH_OK" else "PINCH_FAIL")
    }

    /**
     * Executes a semicolon-separated sequence of `"x,y,holdMs,pauseAfterMs"` touch operations
     * inside one native InputManager session (5x faster than spawning `input tap` per touch).
     */
    fun executeBatchSpec(spec: String): Boolean {
        if (spec.isBlank()) return true
        return try {
            val (managerObj, injectMethod) = resolveInputInjector()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
            val coords = arrayOf(
                MotionEvent.PointerCoords().apply {
                    pressure = 1f
                    size = 1f
                },
            )

            for (rawStep in spec.split(';')) {
                if (rawStep.isBlank()) continue
                val parts = rawStep.split(',')
                if (parts.size < 2) continue
                val x = parts[0].toFloatOrNull() ?: continue
                val y = parts[1].toFloatOrNull() ?: continue
                val holdMs = parts.getOrNull(2)?.toLongOrNull()?.coerceIn(25L, 1_500L) ?: 45L
                val pauseMs = parts.getOrNull(3)?.toLongOrNull()?.coerceIn(0L, 2_000L) ?: 25L

                coords[0].x = x
                coords[0].y = y
                val downTime = SystemClock.uptimeMillis()
                injectTouch(injectMethod, managerObj, downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, coords)
                if (holdMs > 70L) {
                    // Emit periodic ACTION_MOVE heartbeats every ~50ms while holding so Clash of Clans
                    // triggers native continuous rapid-fire troop spawning (~6-10 troops per 550ms hold!)
                    val endAt = downTime + holdMs
                    var toggle = 0
                    while (SystemClock.uptimeMillis() < endAt) {
                        val rem = endAt - SystemClock.uptimeMillis()
                        SystemClock.sleep(minOf(50L, rem.coerceAtLeast(10L)))
                        toggle = toggle xor 1
                        coords[0].x = x + if (toggle == 1) 1f else 0f
                        coords[0].y = y
                        injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1, props, coords)
                    }
                } else if (holdMs > 25L) {
                    SystemClock.sleep(holdMs)
                }
                injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 1, props, coords)
                if (pauseMs > 0L) {
                    SystemClock.sleep(pauseMs)
                }
            }
            true
        } catch (e: Throwable) {
            val root = e.cause ?: e
            System.err.println("BATCH_ERR:${root.javaClass.simpleName}:${root.message}")
            false
        }
    }

    fun injectSingleTouch(x: Int, y: Int, holdMs: Long): Boolean {
        return executeBatchSpec("$x,$y,${holdMs.coerceIn(25L, 1_500L)},0")
    }

    fun injectSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        return try {
            val (managerObj, injectMethod) = resolveInputInjector()
            val props = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                },
            )
            val c0 = MotionEvent.PointerCoords().apply {
                x = x1.toFloat(); y = y1.toFloat(); pressure = 1f; size = 1f
            }
            val downTime = SystemClock.uptimeMillis()
            injectTouch(injectMethod, managerObj, downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, arrayOf(c0))
            val steps = (durationMs / 16L).toInt().coerceIn(4, 30)
            val sleepStep = (durationMs / steps).coerceAtLeast(8L)
            for (s in 1..steps) {
                val frac = s.toFloat() / steps
                c0.x = x1 + (x2 - x1) * frac
                c0.y = y1 + (y2 - y1) * frac
                SystemClock.sleep(sleepStep)
                injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1, props, arrayOf(c0))
            }
            injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis() + 5L, MotionEvent.ACTION_UP, 1, props, arrayOf(c0))
            true
        } catch (_: Throwable) {
            false
        }
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun performPinchZoomOut(width: Int, height: Int, repeatCount: Int = 2): Boolean {
        return try {
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

                injectTouch(injectMethod, managerObj, downTime, downTime, MotionEvent.ACTION_DOWN, 1, props, arrayOf(c0))
                val p1DownAction = MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                injectTouch(injectMethod, managerObj, downTime, downTime + 12L, p1DownAction, 2, props, arrayOf(c0, c1))

                val steps = 8
                for (s in 1..steps) {
                    val frac = s.toFloat() / steps
                    c0.x = p0StartX + (p0EndX - p0StartX) * frac
                    c0.y = p0StartY + (p0EndY - p0StartY) * frac
                    c1.x = p1StartX + (p1EndX - p1StartX) * frac
                    c1.y = p1StartY + (p1EndY - p1StartY) * frac
                    SystemClock.sleep(14L)
                    injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 2, props, arrayOf(c0, c1))
                }

                val p1UpAction = MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis(), p1UpAction, 2, props, arrayOf(c0, c1))
                injectTouch(injectMethod, managerObj, downTime, SystemClock.uptimeMillis() + 8L, MotionEvent.ACTION_UP, 1, props, arrayOf(c0))

                if (cycle + 1 < repeatCount) {
                    SystemClock.sleep(70L)
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
        cachedInjector?.let { return it }
        runCatching {
            val globalClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = globalClass.getMethod("getInstance").invoke(null)
            val inject = globalClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
            )
            if (instance != null) {
                return (instance to inject).also { cachedInjector = it }
            }
        }
        val legacyClass = InputManager::class.java
        val instance = legacyClass.getMethod("getInstance").invoke(null)
            ?: error("InputManager.getInstance() returned null")
        val inject = legacyClass.getMethod(
            "injectInputEvent",
            InputEvent::class.java,
            Int::class.javaPrimitiveType,
        )
        return (instance to inject).also { cachedInjector = it }
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
