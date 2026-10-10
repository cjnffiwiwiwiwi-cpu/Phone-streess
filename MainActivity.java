package com.example.phonestresstest;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView tempView, batteryView, stateView, elapsedView, thermalView;
    private Spinner cpuSpinner, ramSpinner;
    private Button startButton, stopButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long startedAt = 0;
    private boolean running = false;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateBattery();
            if (running && startedAt > 0) {
                long sec = (System.currentTimeMillis() - startedAt) / 1000;
                elapsedView.setText(String.format(java.util.Locale.US, "زمان تست: %02d:%02d:%02d", sec/3600, (sec/60)%60, sec%60));
            }
            handler.postDelayed(this, 1000);
        }
    };
    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (StressService.ACTION_STATE.equals(intent.getAction())) {
                boolean active = intent.getBooleanExtra("running", false);
                running = active;
                if (active && startedAt == 0) startedAt = System.currentTimeMillis();
                if (!active) startedAt = 0;
                stateView.setText(active ? "وضعیت: تست در حال اجراست" : "وضعیت: متوقف");
                startButton.setEnabled(!active);
                stopButton.setEnabled(active);
                String reason = intent.getStringExtra("reason");
                if (reason != null && !reason.isEmpty()) stateView.setText("وضعیت: " + reason);
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
        IntentFilter filter = new IntentFilter(StressService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceReceiver, filter);
        handler.post(ticker);
        updateBattery();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));
        root.setBackgroundColor(0xFFF2F6F5);
        scroll.addView(root);

        TextView title = text("Phone Stress Test", 26, 0xFF004D40, true);
        root.addView(title);
        TextView subtitle = text("تست فشار پردازنده و پایش دمای باتری", 16, 0xFF374A46, false);
        LinearLayout.LayoutParams subp = params(); subp.topMargin = dp(6); root.addView(subtitle, subp);

        tempView = cardText(root, "دمای باتری: در حال خواندن…", 22, 0xFFB71C1C);
        batteryView = cardText(root, "شارژ باتری: --%", 17, 0xFF263238);
        thermalView = cardText(root, "وضعیت حرارتی اندروید: بررسی می‌شود", 15, 0xFF263238);
        stateView = cardText(root, "وضعیت: متوقف", 17, 0xFF00695C);
        elapsedView = cardText(root, "زمان تست: 00:00:00", 16, 0xFF263238);

        addLabel(root, "تعداد هسته‌های CPU");
        cpuSpinner = new Spinner(this);
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
        String[] cpuOptions = new String[cores];
        for (int i=0; i<cores; i++) cpuOptions[i] = (i+1) + (i==0 ? " هسته" : " هسته");
        cpuSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, cpuOptions));
        cpuSpinner.setSelection(cores-1);
        root.addView(cpuSpinner, params());

        addLabel(root, "حافظه کمکی (RAM)");
        ramSpinner = new Spinner(this);
        String[] ramOptions = {"۰ مگابایت", "۶۴ مگابایت", "۱۲۸ مگابایت", "۲۵۶ مگابایت"};
        ramSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ramOptions));
        ramSpinner.setSelection(1);
        root.addView(ramSpinner, params());

        TextView note = text("تست به‌صورت پیش‌فرض تا توقف دستی ادامه دارد؛ برای محافظت از گوشی، در دمای باتری ۴۸°C یا وضعیت حرارتی شدید، خودکار متوقف می‌شود. دمای CPU/GPU در همه گوشی‌ها در دسترس برنامه‌ها نیست.", 14, 0xFF6D4C41, false);
        LinearLayout.LayoutParams np = params(); np.topMargin = dp(16); root.addView(note, np);

        LinearLayout buttons = new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setGravity(Gravity.CENTER); 
        startButton = new Button(this); startButton.setText("شروع تست"); startButton.setOnClickListener(v -> startTest());
        stopButton = new Button(this); stopButton.setText("توقف فوری"); stopButton.setEnabled(false); stopButton.setOnClickListener(v -> stopTest());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(54), 1); bp.setMargins(dp(4), dp(18), dp(4), 0);
        buttons.addView(startButton, bp); buttons.addView(stopButton, bp); root.addView(buttons);
        setContentView(scroll);
    }

    private void startTest() {
        int threads = cpuSpinner.getSelectedItemPosition() + 1;
        int[] ram = {0, 64, 128, 256};
        int memoryMb = ram[ramSpinner.getSelectedItemPosition()];
        Intent i = new Intent(this, StressService.class);
        i.setAction(StressService.ACTION_START);
        i.putExtra("threads", threads); i.putExtra("memoryMb", memoryMb);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i); else startService(i);
        startedAt = System.currentTimeMillis(); running = true;
        startButton.setEnabled(false); stopButton.setEnabled(true);
        stateView.setText("وضعیت: در حال راه‌اندازی…");
    }

    private void stopTest() {
        Intent i = new Intent(this, StressService.class); i.setAction(StressService.ACTION_STOP); startService(i);
        running = false; startedAt = 0; startButton.setEnabled(true); stopButton.setEnabled(false);
        stateView.setText("وضعیت: متوقف شد"); elapsedView.setText("زمان تست: 00:00:00");
    }

    private void updateBattery() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (t >= 0) {
                float c = t / 10f;
                tempView.setText(String.format(java.util.Locale.US, "دمای باتری: %.1f°C", c));
                if (c >= 43f) tempView.setTextColor(0xFFB71C1C); else tempView.setTextColor(0xFF00695C);
            } else tempView.setText("دمای باتری: در دسترس نیست");
            if (level >= 0 && scale > 0) batteryView.setText("شارژ باتری: " + (level * 100 / scale) + "%");
        }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.os.PowerManager pm = (android.os.PowerManager)getSystemService(POWER_SERVICE);
                int status = pm.getCurrentThermalStatus();
                String[] names = {"عادی", "کم", "متوسط", "بالا", "شدید", "بحرانی", "خاموشی"};
                int idx = Math.max(0, Math.min(status, names.length-1));
                thermalView.setText("وضعیت حرارتی اندروید: " + names[idx]);
            } catch (Exception e) { thermalView.setText("وضعیت حرارتی اندروید: در دسترس نیست"); }
        } else thermalView.setText("وضعیت حرارتی اندروید: پشتیبانی نمی‌شود");
    }

    private TextView cardText(LinearLayout root, String s, int size, int color) {
        TextView v = text(s, size, color, true); v.setPadding(dp(14), dp(14), dp(14), dp(14));
        v.setBackgroundColor(0xFFFFFFFF); LinearLayout.LayoutParams p = params(); p.topMargin = dp(10); root.addView(v, p); return v;
    }
    private void addLabel(LinearLayout root, String s) { TextView v = text(s, 16, 0xFF263238, true); LinearLayout.LayoutParams p=params(); p.topMargin=dp(16); root.addView(v,p); }
    private TextView text(String s, int size, int color, boolean bold) { TextView v = new TextView(this); v.setText(s); v.setTextSize(size); v.setTextColor(color); if (bold) v.setTypeface(null, android.graphics.Typeface.BOLD); return v; }
    private LinearLayout.LayoutParams params() { return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); }
    private int dp(int n) { return (int)(n * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        handler.removeCallbacks(ticker);
        try { unregisterReceiver(serviceReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
