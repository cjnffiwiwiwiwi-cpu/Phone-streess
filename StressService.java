package com.example.phonestresstest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class StressService extends Service {
    public static final String ACTION_START = "com.example.phonestresstest.START";
    public static final String ACTION_STOP = "com.example.phonestresstest.STOP";
    public static final String ACTION_STATE = "com.example.phonestresstest.STATE";
    private static final String CHANNEL_ID = "stress_test_channel";
    private static final int NOTIFICATION_ID = 7401;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Thread> workers = new ArrayList<>();
    private volatile byte[][] memoryBlocks = new byte[0][];
    private Thread monitor;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() { super.onCreate(); createChannel(); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) { stopStress("توسط کاربر متوقف شد"); return START_NOT_STICKY; }
        if (ACTION_START.equals(action)) {
            if (running.get()) stopStress("شروع مجدد");
            int threads = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), intent.getIntExtra("threads", 1)));
            int memoryMb = Math.max(0, Math.min(256, intent.getIntExtra("memoryMb", 64)));
            startForeground(NOTIFICATION_ID, buildNotification("در حال آماده‌سازی تست…"));
            running.set(true);
            acquireWakeLock();
            allocateMemory(memoryMb);
            for (int n=0; n<threads; n++) {
                Thread worker = new Thread(() -> {
                    double x = 0.123456789;
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        for (int i=0; i<4000; i++) x = Math.sin(x * 1.0000001 + i) * Math.cos(x + 0.000001);
                        if (x == Double.POSITIVE_INFINITY) x = 0.1;
                    }
                }, "StressCPU-" + n);
                worker.setPriority(Thread.NORM_PRIORITY);
                workers.add(worker); worker.start();
            }
            monitor = new Thread(this::monitorLoop, "StressMonitor"); monitor.start();
            broadcastState(true, "");
            updateNotification("تست فعال است • برای توقف از اعلان یا برنامه استفاده کنید");
        }
        return START_NOT_STICKY;
    }

    private void allocateMemory(int mb) {
        try {
            int blocks = mb / 8;
            byte[][] allocated = new byte[blocks][];
            for (int i=0; i<blocks; i++) {
                allocated[i] = new byte[8 * 1024 * 1024];
                for (int j=0; j<allocated[i].length; j += 4096) allocated[i][j] = (byte)(j + i);
            }
            memoryBlocks = allocated;
        } catch (OutOfMemoryError e) {
            memoryBlocks = new byte[0][];
            broadcastState(true, "حافظه کافی نبود؛ تست CPU ادامه دارد");
        }
    }

    private void monitorLoop() {
        while (running.get()) {
            try {
                Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    int raw = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (raw >= 480) { stopStress("به دلیل دمای باتری ۴۸°C یا بیشتر، تست برای ایمنی متوقف شد"); return; }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    PowerManager pm = (PowerManager)getSystemService(Context.POWER_SERVICE);
                    if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE) {
                        stopStress("به دلیل وضعیت حرارتی شدید گوشی، تست متوقف شد"); return;
                    }
                }
                updateNotification("تست فعال است • دمای باتری در برنامه نمایش داده می‌شود");
                Thread.sleep(3000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (Exception ignored) { try { Thread.sleep(3000); } catch (InterruptedException e) { return; } }
        }
    }

    private synchronized void stopStress(String reason) {
        boolean wasRunning = running.getAndSet(false);
        for (Thread t : workers) { try { t.interrupt(); } catch (Exception ignored) {} }
        workers.clear();
        if (monitor != null && monitor != Thread.currentThread()) { monitor.interrupt(); monitor = null; }
        memoryBlocks = new byte[0][];
        if (wakeLock != null && wakeLock.isHeld()) { try { wakeLock.release(); } catch (Exception ignored) {} }
        wakeLock = null;
        broadcastState(false, reason);
        if (wasRunning || Build.VERSION.SDK_INT >= 24) {
            try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception e) { stopForeground(true); }
            stopSelf();
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName() + ":StressTest");
                wakeLock.setReferenceCounted(false); wakeLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Phone stress test", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("کنترل تست فشار گوشی");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String content) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, StressService.class); stop.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_info_details).setContentTitle("Phone Stress Test").setContentText(content).setContentIntent(openPending).setOngoing(true).addAction(android.R.drawable.ic_media_pause, "توقف فوری", stopPending);
        return builder.build();
    }
    private void updateNotification(String content) {
        if (!running.get()) return;
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(content));
    }
    private void broadcastState(boolean active, String reason) {
        Intent i = new Intent(ACTION_STATE); i.setPackage(getPackageName()); i.putExtra("running", active); i.putExtra("reason", reason); sendBroadcast(i);
    }
    @Override public void onTaskRemoved(Intent rootIntent) { super.onTaskRemoved(rootIntent); /* foreground test continues; notification provides a stop action */ }
    @Override public void onDestroy() { stopStress("سرویس بسته شد"); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
