package com.example.fivegtile;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public class FiveGTileService extends TileService {
    // A slow status query must never sit in front of a user's tap.
    private static final ExecutorService CLICK_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final ExecutorService REFRESH_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final AtomicBoolean REFRESH_PENDING = new AtomicBoolean(false);
    private static final String PREFS = "settings";
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private volatile int generation;
    private volatile boolean destroyed;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        CommandBridge.warmUp(this);
        refreshAsync();
    };
    private final Shizuku.OnBinderDeadListener binderDeadListener = () ->
            main.post(() -> {
                if (!destroyed && !BUSY.get()) setRetryable("需要 Shizuku");
            });

    @Override
    public void onCreate() {
        super.onCreate();
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        CommandBridge.warmUp(this);
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        generation++;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        super.onDestroy();
    }

    @Override
    public void onStartListening() {
        super.onStartListening();
        if (BUSY.get()) setWorking("正在切换…");
        else refreshAsync();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (!BUSY.compareAndSet(false, true)) return;
        final int request = ++generation;
        final int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
        final long started = SystemClock.elapsedRealtime();
        setWorking("连接中…");

        CLICK_EXECUTOR.execute(() -> {
            String stage = "连接服务";
            StringBuilder diagnostic = new StringBuilder("2.2-final / SIM ")
                    .append(slot + 1).append("\n").append(new java.util.Date()).append('\n');
            try {
                long deadline = started + 8000;
                if (!CommandBridge.awaitReady(this, 3000)) {
                    throw new IllegalStateException(CommandBridge.isShizukuReady()
                            ? "后台服务连接超时，请点按重试"
                            : "Shizuku 未运行或未授权，请打开应用检查");
                }
                mark(diagnostic, "连接完成", started);
                postCurrent(request, () -> setWorking("正在切换…"));
                stage = "读取网络类型";
                String before = CommandBridge.exec(this, NetworkCommands.get(slot), deadline);
                long beforeMask = NetworkCommands.parseMask(before);
                boolean target5g = (beforeMask & NetworkCommands.NR_BIT) == 0;
                mark(diagnostic, "读取完成", started);
                diagnostic.append("切换前：").append(before).append('\n');

                stage = "写入网络模式";
                // Explicit target is idempotent if the remote process dies mid-call.
                CommandBridge.exec(this, NetworkCommands.set5g(slot, beforeMask, target5g), deadline);
                mark(diagnostic, "写入完成", started);

                stage = "确认系统设置";
                long verifyUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 2000);
                boolean confirmed = false;
                String after = "";
                do {
                    after = CommandBridge.exec(this, NetworkCommands.get(slot), deadline);
                    if (NetworkCommands.hasNr(after) == target5g) {
                        confirmed = true;
                        break;
                    }
                    long remaining = verifyUntil - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    Thread.sleep(Math.min(150, remaining));
                } while (SystemClock.elapsedRealtime() < verifyUntil);

                diagnostic.append("切换后：").append(after).append('\n');
                if (!confirmed) {
                    throw new IllegalStateException("系统尚未确认切换；请检查 SIM 或系统网络设置");
                }
                mark(diagnostic, "确认完成", started);
                postCurrent(request, () -> updateTile(target5g));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                diagnostic.append("失败阶段：").append(stage).append("\n原因：")
                        .append(message).append('\n');
                postCurrent(request, () -> {
                    setRetryable("点按重试");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    if (!CommandBridge.isShizukuReady()) openSetup();
                });
            } finally {
                mark(diagnostic, "总耗时", started);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_operation", diagnostic.toString()).apply();
                BUSY.set(false);
                // A replacement TileService must not retain the previous instance's busy label.
                if (destroyed) requestRefresh();
            }
        });
    }

    private void refreshAsync() {
        if (destroyed || BUSY.get() || !REFRESH_PENDING.compareAndSet(false, true)) return;
        final int request = generation;
        final int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
        REFRESH_EXECUTOR.execute(() -> {
            try {
                if (BUSY.get() || destroyed) return;
                String result = CommandBridge.exec(this, NetworkCommands.get(slot),
                        SystemClock.elapsedRealtime() + 3000);
                boolean nr = NetworkCommands.hasNr(result);
                main.post(() -> {
                    if (!destroyed && request == generation && !BUSY.get()
                            && slot == getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0)) {
                        updateTile(nr);
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (!destroyed && request == generation && !BUSY.get()) {
                        setRetryable(CommandBridge.isShizukuReady() ? "点按重试" : "需要 Shizuku");
                    }
                });
            } finally {
                REFRESH_PENDING.set(false);
                if (destroyed) requestRefresh();
            }
        });
    }

    private void updateTile(boolean nrEnabled) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(nrEnabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(nrEnabled ? "5G 已允许" : "5G 已关闭");
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(nrEnabled ? "点按关闭 5G" : "点按允许 5G");
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

    private void setRetryable(String label) {
        Tile tile = getQsTile();
        if (tile == null) return;
        // STATE_UNAVAILABLE disables onClick, making "tap to retry" impossible.
        tile.setState(Tile.STATE_INACTIVE);
        tile.setLabel(label);
        if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("点按重试 · 长按打开设置");
        tile.updateTile();
    }

    private void postCurrent(int request, Runnable action) {
        main.post(() -> {
            if (!destroyed && request == generation) action.run();
        });
    }

    private static void mark(StringBuilder log, String label, long started) {
        log.append(label).append("：").append(SystemClock.elapsedRealtime() - started)
                .append(" ms\n");
    }

    private void requestRefresh() {
        try {
            requestListeningState(getApplicationContext(),
                    new ComponentName(this, FiveGTileService.class));
        } catch (RuntimeException ignored) {}
    }

    // The PendingIntent overload exists only on API 34+. The legacy call below
    // is reachable only on older Android, where that overload is unavailable.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private void openSetup() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                PendingIntent pi = PendingIntent.getActivity(this, 100, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pi);
            } else {
                //noinspection deprecation
                startActivityAndCollapse(intent);
            }
        } catch (RuntimeException ignored) {}
    }
}
