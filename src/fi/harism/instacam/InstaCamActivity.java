/*
   Copyright 2012 Harri Smatt

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package fi.harism.instacam;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;

import android.animation.LayoutTransition;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Images.ImageColumns;
import android.provider.MediaStore.MediaColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The one and only Activity for this camera application.
 */
public class InstaCamActivity extends Activity {

	// Custom camera holder class.
	private final InstaCamCamera mCamera = new InstaCamCamera();
	// RenderScript holder class.
	private InstaCamRS mInstaCamRS;
	// Common observer for all Buttons.
	private final ButtonObserver mObserverButton = new ButtonObserver();
	// Camera observer for handling picture taking.
	private final CameraObserver mObserverCamera = new CameraObserver();
	// Observer for handling SurfaceTexture creation.
	private final RendererObserver mObserverRenderer = new RendererObserver();
	// Common observer for all SeekBars.
	private final SeekBarObserver mObserverSeekBar = new SeekBarObserver();
	// Application shared preferences instance.
	private SharedPreferences mPreferences;
	// Preview texture renderer class.
	private InstaCamRenderer mRenderer;
	// Shared data instance.
	private final InstaCamData mSharedData = new InstaCamData();

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		// We prevent screen orientation changes.
		super.onConfigurationChanged(newConfig);
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		// Force full screen view.
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		getWindow().clearFlags(
				WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);

		// Instantiate RenderScript.
		mInstaCamRS = new InstaCamRS(this);

		// Instantiate camera handler.
		mCamera.setCamera(Camera.CameraInfo.CAMERA_FACING_BACK);
		mCamera.setSharedData(mSharedData);

		// Set content view.
		setContentView(R.layout.instacam);

		// Find renderer view and instantiate it.
		mRenderer = (InstaCamRenderer) findViewById(R.id.instacam_renderer);
		mRenderer.setSharedData(mSharedData);
		mRenderer.setObserver(mObserverRenderer);

		// Hide menu view by default.
		View menu = findViewById(R.id.menu);
		menu.setVisibility(View.GONE);

		// Set transition for root view (hide/show menu).
		LayoutTransition layoutTransition = new LayoutTransition();
		layoutTransition.setDuration(250);
		ViewGroup viewGroup = (ViewGroup) findViewById(R.id.root);
		viewGroup.setLayoutTransition(layoutTransition);

		// Set transition for footer (switch "shoot" / "cancel"&"save").
		layoutTransition = new LayoutTransition();
		layoutTransition.setDuration(250);
		viewGroup = (ViewGroup) findViewById(R.id.footer);
		viewGroup.setLayoutTransition(layoutTransition);

		// Set Button OnClickListeners.
		findViewById(R.id.button_exit).setOnClickListener(mObserverButton);
		findViewById(R.id.button_shoot).setOnClickListener(mObserverButton);
		findViewById(R.id.button_save).setOnClickListener(mObserverButton);
		findViewById(R.id.button_cancel).setOnClickListener(mObserverButton);
		findViewById(R.id.button_menu).setOnClickListener(mObserverButton);

		// Get preferences instance.
		mPreferences = getPreferences(MODE_PRIVATE);

		// SeekBar ids as triples { SeekBar id, key id, default value }.
		final int SEEKBAR_IDS[][] = {
				{ R.id.seekbar_brightness, R.string.key_brightness, 5 },
				{ R.id.seekbar_contrast, R.string.key_contrast, 5 },
				{ R.id.seekbar_saturation, R.string.key_saturation, 8 },
				{ R.id.seekbar_corner_radius, R.string.key_corner_radius, 3 } };
		// Set SeekBar OnSeekBarChangeListeners and default progress.
		for (int ids[] : SEEKBAR_IDS) {
			SeekBar seekBar = (SeekBar) findViewById(ids[0]);
			seekBar.setOnSeekBarChangeListener(mObserverSeekBar);
			seekBar.setProgress(mPreferences.getInt(getString(ids[1]), ids[2]));
			// SeekBar.setProgress triggers observer only in case its value
			// changes. And we're relying on this trigger to happen.
			if (seekBar.getProgress() == 0) {
				seekBar.setProgress(1);
				seekBar.setProgress(0);
			}
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
	}

	@Override
	public void onPause() {
		super.onPause();
		mCamera.onPause();
		mRenderer.onPause();
	}

	@Override
	public void onResume() {
		super.onResume();
		mCamera.onResume();
		mRenderer.onResume();
	}

	private final class ButtonObserver implements View.OnClickListener {
		@Override
		public void onClick(View v) {
			switch (v.getId()) {
			// On exit simply close Activity.
			case R.id.button_exit:
				finish();
				break;
			// On shoot trigger autoFocus. This call ends to
			// CameraObserver.onAutoFocus eventually.
			case R.id.button_shoot:
				mCamera.autoFocus(mObserverCamera);
				break;
			// Pressing menu button switches menu visibility.
			case R.id.button_menu:
				View view = findViewById(R.id.menu);
				if (view.getVisibility() == View.GONE
						|| view.getVisibility() == View.INVISIBLE) {
					view.setVisibility(View.VISIBLE);
				} else {
					view.setVisibility(View.GONE);
				}
				break;
			// Save button starts separate thread for saving picture.
			case R.id.button_save:
				mSharedData.mImageProgress = ProgressDialog
						.show(InstaCamActivity.this, null,
								getString(R.string.saving));
				Thread thread = new Thread(new SaveRunnable());
				thread.setPriority(Thread.MAX_PRIORITY);
				thread.start();
				break;
			// Cancel button simply discards current picture data.
			case R.id.button_cancel:
				mSharedData.mImageData = null;
				findViewById(R.id.button_shoot).setVisibility(View.VISIBLE);
				findViewById(R.id.buttons_cancel_save).setVisibility(View.GONE);
				mCamera.startPreview();
				break;
			}
		}

	}

	/**
	 * Class for implementing Camera related callbacks.
	 */
	private final class CameraObserver implements Camera.ShutterCallback,
			Camera.AutoFocusCallback, Camera.PictureCallback {
		@Override
		public void onAutoFocus(boolean success, Camera camera) {
			// If auto focus failed show brief notification about it.
			if (!success) {
				Toast.makeText(InstaCamActivity.this, R.string.focus_failed,
						Toast.LENGTH_SHORT).show();
			}
			// And continue with taking the picture. This call will end up to
			// onPictureTaken eventually.
			camera.takePicture(this, null, this);
		}

		@Override
		public void onPictureTaken(byte[] data, Camera camera) {
			// Once picture is taken just store its data.
			mSharedData.mImageData = data;
			// And time it was taken.
			Calendar calendar = Calendar.getInstance();
			mSharedData.mImageTime = calendar.getTimeInMillis();
		}

		@Override
		public void onShutter() {
			// At the point picture is actually taken switch footer buttons.
			findViewById(R.id.buttons_cancel_save).setVisibility(View.VISIBLE);
			findViewById(R.id.button_shoot).setVisibility(View.GONE);
		}

	}

	/**
	 * Class for implementing InstaCamRenderer related callbacks.
	 */
	private class RendererObserver implements InstaCamRenderer.Observer {
		@Override
		public void onSurfaceTextureCreated(SurfaceTexture surfaceTexture) {
			// Once we have SurfaceTexture try setting it to Camera.
			try {
				mCamera.setPreviewTexture(surfaceTexture);
				mCamera.startPreview();
			} catch (final Exception ex) {
				runOnUiThread(new Runnable() {
					@Override
					public void run() {
						Toast.makeText(InstaCamActivity.this, ex.getMessage(),
								Toast.LENGTH_LONG).show();
					}
				});
			}
		}
	}

	/**
	 * Runnable for handling asynchronous picture saving.
	 */
	private final class SaveRunnable implements Runnable {

		@Override
		public void run() {
			String error = null;
			try {
				// First instantiate calendar instance.
				Calendar calendar = Calendar.getInstance();
				calendar.setTimeInMillis(mSharedData.mImageTime);

				// Generate picture name.
				String pictureName = String.format(
						"InstaCam_%d%02d%02d_%02d%02d%02d",
						calendar.get(Calendar.YEAR),
						calendar.get(Calendar.MONTH) + (1 - Calendar.JANUARY),
						calendar.get(Calendar.DATE),
						calendar.get(Calendar.HOUR_OF_DAY),
						calendar.get(Calendar.MINUTE),
						calendar.get(Calendar.SECOND));

				// Get "Pictures" -directory path.
				File filePath = Environment
						.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
				// Add "/InstaCam/" -directory to it.
				filePath = new File(filePath, "/InstaCam/");
				// Make all dirs till ".../Pictures/InstaCam/".
				filePath.mkdirs();
				// Generate final file path ".../InstaCam/picname.jpeg".
				filePath = new File(filePath, pictureName + ".jpeg");
				// Create picture file.
				filePath.createNewFile();

				// We'd prefer to have ARGB_8888 Bitmap.
				BitmapFactory.Options options = new BitmapFactory.Options();
				options.inPreferredConfig = Bitmap.Config.ARGB_8888;
				// Decode picture taken by camera.
				Bitmap bitmap = BitmapFactory.decodeByteArray(
						mSharedData.mImageData, 0,
						mSharedData.mImageData.length, options);
				// Apply RenderScript filter to Bitmap.
				mInstaCamRS.applyFilter(bitmap, mSharedData);
				// Save picture to file system.
				FileOutputStream fos = new FileOutputStream(filePath);
				bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
				fos.flush();
				fos.close();
				bitmap.recycle();

				// Add picture to content resolver.
				ContentValues v = new ContentValues();
				v.put(MediaColumns.TITLE, pictureName);
				v.put(MediaColumns.DISPLAY_NAME, pictureName);
				v.put(ImageColumns.DESCRIPTION, "Taken with InstaCam.");
				v.put(MediaColumns.DATE_ADDED, calendar.getTimeInMillis());
				v.put(ImageColumns.DATE_TAKEN, calendar.getTimeInMillis());
				v.put(MediaColumns.DATE_MODIFIED, calendar.getTimeInMillis());
				v.put(MediaColumns.MIME_TYPE, "image/jpeg");
				v.put(ImageColumns.ORIENTATION,
						mCamera.getCameraInfo().orientation);
				v.put(MediaColumns.DATA, filePath.getAbsolutePath());

				File parent = filePath.getParentFile();
				String path = parent.toString().toLowerCase();
				String name = parent.getName().toLowerCase();
				v.put(Images.ImageColumns.BUCKET_ID, path.hashCode());
				v.put(Images.ImageColumns.BUCKET_DISPLAY_NAME, name);
				v.put(MediaColumns.SIZE, filePath.length());

				// if( targ_loc != null ) {
				// v.put(Images.Media.LATITUDE, loc.getLatitude());
				// v.put(Images.Media.LONGITUDE, loc.getLongitude());
				// }

				ContentResolver c = getContentResolver();
				c.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
			} catch (Exception ex) {
				error = ex.getMessage();
			}

			// Finally hide progress dialog, dismiss picture data and show error
			// message if an error occurred during saving.
			final String errorMsg = error;
			runOnUiThread(new Runnable() {
				@Override
				public void run() {
					mSharedData.mImageProgress.dismiss();
					mSharedData.mImageProgress = null;
					mSharedData.mImageData = null;
					findViewById(R.id.button_shoot).setVisibility(View.VISIBLE);
					findViewById(R.id.buttons_cancel_save).setVisibility(
							View.GONE);
					mCamera.startPreview();
					if (errorMsg != null) {
						Toast.makeText(InstaCamActivity.this, errorMsg,
								Toast.LENGTH_LONG).show();
					}
				}
			});
		}
	}

	/**
	 * Class for implementing SeekBar related callbacks.
	 */
	private final class SeekBarObserver implements
			SeekBar.OnSeekBarChangeListener {

		@Override
		public void onProgressChanged(SeekBar seekBar, int progress,
				boolean fromUser) {

			switch (seekBar.getId()) {
			// On brightness recalculate shared value and update preferences.
			case R.id.seekbar_brightness: {
				mPreferences.edit()
						.putInt(getString(R.string.key_brightness), progress)
						.commit();
				mSharedData.mBrightness = (progress - 5) / 10f;

				TextView textView = (TextView) findViewById(R.id.text_brightness);
				textView.setText(getString(R.string.seekbar_brightness,
						progress - 5));
				break;
			}
			// On contrast recalculate shared value and update preferences.
			case R.id.seekbar_contrast: {
				mPreferences.edit()
						.putInt(getString(R.string.key_contrast), progress)
						.commit();
				mSharedData.mContrast = (progress - 5) / 10f;
				TextView textView = (TextView) findViewById(R.id.text_contrast);
				textView.setText(getString(R.string.seekbar_contrast,
						progress - 5));
				break;
			}
			// On saturation recalculate shared value and update preferences.
			case R.id.seekbar_saturation: {
				mPreferences.edit()
						.putInt(getString(R.string.key_saturation), progress)
						.commit();
				mSharedData.mSaturation = (progress - 8) / 8f;
				TextView textView = (TextView) findViewById(R.id.text_saturation);
				textView.setText(getString(R.string.seekbar_saturation,
						progress - 8));
				break;
			}
			// On radius recalculate shared value and update preferences.
			case R.id.seekbar_corner_radius: {
				mPreferences
						.edit()
						.putInt(getString(R.string.key_corner_radius), progress)
						.commit();
				mSharedData.mCornerRadius = progress / 10f;
				TextView textView = (TextView) findViewById(R.id.text_corner_radius);
				textView.setText(getString(R.string.seekbar_corner_radius,
						-progress));
				break;
			}
			}
			mRenderer.requestRender();
		}

		@Override
		public void onStartTrackingTouch(SeekBar seekBar) {
		}

		@Override
		public void onStopTrackingTouch(SeekBar seekBar) {
		}
	}

}