package com.example.fivegtile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;
    private static final String PREFS = "settings";

    private static final int BG = Color.rgb(6, 21, 46);
    private static final int CARD = Color.rgb(14, 41, 82);
    private static final int CARD_ALT = Color.rgb(11, 34, 70);
    private static final int CYAN = Color.rgb(44, 211, 255);
    private static final int BLUE = Color.rgb(32, 126, 255);
    private static final int TEXT = Color.WHITE;
    private static final int TEXT_MUTED = Color.rgb(166, 190, 225);
    private static final int GREEN = Color.rgb(50, 220, 145);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private TextView modeText;
    private Button switchButton;

    private final AtomicBoolean statusPending = new AtomicBoolean(false);
    private final AtomicBoolean testPending = new AtomicBoolean(false);
    private final AtomicBoolean switchPending = new AtomicBoolean(false);
    private volatile boolean requestPermissionWhenBinderArrives;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (requestPermissionWhenBinderArrives) {
            requestPermissionWhenBinderArrives = false;
            requestPermissionNow();
        }
        CommandBridge.warmUp(this);
        refreshStatus();
        refreshSwitchButton();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        refreshStatus();
        refreshSwitchButton();
    };

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                CommandBridge.warmUp(this);
                Toast.makeText(this, "Shizuku 授权成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Shizuku 授权未通过", Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
            refreshSwitchButton();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        CommandBridge.warmUp(this);
        refreshStatus();
        refreshSwitchButton();
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshSwitchButton();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setBackgroundColor(BG);

        LinearLayout hero = card(CARD);
        hero.setPadding(dp(20), dp(20), dp(20), dp(20));

        TextView badge = text("5G", 15, Typeface.BOLD, CYAN);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(Color.rgb(15, 69, 120), dp(16), CYAN, 1));
        hero.addView(badge, lp(dp(56), dp(34), 0, 0, 0, dp(14)));

        TextView title = text("5G Tile", 30, Typeface.BOLD, TEXT);
        hero.addView(title, lp(-1, -2, 0, 0, 0, dp(4)));

        TextView subtitle = text("快速切换 4G / 5G · Shizuku", 15, Typeface.NORMAL, TEXT_MUTED);
        hero.addView(subtitle, lp(-1, -2, 0, 0, 0, dp(10)));

        TextView version = text("v2.3 preview1 · Xiaomi 5G master switch", 12, Typeface.NORMAL, Color.rgb(104, 158, 220));
        hero.addView(version, lp(-1, -2, 0, 0, 0, 0));
        root.addView(hero, lp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout statusCard = card(CARD_ALT);
        statusCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView statusTitle = text("连接状态", 13, Typeface.BOLD, TEXT_MUTED);
        statusCard.addView(statusTitle, lp(-1, -2, 0, 0, 0, dp(7)));

        status = text("正在检查 Shizuku…", 17, Typeface.BOLD, TEXT);
        statusCard.addView(status, lp(-1, -2, 0, 0, 0, dp(12)));

        Button grant = secondaryButton("授权 / 重新连接 Shizuku");
        grant.setOnClickListener(v -> requestShizuku());
        statusCard.addView(grant, lp(-1, dp(48), 0, 0, 0, 0));
        root.addView(statusCard, lp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout simCard = card(CARD_ALT);
        simCard.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView simTitle = text("目标 SIM", 13, Typeface.BOLD, TEXT_MUTED);
        simCard.addView(simTitle, lp(-1, -2, 0, 0, 0, dp(8)));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);

        RadioButton sim1 = new RadioButton(this);
        sim1.setText("SIM 1");
        sim1.setTextColor(TEXT);
        sim1.setTextSize(16);
        sim1.setId(R.id.sim1);
        sim1.setButtonTintList(radioTint());

        RadioButton sim2 = new RadioButton(this);
        sim2.setText("SIM 2");
        sim2.setTextColor(TEXT);
        sim2.setTextSize(16);
        sim2.setId(R.id.sim2);
        sim2.setButtonTintList(radioTint());

        group.addView(sim1, new RadioGroup.LayoutParams(0, dp(48), 1f));
        group.addView(sim2, new RadioGroup.LayoutParams(0, dp(48), 1f));

        int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
        group.check(slot == 1 ? R.id.sim2 : R.id.sim1);
        group.setOnCheckedChangeListener((g, id) -> {
            int selected = id == R.id.sim2 ? 1 : 0;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("slot", selected).apply();
            requestTileRefresh();
            refreshSwitchButton();
        });

        simCard.addView(group, lp(-1, -2, 0, 0, 0, 0));
        root.addView(simCard, lp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout actionCard = card(CARD);
        actionCard.setPadding(dp(18), dp(18), dp(18), dp(18));

        TextView modeLabel = text("当前网络模式", 13, Typeface.BOLD, TEXT_MUTED);
        actionCard.addView(modeLabel, lp(-1, -2, 0, 0, 0, dp(5)));

        modeText = text("正在读取…", 24, Typeface.BOLD, TEXT);
        actionCard.addView(modeText, lp(-1, -2, 0, 0, 0, dp(14)));

        switchButton = primaryButton("读取当前网络状态…");
        switchButton.setEnabled(false);
        switchButton.setOnClickListener(v -> toggleNetworkMode());
        actionCard.addView(switchButton, lp(-1, dp(58), 0, 0, 0, 0));
        root.addView(actionCard, lp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);

        Button test = secondaryButton("完整自检");
        test.setOnClickListener(v -> testRead());
        tools.addView(test, new LinearLayout.LayoutParams(0, dp(52), 1f));

        View spacer = new View(this);
        tools.addView(spacer, new LinearLayout.LayoutParams(dp(10), 1));

        Button copy = secondaryButton("复制诊断");
        copy.setOnClickListener(v -> copyDiagnostics());
        tools.addView(copy, new LinearLayout.LayoutParams(0, dp(52), 1f));
        root.addView(tools, lp(-1, -2, 0, 0, 0, dp(14)));

        LinearLayout info = card(CARD_ALT);
        info.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView tip = text(
                "• 切换完成后会再次读取系统网络模式确认结果\n" +
                "• App 会同时切换小米 5G 主开关与 NR 网络模式\n" +
                "• 控制中心磁贴与 App 使用同一套切换逻辑",
                13, Typeface.NORMAL, TEXT_MUTED);
        tip.setLineSpacing(dp(2), 1.05f);
        info.addView(tip, lp(-1, -2, 0, 0, 0, 0));
        root.addView(info, lp(-1, -2, 0, 0, 0, 0));

        scroll.addView(root);
        return scroll;
    }

    private void copyDiagnostics() {
        String operation = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_operation", "还没有切换记录");
        String selfTest = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString("last_self_test", "还没有自检记录");
        String report = "5G Tile 2.3-preview1\n" + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT
                + ")\n\n" + operation + "\n" + selfTest;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("5G Tile 诊断", report));
        Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                requestPermissionWhenBinderArrives = true;
                setStatus("等待 Shizuku Binder…", TEXT_MUTED);
                Toast.makeText(this, "正在等待 Shizuku 连接", Toast.LENGTH_SHORT).show();
                return;
            }
            requestPermissionNow();
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku 连接失败: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            refreshStatus();
        }
    }

    private void requestPermissionNow() {
        try {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                CommandBridge.warmUp(this);
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "请在 Shizuku 的授权应用中允许 5G Tile", Toast.LENGTH_LONG).show();
                refreshStatus();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
            setStatus("正在请求 Shizuku 授权…", TEXT_MUTED);
        } catch (Throwable t) {
            Toast.makeText(this, "请求授权失败: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
            refreshStatus();
        }
    }

    private void refreshStatus() {
        runOnUiThread(() -> {
            if (status == null || isFinishing() || isDestroyed()) return;

            boolean binder = false;
            boolean granted = false;
            try {
                binder = Shizuku.pingBinder();
                if (binder) {
                    granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                }
            } catch (Throwable ignored) {}

            if (!binder) {
                setStatus("Shizuku 未连接", TEXT_MUTED);
                return;
            }
            if (!granted) {
                setStatus("Shizuku 已连接 · 等待授权", TEXT_MUTED);
                return;
            }

            setStatus("Shizuku 已授权 · 正在连接服务…", CYAN);
            if (!statusPending.compareAndSet(false, true) || executor.isShutdown()) return;
            executor.execute(() -> {
                try {
                    int uid = CommandBridge.remoteUid(this);
                    runOnUiThread(() -> {
                        if (status != null && !isFinishing() && !isDestroyed()) {
                            setStatus(uid == 2000
                                    ? "Shizuku 已准备就绪"
                                    : "UserService 已连接 · uid=" + uid, GREEN);
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> setStatus("后台服务连接失败", Color.rgb(255, 186, 90)));
                } finally {
                    statusPending.set(false);
                }
            });
        });
    }

    private void testRead() {
        if (!testPending.compareAndSet(false, true) || executor.isShutdown()) return;
        executor.execute(() -> {
            long started = SystemClock.elapsedRealtime();
            try {
                int uid = CommandBridge.remoteUid(this);
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                long deadline = SystemClock.elapsedRealtime() + 4000;
                String result = CommandBridge.exec(this, NetworkCommands.get(slot), deadline);
                String master = CommandBridge.exec(this, NetworkCommands.getXiaomiFiveGSwitch(), deadline);
                boolean nr = NetworkCommands.isEffective5gEnabled(result, master);
                String report = "自检：SIM " + (slot + 1) + " / uid=" + uid + "\n"
                        + "网络类型：" + result + "\n"
                        + "fiveg_user_enable：" + master + "\n"
                        + "实际状态：" + (nr ? "5G 已开启" : "5G 未开启") + "\n"
                        + "耗时：" + (SystemClock.elapsedRealtime() - started) + " ms";
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_self_test", report).apply();
                runOnUiThread(() -> {
                    modeText.setText(nr ? "5G 已开启" : "4G 模式");
                    Toast.makeText(this,
                            (nr ? "当前 5G 已开启\n" : "当前 5G 未开启\n") + "fiveg_user_enable=" + master + "\n" + result,
                            Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_self_test", "自检失败：" + t.getMessage()
                                + "\n耗时：" + (SystemClock.elapsedRealtime() - started) + " ms").apply();
                runOnUiThread(() -> Toast.makeText(this,
                        t.getMessage() == null ? "自检失败" : "自检失败：" + t.getMessage(),
                        Toast.LENGTH_LONG).show());
            } finally {
                testPending.set(false);
            }
        });
    }

    private void refreshSwitchButton() {
        if (switchButton == null || executor.isShutdown() || switchPending.get()) return;
        executor.execute(() -> {
            try {
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                long deadline = SystemClock.elapsedRealtime() + 4000;
                String result = CommandBridge.exec(this, NetworkCommands.get(slot), deadline);
                String master = CommandBridge.exec(this, NetworkCommands.getXiaomiFiveGSwitch(), deadline);
                boolean nr = NetworkCommands.isEffective5gEnabled(result, master);
                runOnUiThread(() -> {
                    if (switchButton != null && !isFinishing() && !isDestroyed() && !switchPending.get()) {
                        switchButton.setEnabled(true);
                        switchButton.setText(nr ? "切换到 4G" : "切换到 5G");
                        modeText.setText(nr ? "5G 已开启" : "4G 模式");
                    }
                });
            } catch (Throwable t) {
                runOnUiThread(() -> {
                    if (switchButton != null && !isFinishing() && !isDestroyed() && !switchPending.get()) {
                        switchButton.setEnabled(CommandBridge.isShizukuReady());
                        switchButton.setText(CommandBridge.isShizukuReady() ? "重试读取状态" : "需要 Shizuku");
                        modeText.setText("状态不可用");
                    }
                });
            }
        });
    }

    private void toggleNetworkMode() {
        if (!switchPending.compareAndSet(false, true) || executor.isShutdown()) return;
        final int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
        final long started = SystemClock.elapsedRealtime();
        switchButton.setEnabled(false);
        switchButton.setText("正在切换…");

        executor.execute(() -> {
            String stage = "连接服务";
            StringBuilder diagnostic = new StringBuilder("2.3-preview1 App按钮 / SIM ")
                    .append(slot + 1).append("\n").append(new java.util.Date()).append('\n');
            try {
                long deadline = started + 8000;
                if (!CommandBridge.awaitReady(this, 3000)) {
                    throw new IllegalStateException(CommandBridge.isShizukuReady()
                            ? "后台服务连接超时，请重试"
                            : "Shizuku 未运行或未授权，请先完成授权");
                }
                diagnostic.append("连接完成：")
                        .append(SystemClock.elapsedRealtime() - started).append(" ms\n");

                stage = "读取系统 5G 状态";
                String before = CommandBridge.exec(this, NetworkCommands.get(slot), deadline);
                String beforeMaster = CommandBridge.exec(this, NetworkCommands.getXiaomiFiveGSwitch(), deadline);
                long beforeMask = NetworkCommands.parseMask(before);
                boolean target5g = !NetworkCommands.isEffective5gEnabled(before, beforeMaster);
                diagnostic.append("读取完成：")
                        .append(SystemClock.elapsedRealtime() - started).append(" ms\n")
                        .append("切换前网络：").append(before).append('\n')
                        .append("切换前主开关：").append(beforeMaster).append('\n');

                stage = "写入小米 5G 主开关";
                CommandBridge.exec(this, NetworkCommands.setXiaomiFiveGSwitch(target5g), deadline);
                stage = "写入网络模式";
                CommandBridge.exec(this, NetworkCommands.set5g(slot, beforeMask, target5g), deadline);
                diagnostic.append("写入完成：")
                        .append(SystemClock.elapsedRealtime() - started).append(" ms\n");

                stage = "确认系统设置";
                long verifyUntil = Math.min(deadline, SystemClock.elapsedRealtime() + 2000);
                boolean confirmed = false;
                String after = "";
                String afterMaster = "";
                do {
                    after = CommandBridge.exec(this, NetworkCommands.get(slot), deadline);
                    afterMaster = CommandBridge.exec(this, NetworkCommands.getXiaomiFiveGSwitch(), deadline);
                    if (NetworkCommands.isEffective5gEnabled(after, afterMaster) == target5g) {
                        confirmed = true;
                        break;
                    }
                    long remaining = verifyUntil - SystemClock.elapsedRealtime();
                    if (remaining <= 0) break;
                    Thread.sleep(Math.min(150, remaining));
                } while (SystemClock.elapsedRealtime() < verifyUntil);

                diagnostic.append("切换后网络：").append(after).append('\n')
                        .append("切换后主开关：").append(afterMaster).append('\n');
                if (!confirmed) throw new IllegalStateException("系统尚未确认切换");
                diagnostic.append("确认完成：")
                        .append(SystemClock.elapsedRealtime() - started).append(" ms\n");

                boolean finalTarget5g = target5g;
                runOnUiThread(() -> {
                    if (switchButton != null && !isFinishing() && !isDestroyed()) {
                        switchButton.setText(finalTarget5g ? "切换到 4G" : "切换到 5G");
                        switchButton.setEnabled(true);
                        modeText.setText(finalTarget5g ? "5G 已开启" : "4G 模式");
                    }
                    Toast.makeText(this,
                            finalTarget5g ? "5G 已开启" : "5G 已关闭",
                            Toast.LENGTH_SHORT).show();
                });
                requestTileRefresh();
            } catch (Throwable t) {
                if (t instanceof InterruptedException) Thread.currentThread().interrupt();
                String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                diagnostic.append("失败阶段：").append(stage).append("\n原因：")
                        .append(message).append('\n');
                runOnUiThread(() -> {
                    if (switchButton != null && !isFinishing() && !isDestroyed()) {
                        switchButton.setText("重试切换");
                        switchButton.setEnabled(true);
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                diagnostic.append("总耗时：")
                        .append(SystemClock.elapsedRealtime() - started).append(" ms\n");
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_operation", diagnostic.toString()).apply();
                switchPending.set(false);
            }
        });
    }

    private void requestTileRefresh() {
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, FiveGTileService.class));
        } catch (Throwable ignored) {}
    }

    private void setStatus(String value, int color) {
        if (status == null) return;
        status.setText(value);
        status.setTextColor(color);
    }

    private LinearLayout card(int color) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setBackground(rounded(color, dp(20), Color.rgb(24, 65, 118), 1));
        return v;
    }

    private Button primaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setBackground(gradientButton());
        b.setStateListAnimator(null);
        return b;
    }

    private Button secondaryButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.rgb(214, 232, 255));
        b.setAllCaps(false);
        b.setBackground(rounded(Color.rgb(17, 52, 98), dp(15), Color.rgb(35, 85, 145), 1));
        b.setStateListAnimator(null);
        return b;
    }

    private TextView text(String value, float size, int style, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTypeface(Typeface.DEFAULT, style);
        t.setTextColor(color);
        return t;
    }

    private ColorStateList radioTint() {
        int[][] states = new int[][] {
                new int[] { android.R.attr.state_checked },
                new int[] {}
        };
        int[] colors = new int[] { CYAN, Color.rgb(105, 139, 184) };
        return new ColorStateList(states, colors);
    }

    private GradientDrawable rounded(int color, float radius, int strokeColor, int strokeWidthDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (strokeWidthDp > 0) d.setStroke(dp(strokeWidthDp), strokeColor);
        return d;
    }

    private GradientDrawable gradientButton() {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] { BLUE, CYAN });
        d.setCornerRadius(dp(18));
        return d;
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
