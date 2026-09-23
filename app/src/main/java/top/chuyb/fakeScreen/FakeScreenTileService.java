package top.chuyb.fakeScreen;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import androidx.annotation.RequiresApi;

/**
 * 快速设置磁贴：一键切换"假熄屏"开关。
 * 通过发送广播与 system_server 中的 MainHook 同步状态。
 */

public class FakeScreenTileService extends TileService {

    private static final String TAG = "FakeScreen";

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Tile onCreate");
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        Log.d(TAG, "Tile onStartListening");
        updateTileState();
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTileState();
    }

    @Override
    public void onClick() {
        super.onClick();
        Tile tile = getQsTile();
        if (tile == null) return;

        // 读取当前状态并取反
        int currentState = tile.getState();
        boolean newEnabled = (currentState != Tile.STATE_ACTIVE);

        // 发送广播给 system_server 的 MainHook
        Intent intent = new Intent(MainActivity.ACTION_TOGGLE_FAKE_SCREEN);
        intent.putExtra("enabled", newEnabled);
        sendBroadcast(intent);

        // 立即刷新磁贴显示
        tile.setState(newEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    /**
     * 从 Settings.Global 读取当前开关状态并刷新磁贴。
     */
    private void updateTileState() {
        Tile tile = getQsTile();
        if (tile == null) return;

        boolean enabled = false;
        try {
            enabled = android.provider.Settings.Global.getInt(
                    getContentResolver(),
                    MainActivity.KEY_FAKE_SCREEN_ENABLED, 0) == 1;
        } catch (Throwable t) {
            Log.e(TAG, "Tile: failed to read Settings.Global", t);
        }
        Log.d(TAG, "Tile updateTileState: enabled=" + enabled
                + " -> setState=" + (enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE));
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}