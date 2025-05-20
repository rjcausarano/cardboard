/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cardboard;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.PopupMenu;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * A Google Cardboard VR NDK sample application.
 *
 * <p>This is the main Activity for the sample application. It initializes a GLSurfaceView to allow
 * rendering.
 */
// TODO(b/184737638): Remove decorator once the AndroidX migration is completed.
@SuppressWarnings("deprecation")
public class VrActivity extends AppCompatActivity implements PopupMenu.OnMenuItemClickListener {
  static {
    System.loadLibrary("cardboard_jni");
  }

  private static final String TAG = VrActivity.class.getSimpleName();

  // Permission request codes
  private static final int PERMISSIONS_REQUEST_CODE = 2;

  // Opaque native pointer to the native CardboardApp instance.
  // This object is owned by the VrActivity instance and passed to the native methods.
  private long nativeApp;

  private GLSurfaceView glView;

  // Video
  private long currentTimeUs = 0;
  private long videoDurationUs = -1;
  private static final long FRAME_STEP_US = 33_000; // ~30 FPS in microseconds
  private MediaMetadataRetriever retriever;

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public void onCreate(Bundle savedInstance) {
    super.onCreate(savedInstance);

    nativeApp = nativeOnCreate(getAssets());

    File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "test.mp4");
    Uri videoUri = Uri.fromFile(file);

    retriever = new MediaMetadataRetriever();
    retriever.setDataSource(this, videoUri);

    String durationMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
    long durationMs = Long.parseLong(durationMsStr);
    videoDurationUs = durationMs * 1000; // convert ms to us

    setContentView(R.layout.activity_vr);
    glView = findViewById(R.id.surface_view);
    glView.setEGLContextClientVersion(2);
    Renderer renderer = new Renderer();
    glView.setRenderer(renderer);
    glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    glView.setOnTouchListener(
        (v, event) -> {
          if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Signal a trigger event.
            glView.queueEvent(
                () -> {
                  nativeOnTriggerEvent(nativeApp);
                });
            return true;
          }
          return false;
        });

    // TODO(b/139010241): Avoid that action and status bar are displayed when pressing settings
    // button.
    setImmersiveSticky();
    View decorView = getWindow().getDecorView();
    decorView.setOnSystemUiVisibilityChangeListener(
        (visibility) -> {
          if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
            setImmersiveSticky();
          }
        });

    // Forces screen to max brightness.
    WindowManager.LayoutParams layout = getWindow().getAttributes();
    layout.screenBrightness = 1.f;
    getWindow().setAttributes(layout);

    // Prevents screen from dimming/locking.
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  }

  @Override
  protected void onPause() {
    super.onPause();
    nativeOnPause(nativeApp);
    glView.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (!isExternalStorageEnabled()) {
      requestPermissions();
      return;
    }

    glView.onResume();
    nativeOnResume(nativeApp);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    nativeOnDestroy(nativeApp);
    nativeApp = 0;
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    if (hasFocus) {
      setImmersiveSticky();
    }
  }

  public void extractNextFrame() throws IOException {
    if (currentTimeUs >= videoDurationUs) {
      Log.i("VideoFrameExtractor", "Reached end of video");
      retriever.release();
      return;
    }

    Bitmap bitmap = retriever.getFrameAtTime(currentTimeUs, MediaMetadataRetriever.OPTION_CLOSEST);

    if (bitmap == null) {
      Log.e("VideoFrameExtractor", "Failed to extract frame at " + currentTimeUs);
      currentTimeUs += FRAME_STEP_US;
      return;
    }

    Bitmap rgbaBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
    ByteBuffer buffer = ByteBuffer.allocateDirect(rgbaBitmap.getByteCount());
    buffer.order(ByteOrder.nativeOrder());
    rgbaBitmap.copyPixelsToBuffer(buffer);
    buffer.rewind();

    nativeProcessFrame(nativeApp, buffer, rgbaBitmap.getWidth(), rgbaBitmap.getHeight());

    currentTimeUs += FRAME_STEP_US;
  }

  private class Renderer implements GLSurfaceView.Renderer {
    @Override
    public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
      nativeOnSurfaceCreated(nativeApp);
    }

    @Override
    public void onSurfaceChanged(GL10 gl10, int width, int height) {
      nativeSetScreenParams(nativeApp, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl10) {
      try {
        extractNextFrame();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      nativeOnDrawFrame(nativeApp);
    }
  }

  /** Callback for when close button is pressed. */
  public void closeSample(View view) {
    Log.d(TAG, "Leaving VR sample");
    finish();
  }

  /** Callback for when settings_menu button is pressed. */
  public void showSettings(View view) {
    PopupMenu popup = new PopupMenu(this, view);
    MenuInflater inflater = popup.getMenuInflater();
    inflater.inflate(R.menu.settings_menu, popup.getMenu());
    popup.setOnMenuItemClickListener(this);
    popup.show();
  }

  @Override
  public boolean onMenuItemClick(MenuItem item) {
    if (item.getItemId() == R.id.switch_viewer) {
      nativeSwitchViewer(nativeApp);
      return true;
    }
    return false;
  }

  private boolean isExternalStorageEnabled() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // Android 13 (API 33)
      boolean hasPhotoPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
              == PackageManager.PERMISSION_GRANTED;
      boolean hasVideoPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
              == PackageManager.PERMISSION_GRANTED;
      boolean hasAudioPermission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
              == PackageManager.PERMISSION_GRANTED;
      return hasPhotoPermission && hasVideoPermission && hasAudioPermission;
    } else {
      // For Android 12 and below
      return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
              == PackageManager.PERMISSION_GRANTED;
    }
  }

  private void requestPermissions() {
    String[] permissions;

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      permissions = new String[] {
              Manifest.permission.READ_MEDIA_IMAGES,
              Manifest.permission.READ_MEDIA_VIDEO,
              Manifest.permission.READ_MEDIA_AUDIO
      };
    } else {
      permissions = new String[] {
              Manifest.permission.READ_EXTERNAL_STORAGE
      };
    }

    ActivityCompat.requestPermissions(this, permissions, PERMISSIONS_REQUEST_CODE);
  }


  @Override
  public void onRequestPermissionsResult(
          int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);

    if (requestCode == PERMISSIONS_REQUEST_CODE) {
      boolean allGranted = true;

      for (int i = 0; i < permissions.length; i++) {
        if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
          allGranted = false;

          // Check if user selected "Don't ask again"
          if (!ActivityCompat.shouldShowRequestPermissionRationale(this, permissions[i])) {
            // Permission denied permanently
            launchPermissionsSettings();
          }
        }
      }

      if (!allGranted) {
        Toast.makeText(this, R.string.read_storage_permission, Toast.LENGTH_LONG).show();
        finish();
      }
    }
  }

  private void launchPermissionsSettings() {
    Intent intent = new Intent();
    intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
    intent.setData(Uri.fromParts("package", getPackageName(), null));
    startActivity(intent);
  }

  private void setImmersiveSticky() {
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
  }

  private native long nativeOnCreate(AssetManager assetManager);

  private native void nativeOnDestroy(long nativeApp);

  private native void nativeOnSurfaceCreated(long nativeApp);

  private native void nativeOnDrawFrame(long nativeApp);

  private native void nativeOnTriggerEvent(long nativeApp);

  private native void nativeOnPause(long nativeApp);

  private native void nativeOnResume(long nativeApp);

  private native void nativeSetScreenParams(long nativeApp, int width, int height);

  private native void nativeSwitchViewer(long nativeApp);

  private native void nativeProcessFrame(long nativeApp, ByteBuffer rgbaBuffer, int width, int height);
}
