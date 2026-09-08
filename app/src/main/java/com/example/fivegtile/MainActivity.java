package com.example.fivegtile;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    private static final int REQ_SHIZUKU = 1001;
    private static final String PREFS = "settings";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private TextView status;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        CommandBridge.warmUp(this);
        refreshStatus();
    };
    private final Shizuku.OnBinderDeadListener binderDeadListener = this::refreshStatus;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, grantResult) -> {
        if (requestCode == REQ_SHIZUKU) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) CommandBridge.warmUp(this);
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
        desc.setText("最终架构使用 Shizuku UserService（shell uid）执行命令。特权服务由 Shizuku 以 daemon 模式维护，App 自己被清后台后，磁贴会自动重新连接，不依赖 aShell You，也不需要锁最近任务。");
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
        sim1.setId(100);
        RadioButton sim2 = new RadioButton(this);
        sim2.setText("SIM 2（slot 1）");
        sim2.setId(101);
        group.addView(sim1);
        group.addView(sim2);
        int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
        group.check(slot == 1 ? 101 : 100);
        group.setOnCheckedChangeListener((g, id) -> {
            int selected = id == 101 ? 1 : 0;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("slot", selected).apply();
            requestTileRefresh();
        });
        root.addView(group, lp(-1, -2, 0, 0, 0, dp(20)));

        Button test = new Button(this);
        test.setText("完整自检（shell UID + 当前网络类型）");
        test.setOnClickListener(v -> testRead());
        root.addView(test, lp(-1, -2, 0, 0, 0, dp(16)));

        TextView tip = new TextView(this);
        tip.setText("使用方法：\n1. Shizuku 保持运行，并给本 App 授权一次。\n2. 控制中心添加“5G 切换”磁贴。\n3. 点磁贴自动 4G ↔ 5G。\n4. 可以正常从最近任务清掉本 App，再直接使用磁贴。\n\n默认使用 SIM 1 / slot 0 和你已实机验证成功的网络掩码。");
        tip.setTextSize(15);
        tip.setTextColor(0xFF5A5149);
        tip.setLineSpacing(0, 1.18f);
        root.addView(tip, lp(-1, -2, 0, 0, 0, 0));

        return root;
    }

    private void requestShizuku() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                CommandBridge.warmUp(this);
                Toast.makeText(this, "Shizuku 已授权，正在连接 shell 服务", Toast.LENGTH_SHORT).show();
                refreshStatus();
                return;
            }
            Shizuku.requestPermission(REQ_SHIZUKU);
        } catch (Throwable t) {
            Toast.makeText(this, "Shizuku 连接失败: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
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
                status.setText("状态：Shizuku 未连接");
                return;
            }
            if (!granted) {
                status.setText("状态：Shizuku 已运行，但本 App 未授权");
                return;
            }

            status.setText("状态：Shizuku 已授权（server uid=" + shizukuUid + "），正在连接 shell 服务...");
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
                            status.setText("状态：Shizuku 已授权，shell 服务连接失败");
                        }
                    });
                }
            });
        });
    }

    private void testRead() {
        executor.execute(() -> {
            try {
                int uid = CommandBridge.remoteUid(this);
                int slot = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("slot", 0);
                String result = CommandBridge.exec(this, NetworkCommands.get(slot));
                boolean nr = NetworkCommands.hasNr(result);
                runOnUiThread(() -> Toast.makeText(this,
                        "UserService uid=" + uid + "\n"
                                + (nr ? "当前允许 5G (NR)\n" : "当前未允许 5G (NR)\n")
                                + result,
                        Toast.LENGTH_LONG).show());
            } catch (Throwable t) {
                runOnUiThread(() -> Toast.makeText(this,
                        t.getMessage() == null ? "自检失败" : "自检失败：" + t.getMessage(),
                        Toast.LENGTH_LONG).show());
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
