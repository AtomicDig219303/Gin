package com.custom.gin.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class ProgrammaticCamera implements SurfaceHolder.Callback {
    private static final String TAG = "ProgrammaticCamera";

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

        try {
            camera.setPreviewDisplay(holder);
            
            // Set 640x360 or 1280x720 preview size for Glass 16:9 ratio
            Camera.Parameters params = camera.getParameters();
            params.setPreviewSize(640, 360);
            params.setPictureSize(1280, 720); // 720p photo
            camera.setParameters(params);

            camera.startPreview();
            Log.d(TAG, "Preview started. Taking picture...");
            
            // Wait a small delay to let exposure adjust, then take picture
            dummySurfaceView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (camera != null) {
                            camera.takePicture(null, null, jpegCallback);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "takePicture() failed", e);
                        cleanup(null);
                        callback.onError("takePicture failed: " + e.getMessage());
                    }
                }
            }, 300);

        } catch (IOException e) {
            Log.e(TAG, "Failed to set preview display", e);
            cleanup(null);
            callback.onError("Camera preview failed: " + e.getMessage());
        }
    }

    private final Camera.PictureCallback jpegCallback = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(final byte[] data, Camera cam) {
            Log.d(TAG, "Picture taken. Processing...");
            
            // Immediately release camera to prevent overheating
            cleanup(cam);

            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        // Downscale JPEG to save Bluetooth transmission time and RAM
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 2; // Decodes 1280x720 directly to 640x360
                        
                        Bitmap original = BitmapFactory.decodeByteArray(data, 0, data.length, options);
                        if (original == null) {
                            callback.onError("Failed to decode picture bytes");
                            return;
                        }

                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        original.compress(Bitmap.CompressFormat.JPEG, 80, out);
                        original.recycle();

                        final byte[] processedData = out.toByteArray();
                        Log.d(TAG, "Downscaled JPEG size: " + processedData.length + " bytes");

                        // Return result
                        callback.onPictureTaken(processedData);

                    } catch (Exception e) {
                        Log.e(TAG, "Error processing image", e);
                        callback.onError("Image process error: " + e.getMessage());
                    }
                }
            }).start();
        }
    };

    private void cleanup(Camera activeCamera) {
        if (activeCamera != null) {
            try {
                activeCamera.stopPreview();
                activeCamera.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping/releasing activeCamera", e);
            }
        } else if (camera != null) {
            try {
                camera.stopPreview();
                camera.release();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping/releasing camera", e);
            }
        }
        camera = null;

        if (dummySurfaceView != null) {
            parentLayout.removeView(dummySurfaceView);
            dummySurfaceView = null;
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
