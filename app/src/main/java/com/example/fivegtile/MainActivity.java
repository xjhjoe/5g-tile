package com.example.fivegtile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;
    private final AtomicBoolean statusPending = new AtomicBoolean(false);
    private final AtomicBoolean testPending = new AtomicBoolean(false);
    private volatile boolean requestPermissionWhenBinderArrives;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        if (requestPermissionWhenBinderArrives) {
            requestPermissionWhenBinderArrives = false;
            requestPermissionNow();
        }
        CommandBridge.warmUp(this);
        refreshStatus();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshStatus;

    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                CommandBridge.warmUp(this);
                Toast.makeText(this, "Shizuku 授权成功", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Shizuku 授权未通过", Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
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
    }

    private View buildUi() {
        int pad = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(36), pad, pad);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFFFAF7F2);

        TextView title = new TextView(this);
        title.setText("5G 快捷切换");
        title.setTextSize(30);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF2B2723);
        root.addView(title, lp(-1, -2, 0, 0, 0, dp(8)));

        TextView desc = new TextView(this);
        desc.setText("2.2-preview2：点按切换当前 SIM 是否允许 5G。磁贴显示的是网络设置，实际 5G 信号仍取决于覆盖和运营商。使用前请保持 Shizuku 运行并完成授权。");
        desc.setTextSize(16);
        desc.setTextColor(0xFF5A5149);
        desc.setLineSpacing(0, 1.15f);
        root.addView(desc, lp(-1, -2, 0, 0, 0, dp(22)));

        status = new TextView(this);
        status.setText("状态：检查中...");
        status.setTextSize(17);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        status.setBackgroundColor(0xFFFFE0B2);
        status.setTextColor(0xFF3D2F22);
        root.addView(status, lp(-1, -2, 0, 0, 0, dp(12)));

        Button grant = new Button(this);
        grant.setText("授权 / 重新连接 Shizuku");
        grant.setOnClickListener(v -> requestShizuku());
        root.addView(grant, lp(-1, -2, 0, 0, 0, dp(20)));

        TextView simTitle = new TextView(this);
        simTitle.setText("目标 SIM");
        simTitle.setTextSize(18);
        simTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        simTitle.setTextColor(0xFF2B2723);
        root.addView(simTitle, lp(-1, -2, 0, 0, 0, dp(6)));

        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.HORIZONTAL);
        RadioButton sim1 = new RadioButton(this);
        sim1.setText("SIM 1（slot 0）");
        sim1.setId(R.id.sim1);
        RadioButton sim2 = new RadioButton(this);
        sim2.setText("SIM 2（slot 1）");
        sim2.setId(R.id.sim2);
        group.addView(sim1);
        group.addView(sim2);
        int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
        group.check(slot == 1 ? R.id.sim2 : R.id.sim1);
        group.setOnCheckedChangeListener((g, id) -> {
            int selected = id == R.id.sim2 ? 1 : 0;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("slot", selected).apply();
            requestTileRefresh();
        });
        root.addView(group, lp(-1, -2, 0, 0, 0, dp(20)));

        Button test = new Button(this);
        test.setText("完整自检（shell UID + 当前网络类型）");
        test.setOnClickListener(v -> testRead());
        root.addView(test, lp(-1, -2, 0, 0, 0, dp(16)));

        Button copy = new Button(this);
        copy.setText("复制诊断（最近一次切换 / 自检）");
        copy.setOnClickListener(v -> {
            String operation = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("last_operation", "还没有切换记录");
            String selfTest = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("last_self_test", "还没有自检记录");
            String report = "5G Tile 2.2-preview2\n" + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT
                    + ")\n\n" + operation + "\n" + selfTest;
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("5G 切换诊断", report));
            Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
        });
        root.addView(copy, lp(-1, -2, 0, 0, 0, dp(16)));

        TextView tip = new TextView(this);
        tip.setText("初始化只需一次：\n1. Shizuku 保持运行。\n2. 点上方“授权 / 重新连接 Shizuku”。\n3. 授权后做一次完整自检。\n4. 控制中心添加“5G 切换”磁贴。\n\n包名：com.example.fivegtile\n默认 SIM 1 / slot 0。");
        tip.setTextSize(15);
        tip.setTextColor(0xFF5A5149);
        tip.setLineSpacing(0, 1.18f);
        root.addView(tip, lp(-1, -2, 0, 0, 0, 0));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                requestPermissionWhenBinderArrives = true;
                status.setText("状态：等待 Shizuku Binder，收到后会自动弹出授权");
                Toast.makeText(this, "正在等待 Shizuku 连接，不需要反复点", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "Shizuku 已授权，正在连接 shell 服务", Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Toast.makeText(this, "Shizuku 已拒绝授权，请在 Shizuku 的授权应用中重新允许本 App", Toast.LENGTH_LONG).show();
                refreshStatus();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
            status.setText("状态：已向 Shizuku 请求授权...");
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
            int shizukuUid = -1;
            try {
                binder = Shizuku.pingBinder();
                if (binder) {
                    shizukuUid = Shizuku.getUid();
                    granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                }
            } catch (Throwable ignored) {}

            if (!binder) {
                status.setText("状态：Shizuku Binder 未连接");
                return;
            }
            if (!granted) {
                status.setText("状态：Shizuku 已连接，但本 App 未授权");
                return;
            }

            status.setText("状态：Shizuku 已授权（server uid=" + shizukuUid + "），正在连接 shell 服务...");
            if (!statusPending.compareAndSet(false, true) || executor.isShutdown()) return;
            executor.execute(() -> {
                try {
                    int uid = CommandBridge.remoteUid(this);
                    runOnUiThread(() -> {
                        if (status != null && !isFinishing() && !isDestroyed()) {
                            status.setText(uid == 2000
                                    ? "状态：就绪，UserService uid=2000 (shell)"
                                    : "状态：UserService 已连接，uid=" + uid);
                        }
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> {
                        if (status != null && !isFinishing() && !isDestroyed()) {
                            status.setText("状态：Shizuku 已授权，但 UserService 连接失败");
                        }
                    });
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
                String result = CommandBridge.exec(this, NetworkCommands.get(slot));
                boolean nr = NetworkCommands.hasNr(result);
                String report = "自检：SIM " + (slot + 1) + " / uid=" + uid + "\n"
                        + result + "\n耗时：" + (SystemClock.elapsedRealtime() - started) + " ms";
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_self_test", report).apply();
                runOnUiThread(() -> Toast.makeText(this,
                        "UserService uid=" + uid + "\n"
                                + (nr ? "当前允许 5G (NR)\n" : "当前未允许 5G (NR)\n")
                                + result,
                        Toast.LENGTH_LONG).show());
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

    private void requestTileRefresh() {
        try {
            TileService.requestListeningState(this,
                    new ComponentName(this, FiveGTileService.class));
        } catch (Throwable ignored) {}
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
