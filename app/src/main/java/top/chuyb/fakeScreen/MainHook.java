package top.chuyb.fakeScreen;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Main Xposed module entry point for intercepting power button actions
 * and controlling screen power modes directly via SurfaceControl.
 * @since 1.0
 * @author chuyb-re
 */
public class MainHook extends XposedModule {
    private static final String TAG = "FakeScreen";

    private static final int POWER_MODE_OFF = 0;    // Screen off
    private static final int POWER_MODE_NORMAL = 2; // Screen on
    private static volatile int mTargetPowerMode = POWER_MODE_OFF;

    private static volatile boolean sIsFakeScreenEnabled = false;

    private static final Handler mHandler = new Handler(Looper.getMainLooper());
    private static Runnable mPendingPowerTask = null;
    private static final long MULTI_PRESS_TIMEOUT_MS = 220; // Multi-press debounce timeout

    /**
     * Entry point invoked when system_server is initializing.
     *
     * @param param SystemServerStartingParam provided by Xposed framework containing class loader.
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        super.onSystemServerStarting(param);
        ClassLoader classLoader = param.getClassLoader();

        try {
            Class<?> pwmClass = classLoader.loadClass("com.android.server.policy.PhoneWindowManager");

            // 1. Dynamic IPC Receiver Registration
            registerIpcReceiver(pwmClass);

            // 2. Hook Power Button Handler
            hookPowerButton(pwmClass, classLoader);

        } catch (Throwable t) {
            log(Log.ERROR, TAG, "MainHook initialization error: " + Log.getStackTraceString(t));
        }
    }

    // =========================================================================
    // 1. IPC Broadcast & State Synchronization
    // =========================================================================

    /**
     * Hooks PhoneWindowManager.init method to safely register the IPC BroadcastReceiver.
     *
     * @param pwmClass The PhoneWindowManager Class reference.
     */
    private void registerIpcReceiver(Class<?> pwmClass) {
        Method initMethod = findMethodByName(pwmClass, "init");
        if (initMethod == null) return;

        initMethod.setAccessible(true);
        hook(initMethod).intercept(chain -> {
            Object result = chain.proceed();
            Object pwmInstance = chain.getThisObject();

            if (pwmInstance != null) {
                setupReceiver(pwmClass, pwmInstance);
            }
            return result;
        });
    }

    /**
     * Extracts system Context and registers the BroadcastReceiver for toggle actions.
     *
     * @param pwmClass    The PhoneWindowManager Class reference.
     * @param pwmInstance The PhoneWindowManager object instance.
     */
    private void setupReceiver(Class<?> pwmClass, Object pwmInstance) {
        try {
            Context systemContext = getSystemContextFromPwm(pwmClass, pwmInstance);

            if (systemContext != null) {
                // Reset Settings.Global to disabled state on boot
                Settings.Global.putInt(systemContext.getContentResolver(), MainActivity.KEY_FAKE_SCREEN_ENABLED, 0);

                IntentFilter filter = new IntentFilter(MainActivity.ACTION_TOGGLE_FAKE_SCREEN);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        boolean enabled = intent.getBooleanExtra("enabled", false);
                        sIsFakeScreenEnabled = enabled;

                        try {
                            Settings.Global.putInt(
                                    context.getContentResolver(),
                                    MainActivity.KEY_FAKE_SCREEN_ENABLED,
                                    enabled ? 1 : 0
                            );
                        } catch (Throwable t) {
                            log(Log.ERROR, TAG, "Failed to write Settings.Global in system_server", t);
                        }
                    }
                };

                ContextCompat.registerReceiver(
                        systemContext,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_EXPORTED
                );
            } else {
                log(Log.ERROR, TAG, "Failed to extract Context from PhoneWindowManager");
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to register BroadcastReceiver", t);
        }
    }

    // =========================================================================
    // 2. Power Button Hook & Single Press Interception
    // =========================================================================

    /**
     * Hooks the powerPress method in PhoneWindowManager to intercept single press gestures.
     *
     * @param pwmClass    The PhoneWindowManager Class reference.
     * @param classLoader The ClassLoader used to load system classes.
     */
    private void hookPowerButton(Class<?> pwmClass, ClassLoader classLoader) {
        Method powerPress = findPowerPressMethod(pwmClass);
        if (powerPress == null) {
            log(Log.ERROR, TAG, "Failed to find suitable powerPress method");
            return;
        }
        powerPress.setAccessible(true);

        hook(powerPress).intercept(chain -> {
            if (!sIsFakeScreenEnabled) {
                return chain.proceed();
            }

            int count = extractClickCount(chain.getArgs());

            // Bypass multi-press gestures (e.g., double press to launch camera)
            if (count > 1) {
                cancelPendingPowerTask();
                mTargetPowerMode = POWER_MODE_OFF;
                return chain.proceed();
            }

            // Intercept single power press
            if (count == 1) {
                cancelPendingPowerTask();
                mPendingPowerTask = () -> {
                    toggleDisplayPower(classLoader);
                    mPendingPowerTask = null;
                };

                mHandler.postDelayed(mPendingPowerTask, MULTI_PRESS_TIMEOUT_MS);
                return null; // Intercept native lock & sleep behavior
            }

            return chain.proceed();
        });
    }

    /**
     * Toggles screen power mode using hardware-level SurfaceControl APIs.
     *
     * @param classLoader The ClassLoader used to resolve SurfaceControl and display tokens.
     */
    private void toggleDisplayPower(ClassLoader classLoader) {
        IBinder displayBinder = getUniversalDisplayBinder(classLoader);
        if (displayBinder == null) {
            log(Log.ERROR, TAG, "DisplayBinder is null, skipping display power mode toggle");
            return;
        }

        try {
            Class<?> surfaceControlClass = classLoader.loadClass("android.view.SurfaceControl");
            @SuppressLint("SoonBlockedPrivateApi")
            Method setDisplayPowerMode = surfaceControlClass.getDeclaredMethod(
                    "setDisplayPowerMode", IBinder.class, int.class);
            setDisplayPowerMode.setAccessible(true);

            // Direct SurfaceControl display power toggle
            setDisplayPowerMode.invoke(null, displayBinder, mTargetPowerMode);

            // Flip target power mode
            mTargetPowerMode = (mTargetPowerMode == POWER_MODE_OFF) ? POWER_MODE_NORMAL : POWER_MODE_OFF;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to invoke setDisplayPowerMode", t);
        }
    }

    /**
     * Cancels any pending delayed power press tasks in the Handler queue.
     */
    private void cancelPendingPowerTask() {
        if (mPendingPowerTask != null) {
            mHandler.removeCallbacks(mPendingPowerTask);
            mPendingPowerTask = null;
        }
    }

    /**
     * Extracts click count integer from method argument list.
     *
     * @param args The method argument list captured during invocation.
     * @return The click count if present, or 1 as default.
     */
    private int extractClickCount(List<Object> args) {
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return (Integer) arg;
            }
        }
        return 1;
    }

    // =========================================================================
    // 3. Reflection Helpers
    // =========================================================================

    /**
     * Finds a declared method by name without parameter matching.
     *
     * @param clazz Target class to inspect.
     * @param name  Method name.
     * @return Matching Method object or null if not found.
     */
    private static Method findMethodByName(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    /**
     * Finds a powerPress method in PhoneWindowManager with matching parameter signature.
     *
     * @param pwmClass Target PhoneWindowManager Class reference.
     * @return Matching powerPress Method or null if not found.
     */
    private static Method findPowerPressMethod(Class<?> pwmClass) {
        for (Method method : pwmClass.getDeclaredMethods()) {
            if ("powerPress".equals(method.getName())) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length >= 2 && paramTypes[0] == long.class && paramTypes[1] == int.class) {
                    return method;
                }
            }
        }
        return null;
    }

    /**
     * Safely extracts Context from PhoneWindowManager instance via field reflection or method invocation.
     *
     * @param pwmClass    PhoneWindowManager Class reference.
     * @param pwmInstance PhoneWindowManager object instance.
     * @return System Context object if resolved, null otherwise.
     */
    private static Context getSystemContextFromPwm(Class<?> pwmClass, Object pwmInstance) {
        Class<?> currentClass = pwmClass;
        while (currentClass != null && currentClass != Object.class) {
            try {
                Field field = currentClass.getDeclaredField("mContext");
                field.setAccessible(true);
                Object ctx = field.get(pwmInstance);
                if (ctx instanceof Context) {
                    return (Context) ctx;
                }
            } catch (Throwable ignored) {}
            currentClass = currentClass.getSuperclass();
        }

        try {
            Method getContextMethod = pwmClass.getDeclaredMethod("getContext");
            getContextMethod.setAccessible(true);
            Object ctx = getContextMethod.invoke(pwmInstance);
            if (ctx instanceof Context) {
                return (Context) ctx;
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Obtains physical display IBinder token across Android versions (Android 9 to 14+).
     *
     * @param classLoader The ClassLoader used to resolve framework classes.
     * @return Physical display IBinder token or null if resolution fails.
     */
    private static IBinder getUniversalDisplayBinder(ClassLoader classLoader) {
        int sdkInt = Build.VERSION.SDK_INT;

        if (sdkInt >= 34) { // Android 14+
            try {
                Class<?> displayControl = classLoader.loadClass("com.android.server.display.DisplayControl");
                Method getPhysicalDisplayIds = displayControl.getDeclaredMethod("getPhysicalDisplayIds");
                getPhysicalDisplayIds.setAccessible(true);
                long[] ids = (long[]) getPhysicalDisplayIds.invoke(null);
                if (ids != null && ids.length > 0) {
                    Method getPhysicalDisplayToken = displayControl.getDeclaredMethod("getPhysicalDisplayToken", long.class);
                    getPhysicalDisplayToken.setAccessible(true);
                    return (IBinder) getPhysicalDisplayToken.invoke(null, ids[0]);
                }
            } catch (Throwable ignored) {}
        }

        if (sdkInt >= 29) { // Android 10 ~ 13
            try {
                Class<?> surfaceControl = classLoader.loadClass("android.view.SurfaceControl");
                @SuppressLint("BlockedPrivateApi")
                Method getInternalDisplayToken = surfaceControl.getDeclaredMethod("getInternalDisplayToken");
                getInternalDisplayToken.setAccessible(true);
                IBinder token = (IBinder) getInternalDisplayToken.invoke(null);
                if (token != null) return token;
            } catch (Throwable ignored) {}
        }

        try { // Android 9 (API 28)
            Class<?> surfaceControl = classLoader.loadClass("android.view.SurfaceControl");
            Method getBuiltInDisplay = surfaceControl.getDeclaredMethod("getBuiltInDisplay", int.class);
            getBuiltInDisplay.setAccessible(true);
            return (IBinder) getBuiltInDisplay.invoke(null, 0);
        } catch (Throwable ignored) {}

        return null;
    }
}