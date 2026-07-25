package com.han2.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.DisplayMetrics;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

@CapacitorPlugin(name = "ScreenCapture")
public class ScreenCapturePlugin extends Plugin {
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private long lastFrameAt;

    @PluginMethod
    public void start(PluginCall call) {
        stopCapture();
        MediaProjectionManager manager = (MediaProjectionManager)
            getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(call, manager.createScreenCaptureIntent(), "capturePermissionResult");
    }

    @ActivityCallback
    private void capturePermissionResult(PluginCall call, ActivityResult result) {
        if (call == null) return;
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("사용자가 화면 공유를 허용하지 않았습니다.");
            return;
        }

        Context context = getContext();
        Intent serviceIntent = new Intent(context, ScreenCaptureForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        MediaProjectionManager manager = (MediaProjectionManager)
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(result.getResultCode(), result.getData());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopCapture();
                JSObject event = new JSObject();
                event.put("stopped", true);
                notifyListeners("screenStopped", event);
            }
        }, new Handler(context.getMainLooper()));

        startCapture();
        JSObject response = new JSObject();
        response.put("started", true);
        call.resolve(response);
    }

    private void startCapture() {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int sourceWidth = metrics.widthPixels;
        int sourceHeight = metrics.heightPixels;
        float scale = Math.min(1f, 720f / Math.max(sourceWidth, sourceHeight));
        int width = Math.max(2, ((int) (sourceWidth * scale)) & ~1);
        int height = Math.max(2, ((int) (sourceHeight * scale)) & ~1);

        captureThread = new HandlerThread("han2-screen-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = projection.createVirtualDisplay(
            "HAN2ScreenCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.getSurface(),
            null,
            captureHandler
        );
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        try {
            long now = System.currentTimeMillis();
            if (now - lastFrameAt < 500) return;
            lastFrameAt = now;

            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * image.getWidth();
            int paddedWidth = image.getWidth() + rowPadding / pixelStride;
            Bitmap padded = Bitmap.createBitmap(
                paddedWidth, image.getHeight(), Bitmap.Config.ARGB_8888
            );
            padded.copyPixelsFromBuffer(buffer);
            Bitmap bitmap = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
            if (bitmap != padded) padded.recycle();

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, output);
            bitmap.recycle();

            JSObject frame = new JSObject();
            frame.put(
                "dataUrl",
                "data:image/jpeg;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            );
            frame.put("width", image.getWidth());
            frame.put("height", image.getHeight());
            notifyListeners("screenFrame", frame);
        } finally {
            image.close();
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopCapture();
        call.resolve();
    }

    private synchronized void stopCapture() {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (projection != null) {
            MediaProjection activeProjection = projection;
            projection = null;
            activeProjection.stop();
        }
        if (captureThread != null) {
            captureThread.quitSafely();
            captureThread = null;
            captureHandler = null;
        }
        getContext().stopService(new Intent(getContext(), ScreenCaptureForegroundService.class));
    }

    @Override
    protected void handleOnDestroy() {
        stopCapture();
        super.handleOnDestroy();
    }
}
