package io.localproxy.telegramws;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String[] TABS = {"Прокси", "Настройки", "Журнал", "Информация"};
    private static final int INSTALL_PERMISSION_REQUEST = 1444;
    private final Handler main = new Handler();
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            main.postDelayed(this, 450);
        }
    };
    private int tab, background, surface, ink, muted, border, pressed;
    private LinearLayout layout, navigation, content, settingsFooter;
    private ScrollView scroll;
    private TextView status, address, stats, logView;
    private Button toggle, open, copy;
    private TextView updateStatus;
    private Button updateButton;
    private AppUpdates updates;
    private boolean installWhenReady, resumed;
    private EditText port, pool, secret, dc, domains, workers;
    private Switch cf, h2, testDc, debug;
    private long visibleLogVersion = -1;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        updates = AppUpdates.get(this);
        installWhenReady = state != null && state.getBoolean("installWhenReady");
        background = getColor(R.color.breakwall_background);
        surface = getColor(R.color.breakwall_surface);
        ink = getColor(R.color.breakwall_foreground);
        muted = getColor(R.color.breakwall_secondary);
        border = getColor(R.color.breakwall_border);
        pressed = getColor(R.color.breakwall_pressed);

        layout = column();
        layout.setBackgroundColor(background);
        if (Build.VERSION.SDK_INT >= 35) {
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                var keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
                return WindowInsets.CONSUMED;
            });
        }
        setContentView(layout);
        LinearLayout header = column();
        header.setPadding(dp(20), dp(16), dp(20), dp(8));
        header.addView(text(getString(R.string.app_name), 25, ink, true));
        layout.addView(header);
        navigation = row();
        navigation.setPadding(dp(12), 0, dp(12), dp(8));
        HorizontalScrollView tabScroller = new HorizontalScrollView(this);
        tabScroller.setFillViewport(true);
        tabScroller.setHorizontalScrollBarEnabled(false);
        tabScroller.addView(navigation);
        layout.addView(tabScroller);
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        settingsFooter = column();
        settingsFooter.setPadding(dp(16), dp(4), dp(16), dp(8));
        settingsFooter.addView(button("Сохранить настройки", true, this::saveSettings));
        layout.addView(settingsFooter);
        show(state == null ? 0 : state.getInt("tab"));
        if (state != null && tab == 1 && state.containsKey("port")) restoreDraft(state);
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        main.post(ticker);
    }

    @Override public void onPause() {
        resumed = false;
        main.removeCallbacks(ticker);
        super.onPause();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putBoolean("installWhenReady", installWhenReady);
        if (tab != 1) return;
        state.putString("port", fieldValue(port));
        state.putString("pool", fieldValue(pool));
        state.putString("dc", fieldValue(dc));
        state.putString("domains", fieldValue(domains));
        state.putString("workers", fieldValue(workers));
        state.putBoolean("cf", cf.isChecked());
        state.putBoolean("h2", h2.isChecked());
        state.putBoolean("testDc", testDc.isChecked());
        state.putBoolean("debug", debug.isChecked());
    }

    private void restoreDraft(Bundle state) {
        port.setText(state.getString("port"));
        pool.setText(state.getString("pool"));
        dc.setText(state.getString("dc"));
        domains.setText(state.getString("domains"));
        workers.setText(state.getString("workers"));
        cf.setChecked(state.getBoolean("cf"));
        h2.setChecked(state.getBoolean("h2"));
        testDc.setChecked(state.getBoolean("testDc"));
        debug.setChecked(state.getBoolean("debug"));
    }

    private void show(int selected) {
        tab = selected;
        if (selected != 1) installWhenReady = false;
        settingsFooter.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
        navigation.removeAllViews();
        for (int index = 0; index < TABS.length; index++) {
            final int destination = index;
            Button item = button(TABS[index], selected == index, () -> {
                if (tab != destination) show(destination);
            });
            item.setSelected(selected == index);
            item.setTextSize(13);
            item.setSingleLine(true);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2, 1);
            lp.setMargins(dp(4), 0, dp(4), 0);
            navigation.addView(item, lp);
            if (selected == index) {
                item.post(() -> item.requestRectangleOnScreen(
                        new Rect(0, 0, item.getWidth(), item.getHeight())));
            }
        }

        scroll.removeAllViews();
        content = column();
        content.setPadding(dp(16), dp(8), dp(16), dp(20));
        content.setFocusableInTouchMode(true);
        scroll.addView(content);
        if (selected == 0) dashboard();
        else if (selected == 1) settings();
        else if (selected == 2) journal();
        else information();
        scroll.scrollTo(0, 0);
        content.requestFocus();
        refresh();
    }

    private void dashboard() {
        LinearLayout card = card();
        status = text("", 24, ink, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(status);
        address = text("", 15, muted, false);
        add(card, address, 5);
        toggle = button("Запустить", true, this::toggleProxy);
        add(card, toggle, 18);
        open = button("Подключить в Telegram", false, this::openTelegram);
        add(card, open, 9);
        copy = button("Скопировать ссылку", false, () -> {
            ProxySettings current = ProxyService.runningSettings;
            if (current != null) clipboard("Ссылка MTProto", current.link().toString(), true);
        });
        add(card, copy, 9);

        LinearLayout traffic = card();
        traffic.addView(text("Трафик за сеанс", 18, ink, true));
        stats = text("", 15, muted, false);
        add(traffic, stats, 8);
    }

    private void settings() {
        LinearLayout update = card();
        update.addView(text("Обновление приложения", 19, ink, true));
        add(update, text("Текущая версия " + BuildConfig.VERSION_NAME, 14, muted, false), 5);
        updateStatus = text("", 14, muted, false);
        updateStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(update, updateStatus, 5);
        updateButton = button("Проверить обновления", false, this::updateAction);
        add(update, updateButton, 9);

        ProxySettings current = ProxySettings.load(this);
        LinearLayout local = card();
        local.addView(text("Локальное подключение", 19, ink, true));
        port = field(local, "Порт прокси", Integer.toString(current.port), InputType.TYPE_CLASS_NUMBER, false);
        add(local, text("Введите номер от 1024 до 65535. Если порт занят, приложение выберет свободный.",
                13, muted, false), 5);
        secret = field(local, "Секрет MTProto", current.secret,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, false);
        add(local, button("Сгенерировать новый секрет", false,
                () -> secret.setText(ProxySettings.newSecret())), 8);

        LinearLayout routing = card();
        routing.addView(text("Маршрутизация", 19, ink, true));
        pool = field(routing, "Размер пула WebSocket (0–16)", Integer.toString(current.pool),
                InputType.TYPE_CLASS_NUMBER, false);
        dc = field(routing, "Адреса датацентров (DC:IPv4)", current.dc, InputType.TYPE_CLASS_TEXT, true);
        cf = switchField(routing, "Резервный маршрут Cloudflare", current.cloudflare);
        domains = field(routing, "Домены Cloudflare через пробел", current.domains,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        add(routing, text("Пустое поле использует список Flowseal, если Cloudflare включён.", 13, muted, false), 4);
        h2 = switchField(routing, "HTTP/2 для медиа через Cloudflare", current.h2);
        workers = field(routing, "Собственные Cloudflare Workers", current.workers,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, true);
        testDc = switchField(routing, "Тестовые датацентры Telegram", current.testDc);
        cf.setOnCheckedChangeListener((button, checked) -> {
            domains.setEnabled(checked);
            h2.setEnabled(checked);
        });
        domains.setEnabled(cf.isChecked());
        h2.setEnabled(cf.isChecked());

        LinearLayout diagnostics = card();
        diagnostics.addView(text("Диагностика", 19, ink, true));
        debug = switchField(diagnostics, "Подробный журнал", current.debug);
        if (ProxyService.active()) {
            add(content, text("Новые настройки применятся после перезапуска прокси.", 14, muted, false), 9);
        }
    }

    private void updateAction() {
        switch (updates.state().phase()) {
            case AVAILABLE -> {
                installWhenReady = true;
                updates.download();
            }
            case READY -> installUpdate();
            case IDLE, CURRENT, ERROR -> updates.check();
            default -> { }
        }
        refreshUpdateControls();
    }

    private void refreshUpdateControls() {
        AppUpdates.State state = updates.state();
        AppUpdates.Phase phase = state.phase();
        updateText(updateStatus, state.description());
        updateStatus.setVisibility(state.description().isEmpty() ? View.GONE : View.VISIBLE);
        updateButton.setEnabled(phase != AppUpdates.Phase.CHECKING && phase != AppUpdates.Phase.DOWNLOADING);
        updateText(updateButton, switch (phase) {
            case AVAILABLE -> "Скачать и установить " + state.release().version();
            case READY -> "Установить " + state.release().version();
            case CHECKING -> "Проверка…";
            case DOWNLOADING -> "Загрузка…";
            default -> "Проверить обновления";
        });
        if (installWhenReady && phase == AppUpdates.Phase.READY && resumed) {
            installWhenReady = false;
            installUpdate();
        } else if (phase == AppUpdates.Phase.AVAILABLE || phase == AppUpdates.Phase.ERROR) {
            installWhenReady = false;
        }
    }

    private void installUpdate() {
        if (updates.state().phase() != AppUpdates.Phase.READY) return;
        if (!AppUpdates.apkFile(this).isFile()) {
            toast("Скачанный APK больше недоступен. Проверяем обновления снова.");
            updates.check();
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                toast("Разрешите установку приложений из TG WS Breakwall.");
                Intent permission = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", getPackageName(), null));
                startActivityForResult(permission, INSTALL_PERMISSION_REQUEST);
                return;
            }
            Uri uri = UpdateApkProvider.uri(this);
            Intent install = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, UpdateApkProvider.MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("TG WS Breakwall", uri));
            startActivity(install);
        } catch (ActivityNotFoundException | SecurityException exc) {
            toast("Android не смог открыть программу установки.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == INSTALL_PERMISSION_REQUEST && Build.VERSION.SDK_INT >= 26
                && getPackageManager().canRequestPackageInstalls()) {
            installUpdate();
        }
    }

    private void journal() {
        visibleLogVersion = -1;
        LinearLayout card = card();
        LinearLayout actions = row();
        actions.addView(button("Копировать", false, () -> {
            String log = ProxyService.logs();
            if (!log.isEmpty()) clipboard("Журнал прокси", log, false);
        }), new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(46), 1);
        second.leftMargin = dp(8);
        actions.addView(button("Очистить", false, () -> {
            ProxyService.clearLogs();
            refresh();
        }), second);
        card.addView(actions);
        logView = text("", 13, ink, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        add(card, logView, 14);
    }

    private void information() {
        LinearLayout app = card();
        app.addView(text(getString(R.string.app_name), 22, ink, true));
        add(app, text("Версия " + BuildConfig.VERSION_NAME, 14, muted, false), 4);
        add(app, text("a1deus", 17, ink, true), 16);
        add(app, text("Автор форка для Android", 14, muted, false), 4);

        LinearLayout sources = card();
        sources.addView(text("Авторы и исходники", 18, ink, true));
        add(sources, text("Flowseal", 17, ink, true), 13);
        add(sources, text("Автор оригинального проекта и ядра tg-ws-proxy", 14, muted, false), 4);
        add(sources, button("Flowseal/tg-ws-proxy", false,
                () -> openSource("https://github.com/Flowseal/tg-ws-proxy")), 8);
        add(sources, text("amurcanov", 17, ink, true), 17);
        add(sources, text("Автор Android-форка, использованного как функциональный референс",
                14, muted, false), 4);
        add(sources, button("amurcanov/tg-ws-proxy-android", false,
                () -> openSource("https://github.com/amurcanov/tg-ws-proxy-android")), 8);
    }

    private void openSource(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException exc) {
            toast("Установите браузер, чтобы открыть GitHub.");
        }
    }

    private void saveSettings() {
        try {
            int selectedPort = ProxySettings.number(fieldValue(port), 1024, 65535, "Порт");
            int selectedPool = ProxySettings.number(fieldValue(pool), 0, 16, "Размер пула");
            ProxySettings revised = new ProxySettings(selectedPort, selectedPool,
                    fieldValue(secret).toLowerCase(Locale.ROOT), fieldValue(dc), fieldValue(domains),
                    fieldValue(workers), cf.isChecked(), h2.isChecked(), testDc.isChecked(), debug.isChecked());
            revised.save(this);
            toast(ProxyService.active() ? "Сохранено. Перезапустите прокси для применения." : "Настройки сохранены");
        } catch (IllegalArgumentException exc) {
            toast(exc.getMessage());
        }
    }

    private void toggleProxy() {
        try {
            if (ProxyService.active()) {
                ProxyService.stop(this);
            } else if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    && !getPreferences(MODE_PRIVATE).getBoolean("askedForNotifications", false)) {
                getPreferences(MODE_PRIVATE).edit().putBoolean("askedForNotifications", true).apply();
                requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 1443);
            } else {
                ProxyService.start(this);
            }
        } catch (IllegalStateException exc) {
            toast("Android не разрешил запуск службы. Откройте приложение и повторите.");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1443 && !ProxyService.active() && !isFinishing()) toggleProxy();
    }

    private void openTelegram() {
        ProxySettings current = ProxyService.runningSettings;
        if (current == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, current.link()));
        } catch (ActivityNotFoundException exc) {
            toast("Установите Telegram или добавьте прокси вручную по скопированной ссылке.");
        }
    }

    private void clipboard(String name, String value, boolean sensitive) {
        ClipData clip = ClipData.newPlainText(name, value);
        if (sensitive && Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) toast("Скопировано");
    }

    private void refresh() {
        if (tab == 1) {
            refreshUpdateControls();
            return;
        }
        if (tab == 2) {
            long version = ProxyService.logVersion();
            if (version != visibleLogVersion) {
                visibleLogVersion = version;
                String log = ProxyService.logs();
                logView.setText(log.isEmpty() ? "Событий пока нет." : log);
            }
            return;
        }
        if (tab != 0 || status == null) return;
        ProxyService.Phase phase = ProxyService.phase;
        updateText(status, switch (phase) {
            case STARTING -> "Запуск…";
            case ON -> "Прокси запущен";
            case STOPPING -> "Остановка…";
            case ERROR -> "Прокси остановился";
            case OFF -> "Прокси выключен";
        });
        ProxySettings effective = ProxyService.runningSettings;
        int currentPort = effective == null ? ProxySettings.load(this).port : effective.port;
        String connection = "127.0.0.1:" + currentPort;
        if (phase == ProxyService.Phase.ON) {
            if (ProxyService.changedPort) connection += "\nСвободный порт выбран автоматически.";
            connection += "\nПодтвердите подключение прокси в Telegram.";
        }
        updateText(address, phase == ProxyService.Phase.ERROR ? ProxyService.error : connection);
        toggle.setEnabled(phase != ProxyService.Phase.STOPPING);
        updateText(toggle, ProxyService.active() ? "Остановить прокси" : "Запустить прокси");
        open.setEnabled(phase == ProxyService.Phase.ON);
        copy.setEnabled(phase == ProxyService.Phase.ON);
        ProxyService.Metrics current = ProxyService.metrics;
        updateText(stats, "Подключения: " + current.active() + " активных · " + current.total() + " всего\n"
                + "Отправлено: " + Formatter.formatShortFileSize(this, current.up()) + "   ·   Получено: "
                + Formatter.formatShortFileSize(this, current.down()));
    }

    private static void updateText(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private static String fieldValue(EditText field) {
        return field.getText().toString().trim();
    }

    private EditText field(LinearLayout parent, String name, String value, int inputType, boolean multi) {
        TextView label = text(name, 14, ink, true);
        add(parent, label, 11);
        EditText edit = new EditText(this);
        edit.setId(View.generateViewId());
        label.setLabelFor(edit.getId());
        edit.setSingleLine(!multi);
        edit.setInputType(inputType | (multi ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multi) edit.setMaxLines(4);
        edit.setTextSize(15);
        edit.setTextColor(ink);
        edit.setPadding(dp(10), dp(6), dp(10), dp(6));
        edit.setMinimumHeight(dp(44));
        edit.setBackground(box(background, border, 10));
        edit.setText(value);
        add(parent, edit, 5);
        return edit;
    }

    private Switch switchField(LinearLayout parent, String name, boolean checked) {
        Switch item = new Switch(this);
        item.setText(name);
        item.setTextColor(ink);
        item.setTextSize(15);
        item.setMinimumHeight(dp(48));
        item.setChecked(checked);
        add(parent, item, 7);
        return item;
    }

    private LinearLayout card() {
        LinearLayout result = column();
        result.setPadding(dp(15), dp(15), dp(15), dp(15));
        result.setBackground(box(surface, border, 17));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(12);
        content.addView(result, lp);
        return result;
    }

    private TextView text(String value, int size, int tint, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(tint);
        if (bold) result.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return result;
    }

    private Button button(String title, boolean primary, Runnable action) {
        Button result = new Button(this);
        result.setText(title);
        result.setAllCaps(false);
        result.setTextSize(14);
        result.setPadding(dp(8), dp(3), dp(8), dp(3));
        result.setMinHeight(dp(46));
        result.setMinimumHeight(dp(46));
        result.setMinWidth(0);
        result.setMinimumWidth(0);
        result.setStateListAnimator(null);
        int[][] states = {{-android.R.attr.state_enabled}, {}};
        ColorStateList fill = new ColorStateList(states, new int[]{background, primary ? ink : surface});
        result.setTextColor(new ColorStateList(states,
                new int[]{muted, primary ? background : ink}));
        GradientDrawable shape = box(surface, primary ? Color.TRANSPARENT : border, 12);
        shape.setColor(fill);
        result.setBackground(new RippleDrawable(ColorStateList.valueOf(pressed), shape, null));
        result.setOnClickListener(view -> action.run());
        return result;
    }

    private GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(fill);
        shape.setStroke(dp(1), stroke);
        return shape;
    }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        parent.addView(child, lp);
    }

    private int dp(int pixels) {
        return Math.round(pixels * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
