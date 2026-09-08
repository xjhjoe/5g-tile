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

public class FiveGTileService extends TileService {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    @Override
    public void onStartListening() {
        super.onStartListening();
        refreshAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!BUSY.compareAndSet(false, true)) return;

        if (!ShizukuShell.isReady()) {
            BUSY.set(false);
            setUnavailable("需要 Shizuku");
            Toast.makeText(this, "请打开 5G 切换并授权 Shizuku", Toast.LENGTH_SHORT).show();
            openSetup();
            return;
        }

        setWorking();
        EXECUTOR.execute(() -> {
            try {
                int slot = getPreferences(MODE_PRIVATE).getInt("slot", 0);
                String before = ShizukuShell.exec(NetworkCommands.get(slot));
                boolean enable5g = !NetworkCommands.hasNr(before);
                ShizukuShell.exec(NetworkCommands.set5g(slot, enable5g));
                String after = ShizukuShell.exec(NetworkCommands.get(slot));
                boolean nr = NetworkCommands.hasNr(after);
                runOnUiThread(() -> updateTile(nr, true));
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setUnavailable("切换失败");
                    Toast.makeText(this, t.getMessage() == null ? "5G 切换失败" : t.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                BUSY.set(false);
            }
        });
    }

    private void refreshAsync() {
        if (!ShizukuShell.isReady()) {
            setUnavailable("需要 Shizuku");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                int slot = getPreferences(MODE_PRIVATE).getInt("slot", 0);
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

    private void setWorking() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_INACTIVE);
        tile.setLabel("正在切换...");
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
