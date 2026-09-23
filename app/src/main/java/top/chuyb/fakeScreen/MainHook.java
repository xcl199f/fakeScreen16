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
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

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
    private static final AtomicInteger mCurrentPowerMode = new AtomicInteger(POWER_MODE_NORMAL);
    private static volatile boolean sIsFakeScreenEnabled = false;
    private static volatile boolean sIsDisableSleepEnabled = false;
    private static final Handler mHandler = new Handler(Looper.getMainLooper());
    private static Runnable mPendingPowerTask = null;
    private static final long MULTI_PRESS_TIMEOUT_MS = 250; // Multi-press debounce timeout
    private static final long DOUBLE_PRESS_INTERVAL_MS = 300;
    private static long sLastPowerPressTime = 0;
    @SuppressLint("StaticFieldLeak")
    private static Context sSystemContext = null;
    private static android.app.KeyguardManager sKeyguardManager = null;
    private static volatile boolean sScreenOnBeforePress = true;
    private static Method sIsScreenOnMethod = null;
    private static Field sPolicyField = null;
    private static Field sScreenOnField = null;
    private static int sScreenStateMode = 0;   // 0=未初始化 1=isScreenOn 2=DisplayPolicy 3=广播


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

            hookDisableSleep(classLoader);

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
            final ClassLoader cl = pwmClass.getClassLoader();

            if (systemContext != null) {
                sSystemContext = systemContext;
                sKeyguardManager = (android.app.KeyguardManager) systemContext.getSystemService(Context.KEYGUARD_SERVICE);
                setupScreenStateTracker(cl, systemContext);

                // Reset Settings.Global to disabled state on boot
                try {
                    sIsFakeScreenEnabled = Settings.Global.getInt(
                            systemContext.getContentResolver(),
                            MainActivity.KEY_FAKE_SCREEN_ENABLED, 0) == 1;
                    Log.d(TAG, "restored sIsFakeScreenEnabled=" + sIsFakeScreenEnabled);
                } catch (Throwable ignored) {}
                try {
                    sIsDisableSleepEnabled = Settings.Global.getInt(
                            systemContext.getContentResolver(),
                            MainActivity.KEY_DISABLE_SLEEP_ENABLED, 0) == 1;
                    Log.d(TAG, "restored sIsDisableSleepEnabled=" + sIsDisableSleepEnabled);
                } catch (Throwable ignored) {}

                IntentFilter filter = new IntentFilter();
                filter.addAction(MainActivity.ACTION_TOGGLE_FAKE_SCREEN);
                filter.addAction(MainActivity.ACTION_TOGGLE_DISABLE_SLEEP);
                filter.addAction(MainActivity.ACTION_FAKE_SCREEN_NOW);
                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String action = intent.getAction();
                        boolean enabled = intent.getBooleanExtra("enabled", false);
                        Log.d(TAG, "MainHook onReceive: action=" + action + " enabled=" + enabled);

                        if (MainActivity.ACTION_TOGGLE_FAKE_SCREEN.equals(action)) {
                            sIsFakeScreenEnabled = enabled;
                            try {
                                Settings.Global.putInt(context.getContentResolver(),
                                        MainActivity.KEY_FAKE_SCREEN_ENABLED, enabled ? 1 : 0);
                            } catch (Throwable t) {
                                log(Log.ERROR, TAG, "write KEY_FAKE_SCREEN_ENABLED failed", t);
                            }
                        } else if (MainActivity.ACTION_TOGGLE_DISABLE_SLEEP.equals(action)) {
                            sIsDisableSleepEnabled = enabled;
                            try {
                                Settings.Global.putInt(context.getContentResolver(),
                                        MainActivity.KEY_DISABLE_SLEEP_ENABLED, enabled ? 1 : 0);
                            } catch (Throwable t) {
                                log(Log.ERROR, TAG, "write KEY_DISABLE_SLEEP_ENABLED failed", t);
                            }
                        } else if (MainActivity.ACTION_FAKE_SCREEN_NOW.equals(action)) {
                            Log.d(TAG, "ACTION_FAKE_SCREEN_NOW received");

                            String mode = intent.getStringExtra("mode");

                            if ("screenoff".equals(mode)) {
                                toggleDisplayPower(cl, POWER_MODE_OFF);
                            } else if ("screenon".equals(mode)) {
                                toggleDisplayPower(cl, POWER_MODE_NORMAL);
                            } else {
                                toggleDisplayPower(cl);
                            }
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

    private void initScreenStateReader(Class<?> pwmClass) {
        // 1. 试 isScreenOn
        try {
            sIsScreenOnMethod = pwmClass.getDeclaredMethod("isScreenOn");
            sIsScreenOnMethod.setAccessible(true);
            sScreenStateMode = 1;
            Log.e("FakeScreen", "screen state: isScreenOn");
            return;
        } catch (Throwable ignored) {}

        // 2. 试 DisplayPolicy.mScreenOnEarly
        try {
            sPolicyField = pwmClass.getDeclaredField("mDefaultDisplayPolicy");
            sPolicyField.setAccessible(true);
            Class<?> policyClass = sPolicyField.getType();
            sScreenOnField = policyClass.getDeclaredField("mScreenOnEarly");
            sScreenOnField.setAccessible(true);
            sScreenStateMode = 2;
            Log.e("FakeScreen", "screen state: DisplayPolicy.mScreenOnEarly");
            return;
        } catch (Throwable ignored) {}

        // 3. 广播兜底
        sScreenStateMode = 3;
        Log.e("FakeScreen", "screen state: broadcast");
    }

    private boolean readScreenOn(Object pwmInstance) {
        try {
            if (sScreenStateMode == 1) {
                Object raw = sIsScreenOnMethod.invoke(pwmInstance);
                return (raw instanceof Boolean) ? (Boolean) raw : false;
            } else if (sScreenStateMode == 2) {
                Object policy = sPolicyField.get(pwmInstance);
                return sScreenOnField.getBoolean(policy);
            }
        } catch (Throwable t) {
            Log.e(TAG, "readScreenOn failed", t);
        }
        return false;
    }

    private void setupScreenStateTracker(ClassLoader classLoader, Context systemContext) {
        boolean hooked = false;

        try {
            Class<?> pwmClass = classLoader.loadClass("com.android.server.policy.PhoneWindowManager");

            // 初始化屏幕状态读取方式
            initScreenStateReader(pwmClass);

            Method interceptDown = null;
            for (Method m : pwmClass.getDeclaredMethods()) {
                if ("interceptPowerKeyDown".equals(m.getName())) {
                    interceptDown = m;
                    break;
                }
            }

            if (interceptDown != null) {
                interceptDown.setAccessible(true);
                hook(interceptDown).intercept(chain -> {
                    if (!sIsFakeScreenEnabled) {
                        return chain.proceed();
                    }
                    sScreenOnBeforePress = readScreenOn(chain.getThisObject());
                    Log.d(TAG, "interceptPowerKeyDown, before=" + sScreenOnBeforePress);
                    return chain.proceed();
                });
                hooked = true;
                Log.d(TAG, "hooked interceptPowerKeyDown");
            }
        } catch (Throwable t) {
            Log.e(TAG, "hook interceptPowerKeyDown failed", t);
        }

        if (!hooked) {
            // 广播兜底
            try {
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_SCREEN_OFF);
                filter.addAction(Intent.ACTION_SCREEN_ON);

                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                            sScreenOnBeforePress = false;
                            Log.d(TAG, "SCREEN_OFF broadcast");
                        } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                            sScreenOnBeforePress = true;
                            Log.d(TAG, "SCREEN_ON broadcast");
                        }
                    }
                };

                ContextCompat.registerReceiver(systemContext, receiver, filter,
                        ContextCompat.RECEIVER_EXPORTED);
                Log.d(TAG, "registered broadcast (hook failed)");
            } catch (Throwable t) {
                Log.e(TAG, "register broadcast failed", t);
            }
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
            Log.d(TAG, "powerPress method not found");
            return;
        }
        powerPress.setAccessible(true);

        hook(powerPress).intercept(chain -> {
            if (!sIsFakeScreenEnabled) {
                return chain.proceed();
            }

            final boolean screenOnAtPress = sScreenOnBeforePress;
            Log.d(TAG, "press, screenOnAtPress=" + screenOnAtPress);

            if (sIsFakeScreenEnabled && mCurrentPowerMode.get() == POWER_MODE_OFF) {
                resetUserActivityTimer();
            }

            long now = SystemClock.uptimeMillis();
            long delta = now - sLastPowerPressTime;
            sLastPowerPressTime = now;

            if (delta < DOUBLE_PRESS_INTERVAL_MS) {
                Log.d(TAG, "multi press, pass through");
                cancelPendingPowerTask();

                Object result = chain.proceed();

                mHandler.postDelayed(() -> {
                    boolean locked = sKeyguardManager != null
                            && sKeyguardManager.isKeyguardLocked();
                    Log.d(TAG, "after multi press, isKeyguardLocked=" + locked);

                    if (locked) {
                        mCurrentPowerMode.set(POWER_MODE_OFF);
                    }
                }, 1000);

                return result;
            }

            cancelPendingPowerTask();
            mPendingPowerTask = () -> {
                if (!screenOnAtPress) {
                    // 已锁屏 → 正常解锁
                    mCurrentPowerMode.set(POWER_MODE_NORMAL);
                } else {
                    // 未锁 → 按原逻辑翻转
                    toggleDisplayPower(classLoader);
                }

                mPendingPowerTask = null;
            };
            mHandler.postDelayed(mPendingPowerTask, MULTI_PRESS_TIMEOUT_MS);
            return null;
        });
    }

    /**
     * Toggles screen power mode using hardware-level SurfaceControl APIs.
     *
     * @param classLoader The ClassLoader used to resolve SurfaceControl and display tokens.
     */
    private void toggleDisplayPower(ClassLoader classLoader) {
        toggleDisplayPower(classLoader, -1);
    }
    private void toggleDisplayPower(ClassLoader classLoader, int mode) {
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

            int newMode;
            if (mode == -1) {
                int oldMode = mCurrentPowerMode.get();
                newMode = (oldMode == POWER_MODE_OFF) ? POWER_MODE_NORMAL : POWER_MODE_OFF;
            } else {
                newMode = mode;
            }
            if (mCurrentPowerMode.get() == newMode) {
                Log.d(TAG, "already in mode " + newMode + ", skip");
                return;
            }

            setDisplayPowerMode.invoke(null, displayBinder, newMode);
            Log.d(TAG, "newMode: " + newMode);
            mCurrentPowerMode.set(newMode);
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

    /**
     * Hook PowerManagerService.isBeingKeptAwakeLocked，当"禁用休眠"开关打开时
     * 强制返回 true，阻止系统进入休眠。
     *
     * @param classLoader system_server 的 ClassLoader
     */
    private void hookDisableSleep(ClassLoader classLoader) {
        try {
            Class<?> pmsClass = classLoader.loadClass("com.android.server.power.PowerManagerService");

            Method target = null;
            for (Method m : pmsClass.getDeclaredMethods()) {
                if ("isBeingKeptAwakeLocked".equals(m.getName())) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                log(Log.ERROR, TAG, "isBeingKeptAwakeLocked not found");
                return;
            }
            target.setAccessible(true);

            hook(target).intercept(chain -> {
                // 只有"开关打开"且"当前假熄屏"时，才强制不休眠
                if (sIsDisableSleepEnabled && mCurrentPowerMode.get() == POWER_MODE_OFF) {
                    return Boolean.TRUE;
                }
                return chain.proceed();
            });

            log(Log.INFO, TAG, "hookDisableSleep installed");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hookDisableSleep failed: " + Log.getStackTraceString(t));
        }
    }

    private void resetUserActivityTimer() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager)
                    sSystemContext.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                Method m = pm.getClass().getMethod("userActivity", long.class, boolean.class);
                m.setAccessible(true);
                m.invoke(pm, SystemClock.uptimeMillis(), false);
                Log.d(TAG, "userActivity called via reflection");
            }
        } catch (Throwable t) {
            Log.d(TAG, "userActivity reflection failed", t);
        }
    }
}