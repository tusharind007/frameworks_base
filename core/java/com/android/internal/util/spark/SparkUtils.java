/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.util.spark;

import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_RIGHT;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.input.InputManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.input.InputManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.SystemClock;
import android.text.format.Time;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.util.DisplayMetrics;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import android.util.Log;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import com.android.internal.R;
import com.android.internal.statusbar.IStatusBarService;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class SparkUtils {

    public static final String INTENT_SCREENSHOT = "action_handler_screenshot";
    public static final String INTENT_REGION_SCREENSHOT = "action_handler_region_screenshot";
    private static final int NO_CUTOUT = -1;
    private static final boolean DEBUG = false;
    private static final String TAG = "SparkUtils";

	// Check if device is connected to Wi-Fi
    public static boolean isWiFiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        NetworkInfo wifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return wifi.isConnected();
    }

	// Returns today's passed time in Millisecond
    public static long getTodayMillis() {
        final long passedMillis;
        Time time = new Time();
        time.set(System.currentTimeMillis());
        passedMillis = ((time.hour * 60 * 60) + (time.minute * 60) + time.second) * 1000;
        return passedMillis;
    }

	// Check if device is connected to the internet
    public static boolean isConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        NetworkInfo wifi = cm.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        NetworkInfo mobile = cm.getNetworkInfo(ConnectivityManager.TYPE_MOBILE);
        return wifi.isConnected() || mobile.isConnected();
    }

    public static String batteryTemperature(Context context, Boolean ForC) {
        Intent intent = context.registerReceiver(null, new IntentFilter(
                Intent.ACTION_BATTERY_CHANGED));
        float  temp = ((float) (intent != null ? intent.getIntExtra(
                BatteryManager.EXTRA_TEMPERATURE, 0) : 0)) / 10;
        // Round up to nearest number
        int c = (int) ((temp) + 0.5f);
        float n = temp + 0.5f;
        // Use boolean to determine celsius or fahrenheit
        return String.valueOf((n - c) % 2 == 0 ? (int) temp :
                ForC ? c * 9/5 + 32:c);
    }

    public static void takeScreenshot(boolean full) {
        IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        try {
            wm.sendCustomAction(new Intent(full? INTENT_SCREENSHOT : INTENT_REGION_SCREENSHOT));
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    // Method to detect countries that use Fahrenheit
    public static boolean mccCheck(Context context) {
        // MCC's belonging to countries that use Fahrenheit
        String[] mcc = {"364", "552", "702", "346", "550", "376", "330",
                "310", "311", "312", "551"};

        TelephonyManager tel = (TelephonyManager) context.getSystemService(
                Context.TELEPHONY_SERVICE);
        String networkOperator = tel.getNetworkOperator();

        // Check the array to determine celsius or fahrenheit.
        // Default to celsius if can't access MCC
        return !TextUtils.isEmpty(networkOperator) && Arrays.asList(mcc).contains(
                networkOperator.substring(0, /*Filter only 3 digits*/ 3));
    }

    // Check to see if a package is installed
    public static boolean isPackageInstalled(Context context, String pkg, boolean ignoreState) {
        if (pkg != null) {
            try {
                PackageInfo pi = context.getPackageManager().getPackageInfo(pkg, 0);
                if (!pi.applicationInfo.enabled && !ignoreState) {
                    return false;
                }
            } catch (NameNotFoundException e) {
                return false;
            }
        }

        return true;
    }

    public static boolean isPackageInstalled(Context context, String pkg) {
        return isPackageInstalled(context, pkg, true);
    }

    public static boolean deviceHasFlashlight(Context ctx) {
        return ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);
    }

    public static void toggleCameraFlash() {
        final IStatusBarService service = IStatusBarService.Stub.asInterface(
                        ServiceManager.getService("statusbar"));
        if (service != null) {
            try {
                service.toggleCameraFlash();
            } catch (RemoteException e) {
                // do nothing.
            }
        }
    }

    // Check to see if device supports the Fingerprint scanner
    public static boolean hasFingerprintSupport(Context context) {
        FingerprintManager fingerprintManager = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        return context.getApplicationContext().checkSelfPermission(Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED &&
                (fingerprintManager != null && fingerprintManager.isHardwareDetected());
    }

    public static void killForegroundApp() {
        FireActions.killForegroundApp();
    }

    private static final class FireActions {
        private static IStatusBarService mStatusBarService = null;
        private static IStatusBarService getStatusBarService() {
            synchronized (FireActions.class) {
                if (mStatusBarService == null) {
                    mStatusBarService = IStatusBarService.Stub.asInterface(
                            ServiceManager.getService("statusbar"));
                }
                return mStatusBarService;
            }
        }

        public static void killForegroundApp() {
            IStatusBarService service = getStatusBarService();
            if (service != null) {
                try {
                    service.killForegroundApp();
                } catch (RemoteException e) {
                    // do nothing.
                }
            }
        }
    }


    // Check to see if device not only supports the Fingerprint scanner but also if is enrolled
    public static boolean hasFingerprintEnrolled(Context context) {
        FingerprintManager fingerprintManager = (FingerprintManager) context.getSystemService(Context.FINGERPRINT_SERVICE);
        return context.getApplicationContext().checkSelfPermission(Manifest.permission.USE_FINGERPRINT) == PackageManager.PERMISSION_GRANTED &&
                (fingerprintManager != null && fingerprintManager.isHardwareDetected() && fingerprintManager.hasEnrolledFingerprints());
    }

    // Check to see if device has a camera
    public static boolean hasCamera(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    // Check to see if device supports NFC
    public static boolean hasNFC(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
    }

    // Check to see if device supports Wifi
    public static boolean hasWiFi(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
    }

    // Check to see if device supports Bluetooth
    public static boolean hasBluetooth(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH);
    }

    // Check to see if device supports an alterative ambient display package
    public static boolean hasAltAmbientDisplay(Context context) {
        return context.getResources().getBoolean(com.android.internal.R.bool.config_alt_ambient_display);
    }

    // Check to see if device supports A/B (seamless) system updates
    public static boolean isABdevice(Context context) {
        return SystemProperties.getBoolean("ro.build.ab_update", false);
    }

    // Check for Chinese language
    public static boolean isChineseLanguage() {
       return Resources.getSystem().getConfiguration().locale.getLanguage().startsWith(
               Locale.CHINESE.getLanguage());
    }

    public static int getBlendColorForPercent(int fullColor, int emptyColor, boolean reversed,
                                        int percentage) {
        float[] newColor = new float[3];
        float[] empty = new float[3];
        float[] full = new float[3];
        Color.colorToHSV(fullColor, full);
        int fullAlpha = Color.alpha(fullColor);
        Color.colorToHSV(emptyColor, empty);
        int emptyAlpha = Color.alpha(emptyColor);
        float blendFactor = percentage/100f;
        if (reversed) {
            if (empty[0] < full[0]) {
                empty[0] += 360f;
            }
            newColor[0] = empty[0] - (empty[0]-full[0])*blendFactor;
        } else {
            if (empty[0] > full[0]) {
                full[0] += 360f;
            }
            newColor[0] = empty[0] + (full[0]-empty[0])*blendFactor;
        }
        if (newColor[0] > 360f) {
            newColor[0] -= 360f;
        } else if (newColor[0] < 0) {
            newColor[0] += 360f;
        }
        newColor[1] = empty[1] + ((full[1]-empty[1])*blendFactor);
        newColor[2] = empty[2] + ((full[2]-empty[2])*blendFactor);
        int newAlpha = (int) (emptyAlpha + ((fullAlpha-emptyAlpha)*blendFactor));
        return Color.HSVToColor(newAlpha, newColor);
    }

    // Method to turn off the screen
    public static void switchScreenOff(Context ctx) {
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm!= null) {
            pm.goToSleep(SystemClock.uptimeMillis());
        }
    }

    public static void sendKeycode(int keycode) {
        long when = SystemClock.uptimeMillis();
        final KeyEvent evDown = new KeyEvent(when, when, KeyEvent.ACTION_DOWN, keycode, 0,
                0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        final KeyEvent evUp = KeyEvent.changeAction(evDown, KeyEvent.ACTION_UP);

        final Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                InputManager.getInstance().injectInputEvent(evDown,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        });
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                InputManager.getInstance().injectInputEvent(evUp,
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
        }, 20);
    }

	    // Check if device has a notch
    public static boolean hasNotch(Context context) {
        String displayCutout = context.getResources().getString(R.string.config_mainBuiltInDisplayCutout);
        boolean maskDisplayCutout = context.getResources().getBoolean(R.bool.config_maskMainBuiltInDisplayCutout);
        boolean displayCutoutExists = (!TextUtils.isEmpty(displayCutout) && !maskDisplayCutout);
        return displayCutoutExists;
    }

    public static int getCutoutType(Context context) {
        final DisplayInfo info = new DisplayInfo();
        context.getDisplay().getDisplayInfo(info);
        final DisplayCutout cutout = info.displayCutout;
        if (cutout == null) {
            if (DEBUG) Log.v(TAG, "noCutout");
            return NO_CUTOUT;
        }
        final Point displaySize = new Point();
        context.getDisplay().getRealSize(displaySize);
        List<Rect> cutOutBounds = cutout.getBoundingRects();
        if (cutOutBounds != null) {
            for (Rect cutOutRect : cutOutBounds) {
                if (DEBUG) Log.v(TAG, "cutout left= " + cutOutRect.left);
                if (DEBUG) Log.v(TAG, "cutout right= " + cutOutRect.right);
                if (cutOutRect.left == 0 && cutOutRect.right > 0) {  //cutout is located on top left
                    if (DEBUG) Log.v(TAG, "cutout position= " + BOUNDS_POSITION_LEFT);
                    return BOUNDS_POSITION_LEFT;
                } else if (cutOutRect.right == displaySize.x && (displaySize.x - cutOutRect.left) > 0) {  //cutout is located on top right
                    if (DEBUG) Log.v(TAG, "cutout position= " + BOUNDS_POSITION_RIGHT);
                    return BOUNDS_POSITION_RIGHT;
                }
            }
        }
        return NO_CUTOUT;
    }
}
