package top.chuyb.fakeScreen;

import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Window;
import android.widget.LinearLayout;
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
        initGithubButton();
        initTipsButton();
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
}