package io.localproxy.telegramws;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.text.TextUtils;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ProxyService extends Service {
    static final String START = "io.localproxy.telegramws.START";
    static final String STOP = "io.localproxy.telegramws.STOP";
    private static final String CHANNEL = "proxy";
    private static final int NOTIFICATION_ID = 1443;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static volatile long logVersion;
    static volatile Phase phase = Phase.OFF;
    static volatile ProxySettings runningSettings;
    static volatile boolean changedPort;
    static volatile String error = "";
    static volatile Metrics metrics = new Metrics(0, 0, 0, 0);

    enum Phase { OFF, STARTING, ON, STOPPING, ERROR }
    record Metrics(int active, int total, long up, long down) {}

    private final Handler main = new Handler(Looper.getMainLooper());
    private Run run;

    static boolean active() {
        return phase == Phase.STARTING || phase == Phase.ON || phase == Phase.STOPPING;
    }

    static void start(Context context) {
        Intent intent = new Intent(context, ProxyService.class).setAction(START);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        context.startService(new Intent(context, ProxyService.class).setAction(STOP));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (STOP.equals(intent.getAction())) {
            if (run == null) stopSelf();
            else {
                run.stop.set(true);
                publish(Phase.STOPPING);
            }
            return START_NOT_STICKY;
        }
        if (run != null) return START_NOT_STICKY;

        ProxySettings settings = ProxySettings.load(this);
        runningSettings = settings;
        changedPort = false;
        metrics = new Metrics(0, 0, 0, 0);
        error = "";
        run = new Run(this);
        phase = Phase.STARTING;
        startForeground(NOTIFICATION_ID, notification());
        refreshTile();
        Run current = run;
        WORKER.execute(() -> {
            String failure = "";
            try {
                if (!current.stopping()) {
                    if (!Python.isStarted()) Python.start(new AndroidPlatform(getApplicationContext()));
                    Python.getInstance().getModule("android_bridge").callAttr("run", settings.json(), current);
                    if (!current.stopping()) failure = "Прокси завершил работу. Подробности в журнале.";
                }
            } catch (RuntimeException | LinkageError exc) {
                String detail = String.valueOf(exc.getMessage()).replace(settings.secret, "[секрет]");
                current.log(detail);
                failure = "Ошибка запуска прокси. Подробности в журнале.";
            }
            String result = failure;
            main.post(() -> finish(current, result));
        });
        return START_NOT_STICKY;
    }

    private void finish(Run completed, String failure) {
        if (run != completed) return;
        run = null;
        runningSettings = null;
        error = completed.stopping() ? "" : failure;
        phase = error.isEmpty() ? Phase.OFF : Phase.ERROR;
        stopForeground(STOP_FOREGROUND_REMOVE);
        refreshTile();
        stopSelf();
    }

    private void publish(Phase next) {
        phase = next;
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification());
        refreshTile();
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, ProxyTile.class));
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Локальный прокси",
                    NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent show = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProxyService.class)
                .setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        String label = switch (phase) {
            case ON -> "Слушает 127.0.0.1:" + runningSettings.port;
            case STOPPING -> "Остановка…";
            default -> "Запуск…";
        };
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(R.drawable.ic_status).setContentTitle(getString(R.string.app_name))
                .setContentText(label).setContentIntent(show).setOngoing(true)
                .setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build()).build();
    }

    static synchronized void appendLog(String line) {
        if (LOG.size() >= 240) LOG.removeFirst();
        LOG.addLast(line.length() > 3000 ? line.substring(0, 3000) + "…" : line);
        logVersion++;
    }

    static synchronized String logs() {
        return TextUtils.join("\n", LOG);
    }

    static long logVersion() {
        return logVersion;
    }

    static synchronized void clearLogs() {
        LOG.clear();
        logVersion++;
    }

    @Override public void onDestroy() {
        if (run != null) {
            run.stop.set(true);
            run = null;
            phase = Phase.OFF;
            runningSettings = null;
            refreshTile();
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    public static final class Run {
        private final ProxyService owner;
        final AtomicBoolean stop = new AtomicBoolean();

        Run(ProxyService owner) {
            this.owner = owner;
        }

        public boolean stopping() {
            return stop.get();
        }

        public void ready(int port) {
            owner.main.post(() -> {
                if (owner.run != this || stopping()) return;
                changedPort = port != runningSettings.port;
                runningSettings = runningSettings.usingPort(owner, port);
                owner.publish(Phase.ON);
            });
        }

        public void log(String message) {
            if (!stopping()) appendLog(message);
        }

        public void metrics(int active, int total, long up, long down) {
            if (!stopping()) ProxyService.metrics = new Metrics(active, total, up, down);
        }
    }
}
