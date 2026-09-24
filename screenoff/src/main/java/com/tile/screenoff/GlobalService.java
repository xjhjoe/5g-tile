package com.tile.screenoff;

import static java.lang.Math.abs;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.Locale;
import java.util.Objects;

import rikka.shizuku.Shizuku;

public class GlobalService extends AccessibilityService implements SharedPreferences.OnSharedPreferenceChangeListener {


    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private ImageView view;
    SharedPreferences sp;
    private boolean exist = false, canmove, doubleTap, shake, volume, netControl;
    int size, sensity, scrOnKey, scrOffKey;
    private int SCREEN_WIDTH, SCREEN_HEIGHT;
    OrientationEventListener listener;
    IScreenOff iScreenOff = null;
    private final ScreenOffBridge.Listener bridgeListener = service -> {
        iScreenOff = service;
        if (service != null) {
            recordDiagnostic("UserService", false, "connected via Nightzuku/Shizuku");
            try { floatWindow(); } catch (Throwable ignored) {}
        } else {
            recordDiagnostic("UserService", false, "disconnected");
        }
    };
    private final Handler autoStartHandler = new Handler(Looper.getMainLooper());
    private boolean autoStartInFlight = false;
    private int autoStartAttempt = 0;
    private final long[] autoStartDelays = new long[]{0L, 2000L, 5000L, 10000L, 30000L};
    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener = () -> {
        recordDiagnostic("AutoStart", false, "Nightzuku/Shizuku binder available");
        autoStartAttempt = 0;
        scheduleAutoStart();
    };


    public static boolean isScreenOffServiceRunning(Context context) {

        ActivityManager activityManager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);

        List<ActivityManager.RunningServiceInfo> runningServices = activityManager.getRunningServices(Integer.MAX_VALUE);
        if (runningServices.isEmpty()) {
            return false;
        }

        for (ActivityManager.RunningServiceInfo serviceInfo : runningServices) {
//            Log.d("TAG", "isGyroFixServiceRunning: "+serviceInfo.toString());
//            Log.d("TAG", "GyroFixService.class.getName(): "+GyroFixService.class.getName());
//            Log.d("TAG", "serviceInfo.service.getClassName(): "+serviceInfo.service.getClassName());
            if (GlobalService.class.getName().equals(serviceInfo.service.getClassName())) {
                return true;
            }
        }
        return false;

    }

    private long lastOffRequestMs = 0L;
    private static final long OFF_DEBOUNCE_MS = 800L;

    final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (iScreenOff == null || intent.getAction() == null) return;
            try {
                switch (intent.getAction()) {
                    case Intent.ACTION_SCREEN_OFF:
                        iScreenOff.updateNowScreenState(false);
                        if (view != null) view.setKeepScreenOn(false);
                        if (listener != null) listener.disable();
                        if (exist && view != null) windowManager.updateViewLayout(view, params);
                        recordDiagnostic("System", true, "ACTION_SCREEN_OFF");
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        iScreenOff.updateNowScreenState(true);
                        recordDiagnostic("System", false, "ACTION_SCREEN_ON");
                        break;
                    case Intent.ACTION_USER_PRESENT:
                        iScreenOff.updateNowScreenState(true);
                        recordDiagnostic("System", false, "ACTION_USER_PRESENT");
                        break;
                }
            } catch (RemoteException e) {
                recordDiagnostic("System", false, "state sync failed");
            }
        }
    };

    final BroadcastReceiver localControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() == null) return;
            switch (intent.getAction()) {
                case "action.ScrOff":
                    String source = intent.getStringExtra("source");
                    screenoff(intent.getBooleanExtra("state", true),
                            source == null ? "Shortcut" : source);
                    break;
                case "intent.screenoff.exit":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) disableSelf();
                    else stopSelf();
                    break;
            }
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent accessibilityEvent) {
    }

    @Override
    public void onInterrupt() {

    }


    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String s) {
        if (s.startsWith("x") || s.startsWith("y")) return;

        view.setVisibility(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && sharedPreferences.getBoolean("land", false) ? View.GONE : View.VISIBLE);
        size = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sharedPreferences.getInt("size", 50), getResources().getDisplayMetrics()));
        params.height = size;
        params.width = size;
        params.alpha = sharedPreferences.getInt("tran", 90) * 0.01f;
        canmove = sharedPreferences.getBoolean("canmove", true);
        doubleTap = sharedPreferences.getBoolean("doubleTap", false);
        shake = sharedPreferences.getBoolean("shake", false);
        sensity = sharedPreferences.getInt("sensity", 10);
        volume = sharedPreferences.getBoolean("volume", false);
        scrOnKey = sharedPreferences.getInt("scrOnKey", 24);
        scrOffKey = sharedPreferences.getInt("scrOffKey", 25);
        netControl = sharedPreferences.getBoolean("net", false);
        if (netControl) startServer();
        floatWindow();

    }


    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sp = getSharedPreferences("s", 0);

        sensity = sp.getInt("sensity", 10);
        listener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return;
                boolean wake = ((orientation >= 360 - sensity || orientation <= sensity) || orientation >= 90 - sensity && orientation <= 90 + sensity) || orientation >= 180 - sensity && orientation <= 180 + sensity || orientation >= 270 - sensity && orientation <= 270 + sensity;
                //下面是手机旋转准确角度与四个方向角度（0 90 180 270）的转换
                if (wake) {
                    screenoff(false, "OrientationWake");
                    this.disable();
                }
            }

        };
        windowManager = (WindowManager) getSystemService(Service.WINDOW_SERVICE);
        GetWidthHeight();
        size = (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sp.getInt("size", 50), getResources().getDisplayMetrics()));

        params = new WindowManager.LayoutParams(size, size, Build.VERSION.SDK_INT >= 22 ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.RGBA_8888);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.alpha = sp.getInt("tran", 90) * 0.01f;
        boolean isLand = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
        params.x = sp.getInt("x" + (isLand ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (isLand ? "1" : "2"), 0);
        view = new ImageView(this);
        ShapeDrawable oval = new ShapeDrawable(new OvalShape());
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.bg) : 0xffc1e8ff);
        view.setBackground(oval);
        view.setImageResource(R.drawable.fw);
        canmove = sp.getBoolean("canmove", true);
        doubleTap = sp.getBoolean("doubleTap", false);
        shake = sp.getBoolean("shake", false);
        volume = sp.getBoolean("volume", false);
        scrOnKey = sp.getInt("scrOnKey", 24);
        scrOffKey = sp.getInt("scrOffKey", 25);
        netControl = sp.getBoolean("net", false);
        if (netControl) startServer();
        view.setOnTouchListener(new View.OnTouchListener() {
            int lastX = 0;
            int lastY = 0;
            int paramX = 0;
            int paramY = 0;
            long lastDown = 0, lastUp = 0;
            boolean moved = false;

            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {

                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = (int) motionEvent.getRawX();
                        lastY = (int) motionEvent.getRawY();
                        paramX = params.x;
                        paramY = params.y;
                        params.alpha = 1;
                        new Handler().postDelayed(() -> {
                            if (System.currentTimeMillis() - lastUp >= 400 && !moved) {
                                screenoff(true, "FloatingLongPress");
                            }
                        }, 400);
                        break;
                    case MotionEvent.ACTION_MOVE:

                        int dx = (int) motionEvent.getRawX() - lastX;
                        int dy = (int) motionEvent.getRawY() - lastY;
                        if (abs(dx) > 4 || abs(dy) > 4)
                            moved = true;
                        if (!canmove) return true;
                        params.x = paramX + dx;
                        params.y = paramY + dy;
                        windowManager.updateViewLayout(view, params);

                        break;
                    case MotionEvent.ACTION_UP:
                        lastUp = System.currentTimeMillis();
                        params.alpha = sp.getInt("tran", 90) * 0.01f;
                        params.x = (params.x > (SCREEN_WIDTH - size) * 0.43) ? (SCREEN_WIDTH - size) / 2 : ((params.x < (SCREEN_WIDTH - size) * -0.43) ? -(SCREEN_WIDTH - size) / 2 : params.x);
                        params.y = Math.min(Math.max(params.y, -(SCREEN_HEIGHT - size) / 2), (SCREEN_HEIGHT - size) / 2);
                        windowManager.updateViewLayout(view, params);
                        moved = false;
                        boolean isLand = getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT;
                        sp.edit().putInt("x" + (isLand ? "1" : "2"), params.x).putInt("y" + (isLand ? "1" : "2"), params.y).apply();
                }


                if (!doubleTap) return false;
                switch (motionEvent.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_OUTSIDE:
                        if (System.currentTimeMillis() - lastDown <= 400)
                            screenoff(false, "FloatingDoubleTap");
                        lastDown = System.currentTimeMillis();
                        break;
                }
                return false;
            }
        });
        view.setVisibility(getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT && sp.getBoolean("land", false) ? View.GONE : View.VISIBLE);
        floatWindow();


        IntentFilter screenFilter = new IntentFilter();
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenStateReceiver, screenFilter);

        IntentFilter localFilter = new IntentFilter();
        localFilter.addAction("action.ScrOff");
        localFilter.addAction("intent.screenoff.exit");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localControlReceiver, localFilter, RECEIVER_NOT_EXPORTED);
            registerReceiver(binderReceiver, new IntentFilter("intent.screenoff.sendBinder"), RECEIVER_EXPORTED);
        } else {
            registerReceiver(localControlReceiver, localFilter);
            registerReceiver(binderReceiver, new IntentFilter("intent.screenoff.sendBinder"));
        }
        sp.registerOnSharedPreferenceChangeListener(this);
        ScreenOffBridge.addListener(bridgeListener);
        ScreenOffBridge.warmUp(this);
        recordDiagnostic("UserService", false,
                ScreenOffBridge.isShizukuReady() ? "binding requested" : "Nightzuku/Shizuku not ready");
    }


    void screenoff(Boolean bb) {
        screenoff(bb, "Unknown");
    }

    void screenoff(Boolean bb, String source) {
        if (iScreenOff == null) {
            recordDiagnostic(source, bb, "ignored: service not ready");
            return;
        }
        try {
            int beforeState = iScreenOff.getNowScreenState();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            boolean interactive = pm == null || pm.isInteractive();

            if (bb) {
                if (!interactive || beforeState == 0) {
                    recordDiagnostic(source, true, "ignored: already non-interactive, state=" + beforeState);
                    return;
                }
                if (beforeState == 2) {
                    recordDiagnostic(source, true, "ignored: already special-off");
                    return;
                }
                long now = SystemClock.elapsedRealtime();
                if (now - lastOffRequestMs < OFF_DEBOUNCE_MS) {
                    recordDiagnostic(source, true, "ignored: duplicate");
                    return;
                }
                lastOffRequestMs = now;
            }

            iScreenOff.setPowerMode(bb);
            if (view != null) view.setKeepScreenOn(bb);
            if (shake && bb && listener != null) listener.enable();
            int afterState = iScreenOff.getNowScreenState();
            recordDiagnostic(source, bb, "executed: interactive=" + interactive
                    + ", before=" + beforeState + ", after=" + afterState);
        } catch (RemoteException ex) {
            recordDiagnostic(source, bb, "RemoteException");
        }
    }

    private void recordDiagnostic(String source, boolean off, String detail) {
        if (sp == null) return;
        String entry = new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
                .format(new java.util.Date())
                + " | " + (off ? "OFF" : "ON")
                + " | " + source + " | " + detail;
        String old = sp.getString("diag_history", "");
        String history = entry + (old.isEmpty() ? "" : "\n" + old);
        if (history.length() > 6000) history = history.substring(0, 6000);
        sp.edit().putString("diag_history", history).apply();
        Log.i("ScreenOffDiag", entry);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (!volume || event.getAction() == KeyEvent.ACTION_UP)
            return super.onKeyEvent(event);

        try {
            final int keycode = event.getKeyCode();
            final int nowState = iScreenOff.getNowScreenState();
            if (keycode == scrOffKey && nowState == 1) {
                screenoff(true, "VolumeKey");
                return true;
            }
            if (keycode == scrOnKey && nowState == 2) {
                screenoff(false, "VolumeKey");
                return true;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }

        return super.onKeyEvent(event);
    }

    public void floatWindow() {
        if (sp.getBoolean("float", false)) {
            if (!exist) {
                windowManager.addView(view, params);
                exist = true;
            } else {
                windowManager.updateViewLayout(view, params);
            }
        } else {
            if (view != null) {
                try {
                    windowManager.removeViewImmediate(view);
                } catch (Exception ignored) {
                }
                exist = false;
            }
        }
    }


    void GetWidthHeight() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        SCREEN_WIDTH = metrics.widthPixels;
        SCREEN_HEIGHT = metrics.heightPixels;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        boolean isLand = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT;
        if (sp.getBoolean("land", false))
            view.setVisibility(isLand ? View.GONE : View.VISIBLE);
        GetWidthHeight();
        params.x = sp.getInt("x" + (isLand ? "1" : "2"), 0);
        params.y = sp.getInt("y" + (isLand ? "1" : "2"), 0);
        ShapeDrawable oval = new ShapeDrawable(new OvalShape());
        oval.getPaint().setColor(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? getColor(R.color.bg) : 0xffc1e8ff);
        view.setBackground(oval);
        view.setImageResource(R.drawable.fw);
        if (exist) windowManager.updateViewLayout(view, params);

    }

    @Override
    public void onDestroy() {
        ScreenOffBridge.removeListener(bridgeListener);
        autoStartHandler.removeCallbacksAndMessages(null);
        try { Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener); } catch (Throwable ignored) {}
        try { unregisterReceiver(screenStateReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(localControlReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(binderReceiver); } catch (Exception ignored) {}
        try {
            windowManager.removeViewImmediate(view);
        } catch (Exception ignored) {
        }
        exist = false;
        listener.disable();
        sp.unregisterOnSharedPreferenceChangeListener(this);
        if (netControl) stopServer();
        super.onDestroy();
    }


    public static int port = 20000;
    private SimpleTcpServer server;

    private void startServer() {
        if (server != null) return;
        server = new SimpleTcpServer(new SimpleTcpServer.TcpConnectionListener() {
            private final HttpRequestParser parser = new HttpRequestParser();

            @Override
            public void onReceive(final byte[] data) {
                parser.add(data);
                final HttpRequest request = parser.parse();
                if (request != null) {
                    output(request);
                    parser.clear();
                }
            }

            @Override
            public void onResponseSent() {
                server.restart();
            }

        }, port);
        server.start();

    }

    void stopServer() {
        server.stop();
        server = null;
    }

    private void output(HttpRequest request) {
        if (server == null) {
            return;
        }
        String target = request.getRequestTarget();
        Log.d("TAG", "output: " + target);
        if (target.equals("/") || target.equals("/index.html")) {
            outputHtml(buildIndexHtml(request), "200 OK");
        } else if (target.equals("/favicon.ico")) {
            outputPng(Objects.requireNonNull(loadBinary("favicon.png")));
        } else {
            try {
                switch (target.substring(0, 3)) {
                    case "/1?":
                        screenoff(false, "NetworkControl");
                        outputHtml("", "200 OK");
                        break;
                    case "/2?":
                        screenoff(true, "NetworkControl");
                        outputHtml("", "200 OK");
                        break;
                    default:
                        outputHtml(build404Html(), "404 Not Found");
                        break;
                }
            } catch (Exception ignored) {
            }

        }
    }

    private String buildIndexHtml(HttpRequest request) {
        String nowState = "未知";
        try {
            switch (iScreenOff.getNowScreenState()) {
                case 0:
                    nowState = "息屏";
                    break;
                case 1:
                    nowState = "亮屏";
                    break;
                default:
                    nowState = "息屏运行";
                    break;
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return Objects.requireNonNull(loadHtml("index.html"))
                .replace("{{brand}}", Build.BRAND)
                .replace("{{device}}", Build.MODEL + " Android " + Build.VERSION.RELEASE)
                .replace("{{state}}", nowState);
    }

    private String build404Html() {
        return loadHtml("404.html");
    }

    private static final int BUFFER_SIZE = 1024 * 1024;
    private static final byte LF = 0x0a;
    private static final byte CR = 0x0d;

    private void outputHtml(String html, String responseCode) {
        String startLine = "HTTP/1.1 " + responseCode;
        List<String> responseHeaders = new ArrayList<>();
        responseHeaders.add("Content-Type: text/html; charset=UTF-8");
        responseHeaders.add(String.format(Locale.getDefault(), "Content-Length: %d", html.getBytes().length));
        StringBuilder builder = new StringBuilder();
        builder.append(startLine).append(new String(new byte[]{CR, LF}));
        for (String responseHeader : responseHeaders) {
            builder.append(responseHeader).append(new String(new byte[]{CR, LF}));
        }
        builder.append(new String(new byte[]{CR, LF}));
        builder.append(html);
        server.output(builder.toString());
    }

    private void outputPng(byte[] png) {
        String startLine = "HTTP/1.1 " + "200 OK";
        List<String> responseHeaders = new ArrayList<>();
        responseHeaders.add("Content-Type: image/png");
        responseHeaders.add(String.format(Locale.getDefault(), "Content-Length: %d", png.length));
        StringBuilder builder = new StringBuilder();
        builder.append(startLine).append(new String(new byte[]{CR, LF}));
        for (String responseHeader : responseHeaders) {
            builder.append(responseHeader).append(new String(new byte[]{CR, LF}));
        }
        builder.append(new String(new byte[]{CR, LF}));
        byte[] headerField = builder.toString().getBytes();
        byte[] output = new byte[headerField.length + png.length];
        System.arraycopy(headerField, 0, output, 0, headerField.length);
        System.arraycopy(png, 0, output, headerField.length, png.length);
        server.output(output);
    }

    private String loadHtml(String fileName) {
        byte[] binary = loadBinary(fileName);
        if (binary == null) {
            return null;
        }
        return new String(binary);
    }

    private byte[] loadBinary(String fileName) {
        try {
            InputStream is = getAssets().open(fileName);
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            byte[] chunk = new byte[BUFFER_SIZE];
            BufferedInputStream bis = new BufferedInputStream(is, BUFFER_SIZE);
            try {
                int len;
                while ((len = bis.read(chunk, 0, BUFFER_SIZE)) > 0) {
                    byteStream.write(chunk, 0, len);
                }
                return byteStream.toByteArray();
            } finally {
                try {
                    byteStream.reset();
                    bis.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }


}
