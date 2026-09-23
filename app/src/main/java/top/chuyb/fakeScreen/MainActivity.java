package top.chuyb.fakeScreen;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.WindowCompat;

/**
 * Main activity for user interface configuration and status toggling.
 * @since 1.0
 * @author chuyb-re
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "FakeScreen";

    public static final String KEY_FAKE_SCREEN_ENABLED = "top_chuyb_fakeScreen_enabled";
    public static final String ACTION_TOGGLE_FAKE_SCREEN = "top.chuyb.fakeScreen.ACTION_TOGGLE";
    public static final String KEY_DISABLE_SLEEP_ENABLED = "top_chuyb_fakeScreen_disable_sleep";
    public static final String ACTION_TOGGLE_DISABLE_SLEEP = "top.chuyb.fakeScreen.ACTION_TOGGLE_DISABLE_SLEEP";
    public static final String ACTION_FAKE_SCREEN_NOW = "top.chuyb.fakeScreen.ACTION_FAKE_SCREEN_NOW";
    private static final String KEY_HIDE_ICON = "top_chuyb_fakeScreen_hide_icon";

    /**
     * Initializes activity layout, window elements, and UI click listeners.
     *
     * @param savedInstanceState Bundle containing activity's previously saved state, if any.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setupEdgeToEdgeWindow();
        setContentView(R.layout.activity_main);

        initSwitchButton();
        initDisableSleepSwitch();
        initShellCommandCopy();
        initShellNowCard();
        initHideIconSwitch();
        initGithubButton();
        initTipsButton();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            refreshSwitches();
        }
    }

    /**
     * Configures edge-to-edge window drawing, transparent status bar, and light bar icons.
     */
    private void setupEdgeToEdgeWindow() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.getDecorView().setBackgroundColor(Color.parseColor("#F8F9FA"));

        WindowCompat.getInsetsController(window, window.getDecorView())
                .setAppearanceLightStatusBars(true);
    }

    /**
     * Binds the main switch widget, reads global setting state, and broadcasts state toggles.
     */
    private void initSwitchButton() {
        SwitchCompat switchBtn = findViewById(R.id.switch_fake_screen);
        if (switchBtn == null) return;

        int currentState = 0;
        try {
            currentState = Settings.Global.getInt(getContentResolver(), KEY_FAKE_SCREEN_ENABLED, 0);
        } catch (Throwable e) {
            Log.e(TAG, "Failed to read Settings.Global state", e);
        }
        switchBtn.setChecked(currentState == 1);

        switchBtn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Intent intent = new Intent(ACTION_TOGGLE_FAKE_SCREEN);
            intent.putExtra("enabled", isChecked);
            sendBroadcast(intent);
            TileService.requestListeningState(this,
                    new ComponentName(getPackageName(), FakeScreenTileService.class.getName()));
        });
    }

    private void initDisableSleepSwitch() {
        SwitchCompat switchBtn = findViewById(R.id.switch_disable_sleep);
        if (switchBtn == null) return;

        boolean enabled = false;
        try {
            enabled = Settings.Global.getInt(getContentResolver(),
                    KEY_DISABLE_SLEEP_ENABLED, 0) == 1;
        } catch (Throwable e) {
            Log.e(TAG, "read disable_sleep failed", e);
        }
        switchBtn.setChecked(enabled);

        switchBtn.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Intent intent = new Intent(ACTION_TOGGLE_DISABLE_SLEEP);
            intent.putExtra("enabled", isChecked);
            sendBroadcast(intent);

            // 顺便同步磁贴状态（如果你想让磁贴也反映这个开关）
            TileService.requestListeningState(this,
                        new ComponentName(getPackageName(), FakeScreenTileService.class.getName()));
        });
    }

    /**
     * Binds the GitHub link button click listener to open repository URL in browser.
     */
    private void initGithubButton() {
        LinearLayout btnGithub = findViewById(R.id.btn_github);
        if (btnGithub == null) return;

        btnGithub.setOnClickListener(v -> {
            try {
                String url = getString(R.string.github_repo_url);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Unable to open browser", Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * Binds the usage tips button click listener to show instruction dialog.
     */
    private void initTipsButton() {
        LinearLayout btnTips = findViewById(R.id.btn_tips);
        if (btnTips == null) return;

        btnTips.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.dialog_tips_title)
                    .setMessage(R.string.tip)
                    .setPositiveButton(R.string.dialog_positive_btn, null)
                    .show();
        });
    }

    private void refreshSwitches() {
        // 假熄屏开关
        SwitchCompat fake = findViewById(R.id.switch_fake_screen);
        if (fake != null) {
            boolean enabled = false;
            try {
                enabled = Settings.Global.getInt(getContentResolver(),
                        KEY_FAKE_SCREEN_ENABLED, 0) == 1;
            } catch (Throwable ignored) {}
            fake.setOnCheckedChangeListener(null);   // 先解绑，避免误发广播
            fake.setChecked(enabled);
            fake.setOnCheckedChangeListener((v, checked) -> {
                Intent i = new Intent(ACTION_TOGGLE_FAKE_SCREEN);
                i.putExtra("enabled", checked);
                sendBroadcast(i);
            });
        }

        // 禁用休眠开关
        SwitchCompat sleep = findViewById(R.id.switch_disable_sleep);
        if (sleep != null) {
            boolean enabled = false;
            try {
                enabled = Settings.Global.getInt(getContentResolver(),
                        KEY_DISABLE_SLEEP_ENABLED, 0) == 1;
            } catch (Throwable ignored) {}
            sleep.setOnCheckedChangeListener(null);
            sleep.setChecked(enabled);
            sleep.setOnCheckedChangeListener((v, checked) -> {
                Intent i = new Intent(ACTION_TOGGLE_DISABLE_SLEEP);
                i.putExtra("enabled", checked);
                sendBroadcast(i);
            });
        }
    }

    /**
     * 绑定 Shell 命令卡片，点击复制命令到剪贴板。
     */
    private void initShellCommandCopy() {
        TextView tvFake = findViewById(R.id.tv_shell_cmd_fake_screen);
        TextView tvSleep = findViewById(R.id.tv_shell_cmd_disable_sleep);

        if (tvFake != null) {
            tvFake.setOnClickListener(v -> copyToClipboard(tvFake.getText().toString()));
        }
        if (tvSleep != null) {
            tvSleep.setOnClickListener(v -> copyToClipboard(tvSleep.getText().toString()));
        }
    }

    private void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("shell", text));
        Toast.makeText(this, R.string.copy_success, Toast.LENGTH_SHORT).show();
    }

    private void initShellNowCard() {
        LinearLayout header = findViewById(R.id.header_shell);
        LinearLayout content = findViewById(R.id.shell_content);
        ImageView arrow = findViewById(R.id.iv_expand);
        TextView off = findViewById(R.id.tv_cmd_off);
        TextView on = findViewById(R.id.tv_cmd_on);
        TextView toggle = findViewById(R.id.tv_cmd_toggle);

        if (header != null && content != null && arrow != null) {
            header.setOnClickListener(v -> {
                boolean expanded = content.getVisibility() == View.VISIBLE;
                content.setVisibility(expanded ? View.GONE : View.VISIBLE);
                // 收起时朝下，展开时朝上
                arrow.setImageResource(expanded
                        ? android.R.drawable.arrow_down_float
                        : android.R.drawable.arrow_up_float);
            });
        }

        // 复用 copyToClipboard
        if (off != null) off.setOnClickListener(v ->
                copyToClipboard(off.getText().toString()));
        if (on != null) on.setOnClickListener(v ->
                copyToClipboard(on.getText().toString()));
        if (toggle != null) toggle.setOnClickListener(v ->
                copyToClipboard(toggle.getText().toString()));
    }

    private void initHideIconSwitch() {
        SwitchCompat sw = findViewById(R.id.switch_hide_icon);
        if (sw == null) return;

        SharedPreferences sp = getSharedPreferences("settings", MODE_PRIVATE);
        boolean hidden = sp.getBoolean(KEY_HIDE_ICON, false);
        sw.setChecked(hidden);

        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setLauncherIconVisible(!isChecked);
            sp.edit().putBoolean(KEY_HIDE_ICON, isChecked).apply();
            Toast.makeText(this,
                    isChecked ? R.string.hide_icon_toast_on : R.string.hide_icon_toast_off,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void setLauncherIconVisible(boolean visible) {
        ComponentName alias = new ComponentName(this, "top.chuyb.fakeScreen.LauncherAlias");
        int newState = visible
                ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        getPackageManager().setComponentEnabledSetting(alias, newState,
                PackageManager.DONT_KILL_APP);
    }
}