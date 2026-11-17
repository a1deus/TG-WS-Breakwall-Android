# TG WS Breakwall

Локальный MTProto-прокси для Telegram. Приложение запускает встроенное ядро Flowseal на `127.0.0.1` и передаёт параметры подключения в Telegram по ссылке `tg://proxy`.

## Установка

Android 7.0 и новее. Архитектуры `arm64-v8a` и `x86_64`.

1. Установите APK.
2. Откройте TG WS Breakwall и нажмите **Запустить прокси**.
3. Нажмите **Подключить в Telegram** и подтвердите подключение в клиенте.

В настройках доступны порт, секрет MTProto, адреса датацентров, пул WebSocket и Cloudflare. Изменения вступают в силу при следующем запуске прокси. Cloudflare включается вручную. Если оставить поле доменов пустым, будут использоваться домены из списка Flowseal. В журнале можно посмотреть события текущего процесса приложения и скопировать их.

При занятом порте приложение выбирает свободный локальный порт и сохраняет его для следующего запуска. В Telegram нужно подтвердить подключение по новой ссылке из приложения.

## Сборка

Требуются JDK 17, Android SDK Platform 35, Build Tools 35.0.0 и Python 3.13. Задайте `JAVA_HOME` и `ANDROID_HOME`. Если Python не обнаруживается автоматически, задайте `TGWS_BUILD_PYTHON` с полным путём к `python.exe`.

```powershell
.\gradlew.bat :app:assembleDebug
```

Отладочный APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Команда `.\gradlew.bat :app:assembleRelease` создаёт неподписанный релизный APK в `app/build/outputs/apk/release/`. Для установки подпишите его с помощью `apksigner` из Android Build Tools.

Файл `TG-WS-Breakwall-1.0.0.apk` подписан ключом, использованным в тестовых сборках. Хранилище ключа находится в `%LOCALAPPDATA%\TGWSProxy\signing`. Для обновлений установленной версии используйте тот же ключ.

## Исходники

- [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy/tree/6690b788432fd0f59b0f38e228494bcd6bede1ca) 1.11.4. Python-ядро включено в `app/src/main/python/proxy` с адаптацией запуска обновления списка Cloudflare. Лицензия MIT включена в APK.
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android/tree/540e588d15fbdf806f694fbe0b952ff044d39a07) использован как функциональный референс.
