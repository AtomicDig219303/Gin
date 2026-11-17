package com.custom.gin.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class ProgrammaticCamera implements SurfaceHolder.Callback {
    private static final String TAG = "ProgrammaticCamera";
    private static final int TARGET_WIDTH = 640;
    private static final int TARGET_HEIGHT = 360;
    private static final int JPEG_QUALITY = 80;

    public interface Callback {
        void onPictureTaken(byte[] jpegData);
        void onError(String message);
    }

    private final Context context;
    private final ViewGroup parentLayout;
    private final Callback callback;

    private Camera camera;
    private SurfaceView dummySurfaceView;
    private boolean isCapturing = false;
    private int configuredPictureWidth = 1280;

    private final Runnable takePictureRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (camera != null && isCapturing) {
                    camera.takePicture(null, null, jpegCallback);
                }
            } catch (Exception e) {
                Log.e(TAG, "takePicture() failed", e);
                cleanup(null);
                callback.onError("takePicture failed: " + e.getMessage());
            }
        }
    };

    public ProgrammaticCamera(Context context, ViewGroup parentLayout, Callback callback) {
        this.context = context;
        this.parentLayout = parentLayout;
        this.callback = callback;
    }

    public void capturePhoto() {
        if (isCapturing) return;
        isCapturing = true;
        Log.d(TAG, "Starting programmatic photo capture...");

        // Create a 1x1 dummy SurfaceView to satisfy Camera driver preview requirement
        dummySurfaceView = new SurfaceView(context);
        ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(1, 1);
        dummySurfaceView.setLayoutParams(lp);

        dummySurfaceView.getHolder().addCallback(this);
        parentLayout.addView(dummySurfaceView);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "Dummy surface created. Opening camera...");
        try {
            camera = Camera.open();
        } catch (Exception e) {
            Log.e(TAG, "Failed to open camera", e);
            cleanup(null);
            callback.onError("Camera open failed: " + e.getMessage());
            return;
        }

        if (camera == null) {
            cleanup(null);
            callback.onError("Camera unavailable");
            return;
        }

        try {
            camera.setPreviewDisplay(holder);

            Camera.Parameters params = camera.getParameters();
            Camera.Size previewSize = chooseOptimalSize(params.getSupportedPreviewSizes(), TARGET_WIDTH, TARGET_HEIGHT);
            if (previewSize != null) {
                params.setPreviewSize(previewSize.width, previewSize.height);
            }

            // Prefer hardware ISP capture directly at ~640x360 to avoid CPU decode + re-encode on OMAP4430
            Camera.Size pictureSize = chooseOptimalSize(params.getSupportedPictureSizes(), TARGET_WIDTH, TARGET_HEIGHT);
            if (pictureSize != null) {
                params.setPictureSize(pictureSize.width, pictureSize.height);
                configuredPictureWidth = pictureSize.width;
            } else {
                params.setPictureSize(1280, 720);
                configuredPictureWidth = 1280;
            }
            params.setJpegQuality(JPEG_QUALITY);
            camera.setParameters(params);

            camera.startPreview();
            Log.d(TAG, "Preview started (" + configuredPictureWidth + "w). Taking picture...");

            // Wait a brief delay to let exposure settle, then take picture
            if (dummySurfaceView != null) {
                dummySurfaceView.postDelayed(takePictureRunnable, 250);
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to start camera preview", e);
            cleanup(null);
            callback.onError("Camera preview failed: " + e.getMessage());
        }
    }

    private static Camera.Size chooseOptimalSize(List<Camera.Size> sizes, int targetW, int targetH) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (Camera.Size size : sizes) {
            if (size.width == targetW && size.height == targetH) {
                return size;
            }
            int diff = Math.abs(size.width - targetW) + Math.abs(size.height - targetH);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    private final Camera.PictureCallback jpegCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(final byte[] data, Camera cam) {
            final int capturedWidth = configuredPictureWidth;
            // Immediately release camera to prevent OMAP4430 overheating
            cleanup(cam);

            if (data == null || data.length == 0) {
                callback.onError("Empty camera data");
                return;
            }

            // Fast path: if hardware ISP already produced <= 640px width, skip Dalvik decode & re-encode
            if (capturedWidth <= TARGET_WIDTH) {
                Log.d(TAG, "Hardware JPEG ready directly: " + data.length + " bytes");
                callback.onPictureTaken(data);
                return;
            }

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = Math.max(2, capturedWidth / TARGET_WIDTH);
                        // RGB_565 uses 2 bytes/pixel instead of 4 (JPEG has no alpha channel)
                        options.inPreferredConfig = Bitmap.Config.RGB_565;

                        Bitmap original = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (original == null) {
                            callback.onError("Failed to decode picture bytes");
                            return;
                        }

                        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(data.length, 65536));
                        original.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
                        original.recycle();

                        final byte[] processedData = out.toByteArray();
                        Log.d(TAG, "Downscaled JPEG size: " + processedData.length + " bytes");
                        callback.onPictureTaken(processedData);

                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image", e);
                        callback.onError("Image process error: " + e.getMessage());
                    }
                }
            }, "CameraDownscaleThread").start();
        }
    };

    private void cleanup(Camera activeCamera) {
        if (dummySurfaceView != null) {
            dummySurfaceView.removeCallbacks(takePictureRunnable);
            try {
                dummySurfaceView.getHolder().removeCallback(this);
                parentLayout.removeView(dummySurfaceView);
            } catch (Exception e) {
                Log.w(TAG, "Error removing dummySurfaceView", e);
            }
            dummySurfaceView = null;
        }

        Camera camToRelease = (activeCamera != null) ? activeCamera : camera;
        camera = null;
        if (camToRelease != null) {
            try {
                camToRelease.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camToRelease.release();
            } catch (Exception e) {
                Log.w(TAG, "Error releasing camera", e);
            }
        }
        isCapturing = false;
    }

    public void release() {
        cleanup(null);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {}
}
