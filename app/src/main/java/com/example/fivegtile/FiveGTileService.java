package com.example.fivegtile;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
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

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        CommandBridge.warmUp(this);
        refreshAsync();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () ->
            runOnUiThread(() -> setUnavailable("Shizuku 已断开"));

    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        CommandBridge.warmUp(this);
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

        setWorking("连接中...");
        EXECUTOR.execute(() -> {
            try {
                // The app process may have just been recreated after Xiaomi/Android
                // background cleanup. The privileged UserService itself is daemonized
                // by Shizuku, so reconnect instead of relying on any cached Binder.
                if (!CommandBridge.awaitReady(this, 6500)) {
                    boolean shizukuReady = CommandBridge.isShizukuReady();
                    runOnUiThread(() -> {
                        if (shizukuReady) {
                            setUnavailable("连接超时");
                            Toast.makeText(this,
                                    "Shizuku 正常，但后台 shell 服务暂未连接；请再点一次",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            setUnavailable("需要 Shizuku");
                            Toast.makeText(this,
                                    "请确认 Shizuku 正在运行并已授权 5G 切换",
                                    Toast.LENGTH_LONG).show();
                            openSetup();
                        }
                    });
                    return;
                }

                runOnUiThread(() -> setWorking("正在切换..."));
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                String before = CommandBridge.exec(this, NetworkCommands.get(slot));
                boolean target5g = !NetworkCommands.hasNr(before);

                CommandBridge.exec(this, NetworkCommands.set5g(slot, target5g));
                SystemClock.sleep(250);
                String after = CommandBridge.exec(this, NetworkCommands.get(slot));

                // One automatic retry covers transient Telephony state changes.
                if (NetworkCommands.hasNr(after) != target5g) {
                    CommandBridge.exec(this, NetworkCommands.set5g(slot, target5g));
                    SystemClock.sleep(350);
                    after = CommandBridge.exec(this, NetworkCommands.get(slot));
                }

                boolean nr = NetworkCommands.hasNr(after);
                if (nr != target5g) {
                    throw new IllegalStateException("Telephony 未应用目标网络模式");
                }

                runOnUiThread(() -> updateTile(nr));
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    setUnavailable("切换失败");
                    Toast.makeText(this,
                            readableError(t),
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
                if (!CommandBridge.awaitReady(this, 4500)) {
                    runOnUiThread(() -> setUnavailable(
                            CommandBridge.isShizukuReady() ? "点击重连" : "需要 Shizuku"));
                    return;
                }
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                String value = CommandBridge.exec(this, NetworkCommands.get(slot));
                boolean nr = NetworkCommands.hasNr(value);
                runOnUiThread(() -> updateTile(nr));
            } catch (Throwable ignored) {
                runOnUiThread(() -> setUnavailable("点击重试"));
            }
        });
    }

    private void updateTile(boolean nrEnabled) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(nrEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
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
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(null);
        tile.updateTile();
    }

    private void setUnavailable(String label) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(Tile.STATE_UNAVAILABLE);
        tile.setLabel(label);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle(null);
        tile.updateTile();
    }

    private String readableError(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String msg = current.getMessage();
        return msg == null || msg.trim().isEmpty()
                ? "5G 切换失败：" + current.getClass().getSimpleName()
                : "5G 切换失败：" + msg;
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
