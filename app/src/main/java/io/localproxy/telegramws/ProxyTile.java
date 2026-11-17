package io.localproxy.telegramws;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public final class ProxyTile extends TileService {
    @Override public void onStartListening() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(ProxyService.active() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(switch (ProxyService.phase) {
                case STARTING -> "Запуск…";
                case ON -> "Прокси запущен";
                case STOPPING -> "Остановка…";
                default -> "Прокси остановлен";
            });
        }
        tile.updateTile();
    }

    @Override public void onClick() {
        if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated") // The Intent overload is used only before API 34.
    private void toggle() {
        try {
            if (ProxyService.active()) ProxyService.stop(this);
            else ProxyService.start(this);
        } catch (IllegalStateException exc) {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, open,
                        PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(open);
            }
        }
    }
}
