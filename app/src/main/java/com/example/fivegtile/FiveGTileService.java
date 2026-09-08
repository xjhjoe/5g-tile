package com.example.fivegtile;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public class FiveGTileService extends TileService {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final String PREFS = "settings";

    // After the app process is killed, Shizuku sends its binder to the new
    // process asynchronously. A Quick Settings tile can be started before that
    // hand-off finishes, so never treat the first pingBinder() == false as a
    // permanent failure.
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (ShizukuShell.isReady()) {
            refreshAsync();
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () ->
            runOnUiThread(() -> setUnavailable("Shizuku 已断开"));

    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    @Override
    public void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        super.onDestroy();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!BUSY.compareAndSet(false, true)) return;

        setWorking("等待 Shizuku...");
        EXECUTOR.execute(() -> {
            try {
                // Important for process-restart recovery: give Shizuku a short
                // window to inject/re-deliver its binder to this fresh process.
                if (!ShizukuShell.awaitReady(3500)) {
                    runOnUiThread(() -> {
                        setUnavailable("需要 Shizuku");
                        Toast.makeText(this,
                                "Shizuku 正在运行但本 App 尚未重新连接，请打开 5G 切换一次",
                                Toast.LENGTH_LONG).show();
                        openSetup();
                    });
                    return;
                }

                runOnUiThread(() -> setWorking("正在切换..."));
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                String before = ShizukuShell.exec(NetworkCommands.get(slot));
                boolean enable5g = !NetworkCommands.hasNr(before);
                ShizukuShell.exec(NetworkCommands.set5g(slot, enable5g));
                String after = ShizukuShell.exec(NetworkCommands.get(slot));
                boolean nr = NetworkCommands.hasNr(after);
                runOnUiThread(() -> updateTile(nr, true));
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setUnavailable("切换失败");
                    Toast.makeText(this,
                            t.getMessage() == null ? "5G 切换失败" : t.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            } finally {
                BUSY.set(false);
            }
        });
    }

    private void refreshAsync() {
        EXECUTOR.execute(() -> {
            try {
                // Do not instantly mark the tile dead after process recreation.
                // The binder delivery is asynchronous and normally arrives very
                // quickly once the TileService process exists.
                if (!ShizukuShell.awaitReady(2500)) {
                    runOnUiThread(() -> setUnavailable("等待 Shizuku"));
                    return;
                }
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                String value = ShizukuShell.exec(NetworkCommands.get(slot));
                boolean nr = NetworkCommands.hasNr(value);
                runOnUiThread(() -> updateTile(nr, true));
            } catch (Throwable ignored) {
                runOnUiThread(() -> setUnavailable("点击重试"));
            }
        });
    }

    private void updateTile(boolean nrEnabled, boolean available) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(available ? (nrEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE) : Tile.STATE_UNAVAILABLE);
        tile.setLabel(nrEnabled ? "5G 已开启" : "5G 已关闭");
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(nrEnabled ? "点按切到 4G" : "点按切到 5G");
        }
        tile.updateTile();
    }

    private void setWorking(String label) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.setLabel(label);
        tile.updateTile();
    }

    private void setUnavailable(String label) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_UNAVAILABLE);
        tile.setLabel(label);
        tile.updateTile();
    }

    private void openSetup() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(
                        this, 100, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                //noinspection deprecation
                startActivityAndCollapse(intent);
            }
        } catch (Throwable ignored) {
        }
    }

    private void runOnUiThread(Runnable r) {
        new android.os.Handler(getMainLooper()).post(r);
    }
}
