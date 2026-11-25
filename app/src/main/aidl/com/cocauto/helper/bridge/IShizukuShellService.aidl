package com.cocauto.helper.bridge;

import android.os.ParcelFileDescriptor;

interface IShizukuShellService {
    void destroy() = 16777114; // Shizuku standard destroy transaction code
    String execCommand(String command) = 1;
    ParcelFileDescriptor capturePngPipe() = 2;
    boolean tap(int x, int y, long holdMs) = 3;
    boolean swipe(int x1, int y1, int x2, int y2, long durationMs) = 4;
    boolean pinchZoomOut(int width, int height) = 5;
}
