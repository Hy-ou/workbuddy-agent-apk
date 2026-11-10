package com.wb.vds;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Shell/root 侧的虚拟屏服务。
 *
 * 由 App 通过 Shizuku 以 app_process 启动（uid 2000 或 root）：
 *   app_process -Djava.class.path=<app files>/vds.dex /system/bin com.wb.vds.Server W H DPI DIR
 *
 * 为什么必须由 shell/root 创建：
 *   Android 17 的 ActivityTaskSupervisor 明确限制「普通 App 在虚拟屏上启动其他 App」
 *   （SecurityManagerService.isVirtualDisplayActive 会直接拒绝）。让 shell/root 成为
 *   显示 owner，再用同一 uid 的 `am start --display` 启动，才符合系统策略。
 *
 * DIR 是 App 私有缓存目录：服务把 displayId 写到 DIR/id，把最新帧写到 DIR/frame.png，
 * App 直接读自己的缓存即可，不需要 screencap，也不需要跨进程传帧。
 */
public final class Server {

    private static volatile Bitmap sFrame;
    private static int sWidth;
    private static int sHeight;
    private static String sDir;
    private static long sLastWrite;

    public static void main(String[] args) {
        try {
            if (args.length < 4) {
                System.err.println("usage: Server W H DPI DIR");
                return;
            }
            sWidth = Integer.parseInt(args[0]);
            sHeight = Integer.parseInt(args[1]);
            int dpi = Integer.parseInt(args[2]);
            sDir = args[3];
            File dir = new File(sDir);
            if (!dir.exists() && !dir.mkdirs()) {
                System.err.println("cannot create " + sDir);
                return;
            }

            try {
                Looper.prepareMainLooper();
            } catch (Throwable ignored) {
                // already prepared
            }

            Class<?> at = Class.forName("android.app.ActivityThread");
            Method systemMain = at.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object thread = systemMain.invoke(null);
            Method getSystemContext = at.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context ctx = (Context) getSystemContext.invoke(thread);
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (dm == null) {
                System.err.println("no DisplayManager");
                return;
            }

            ImageReader reader = ImageReader.newInstance(
                    sWidth, sHeight, PixelFormat.RGBA_8888, 3);
            HandlerThread frames = new HandlerThread("wb-vds-frames");
            frames.start();
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image image = null;
                    try {
                        image = r.acquireLatestImage();
                        if (image == null) return;
                        long now = SystemClock.uptimeMillis();
                        if (now - sLastWrite < 350) return;
                        sLastWrite = now;
                        writeFrame(image);
                    } catch (Throwable ignored) {
                        // keep serving the next frame
                    } finally {
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }, new Handler(frames.getLooper()));

            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
            VirtualDisplay display = dm.createVirtualDisplay(
                    "WorkBuddy 虚拟屏", sWidth, sHeight, dpi, reader.getSurface(), flags);
            if (display == null || display.getDisplay() == null) {
                System.err.println("createVirtualDisplay failed");
                return;
            }
            int id = display.getDisplay().getDisplayId();
            writeText(new File(dir, "id"), String.valueOf(id));
            writeText(new File(dir, "pid"), String.valueOf(android.os.Process.myPid()));
            writeText(new File(dir, "owner"), String.valueOf(android.os.Process.myUid()));
            System.err.println("virtual display ready id=" + id + " uid=" + android.os.Process.myUid());

            Looper.loop();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static void writeFrame(Image image) throws Exception {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        Bitmap bitmap;
        if (pixelStride == 4 && rowStride == sWidth * 4) {
            if (sFrame == null) {
                sFrame = Bitmap.createBitmap(sWidth, sHeight, Bitmap.Config.ARGB_8888);
            }
            buffer.rewind();
            sFrame.copyPixelsFromBuffer(buffer);
            bitmap = sFrame;
        } else {
            int paddedWidth = sWidth + (rowStride - pixelStride * sWidth) / pixelStride;
            Bitmap padded = Bitmap.createBitmap(paddedWidth, sHeight, Bitmap.Config.ARGB_8888);
            buffer.rewind();
            padded.copyPixelsFromBuffer(buffer);
            bitmap = Bitmap.createBitmap(padded, 0, 0, sWidth, sHeight);
            padded.recycle();
        }
        File tmp = new File(sDir, "frame.tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, out);
        } finally {
            out.close();
        }
        if (bitmap != sFrame) {
            bitmap.recycle();
        }
        File target = new File(sDir, "frame.png");
        if (!tmp.renameTo(target)) {
            // Some file systems don't replace atomically; copy over.
            FileOutputStream direct = new FileOutputStream(target);
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, direct);
            } finally {
                direct.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static void writeText(File file, String value) throws Exception {
        FileWriter writer = new FileWriter(file, false);
        try {
            writer.write(value);
        } finally {
            writer.close();
        }
    }
}
