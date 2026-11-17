package io.localproxy.telegramws;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

final class AppUpdates {
    private static final String REPO = "https://github.com/a1deus/TG-WS-Breakwall-Android";
    private static final String RELEASE_PATH = "/a1deus/TG-WS-Breakwall-Android/releases/tag/";
    private static final URI LATEST = URI.create(REPO + "/releases/latest");
    private static final Set<String> DOWNLOAD_HOSTS = new HashSet<>(Arrays.asList("github.com",
            "release-assets.githubusercontent.com", "objects.githubusercontent.com",
            "github-releases.githubusercontent.com"));
    private static final long MAX_APK_BYTES = 128L * 1024 * 1024;

    enum Phase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, ERROR }

    record State(Phase phase, Release release, String description) {}

    record Release(Version version, URI apk) {
        static Release fromLatestLocation(URI target) throws IOException {
            requireGitHub(target);
            String path = target.getRawPath();
            if (!"github.com".equalsIgnoreCase(target.getHost()) || path == null
                    || !path.regionMatches(true, 0, RELEASE_PATH, 0, RELEASE_PATH.length())) {
                throw new UpdateException("GitHub не вернул страницу последнего релиза.");
            }
            String tag = path.substring(RELEASE_PATH.length());
            Version version = Version.parse(tag);
            if (version == null) {
                throw new UpdateException("Тег релиза должен быть в формате vX.Y.Z или X.Y.Z.");
            }
            URI apk = URI.create(REPO + "/releases/download/" + tag
                    + "/TG-WS-Breakwall-" + version + ".apk");
            return new Release(version, apk);
        }
    }

    record Version(int major, int minor, int patch) implements Comparable<Version> {
        private static final Pattern TAG = Pattern.compile(
                "[vV]?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})");

        static Version parse(String tag) {
            if (tag == null) return null;
            Matcher match = TAG.matcher(tag);
            if (!match.matches()) return null;
            return new Version(Integer.parseInt(match.group(1)), Integer.parseInt(match.group(2)),
                    Integer.parseInt(match.group(3)));
        }

        @Override public int compareTo(Version other) {
            int first = Integer.compare(major, other.major);
            if (first != 0) return first;
            int second = Integer.compare(minor, other.minor);
            return second == 0 ? Integer.compare(patch, other.patch) : second;
        }

        @Override public String toString() {
            return major + "." + minor + "." + patch;
        }
    }

    private static AppUpdates instance;
    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile State state = new State(Phase.IDLE, null, "");

    private AppUpdates(Context context) {
        app = context.getApplicationContext();
    }

    static synchronized AppUpdates get(Context context) {
        if (instance == null) instance = new AppUpdates(context);
        return instance;
    }

    static File apkFile(Context context) {
        return new File(context.getCacheDir(), "breakwall-update.apk");
    }

    State state() {
        return state;
    }

    synchronized void check() {
        if (state.phase == Phase.CHECKING || state.phase == Phase.DOWNLOADING) return;
        state = new State(Phase.CHECKING, null, "Проверяем последний стабильный релиз на GitHub…");
        worker.execute(() -> {
            try {
                Release release = latestRelease();
                Version installed = Version.parse(BuildConfig.VERSION_NAME);
                if (installed == null) throw new IOException("Не удалось определить текущую версию.");
                state = release.version.compareTo(installed) > 0
                        ? new State(Phase.AVAILABLE, release, "Доступна версия " + release.version + ".")
                        : new State(Phase.CURRENT, null, "У вас установлена актуальная версия.");
            } catch (IOException | RuntimeException exc) {
                state = new State(Phase.ERROR, null,
                        description(exc, "Не удалось проверить обновления. Проверьте доступ к GitHub."));
            }
        });
    }

    synchronized void download() {
        if (state.phase != Phase.AVAILABLE) return;
        Release release = state.release;
        state = new State(Phase.DOWNLOADING, release, "Загружаем APK с GitHub…");
        worker.execute(() -> {
            File apk = apkFile(app);
            File part = new File(app.getCacheDir(), "breakwall-update-part.apk");
            try {
                downloadApk(release, part);
                verifyApk(part, release);
                if (!part.renameTo(apk)) throw new IOException("Не удалось сохранить обновление.");
                state = new State(Phase.READY, release, "APK проверен. Подтвердите установку в Android.");
            } catch (IOException | RuntimeException | PackageManager.NameNotFoundException exc) {
                state = new State(Phase.AVAILABLE, release,
                        description(exc, "Не удалось скачать или проверить обновление."));
            } finally {
                if (part.isFile()) part.delete();
            }
        });
    }

    private static String description(Exception failure, String fallback) {
        return failure instanceof UpdateException ? failure.getMessage()
                : failure instanceof java.net.SocketTimeoutException
                    ? "GitHub не ответил вовремя. Попробуйте ещё раз." : fallback;
    }

    private static Release latestRelease() throws IOException {
        HttpsURLConnection request = open(LATEST, "text/html");
        try {
            int code = request.getResponseCode();
            if (code == 404) throw new UpdateException("У проекта пока нет стабильного релиза.");
            if (!isRedirect(code)) throw new UpdateException("GitHub не вернул ссылку на последний релиз.");
            return Release.fromLatestLocation(resolveRedirect(LATEST, request));
        } finally {
            request.disconnect();
        }
    }

    private void downloadApk(Release release, File part) throws IOException {
        URI url = release.apk;
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpsURLConnection request = open(url, "application/octet-stream");
            try {
                int code = request.getResponseCode();
                if (isRedirect(code)) {
                    url = resolveRedirect(url, request);
                    continue;
                }
                if (code == 404) {
                    throw new UpdateException("В релизе нет файла TG-WS-Breakwall-" + release.version + ".apk.");
                }
                if (code != 200) throw new UpdateException("GitHub не отдал APK. Код HTTP " + code + ".");
                saveResponse(request, part, release);
                return;
            } finally {
                request.disconnect();
            }
        }
        throw new UpdateException("Слишком много перенаправлений при загрузке APK.");
    }

    private void saveResponse(HttpsURLConnection request, File part, Release release) throws IOException {
        long expected = request.getContentLengthLong();
        if (expected == 0 || expected > MAX_APK_BYTES) {
            throw new UpdateException("Неверный размер APK обновления.");
        }
        long total = 0;
        int reportedPercent = -1;
        try (InputStream input = request.getInputStream(); FileOutputStream output = new FileOutputStream(part)) {
            byte[] bytes = new byte[64 * 1024];
            int length;
            while ((length = input.read(bytes)) != -1) {
                total += length;
                if (total > MAX_APK_BYTES) throw new UpdateException("APK обновления слишком большой.");
                output.write(bytes, 0, length);
                if (expected > 0) {
                    int percent = (int) Math.min(100, total * 100 / expected);
                    if (percent != reportedPercent) {
                        state = new State(Phase.DOWNLOADING, release, "Загрузка APK — " + percent + "%");
                        reportedPercent = percent;
                    }
                }
            }
        }
        if (total == 0 || (expected > 0 && total != expected)) {
            throw new UpdateException("APK скачан не полностью. Повторите загрузку.");
        }
    }

    private void verifyApk(File apk, Release release) throws IOException, PackageManager.NameNotFoundException {
        PackageManager manager = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null || !app.getPackageName().equals(archive.packageName)) {
            throw new UpdateException("В релизе найден APK другого приложения или повреждённый файл.");
        }
        PackageInfo installed = manager.getPackageInfo(app.getPackageName(), flags);
        if (!release.version.equals(Version.parse(archive.versionName))
                || versionCode(archive) <= versionCode(installed)) {
            throw new UpdateException("Версия или внутренний номер APK не подходят для обновления.");
        }
        if (archive.applicationInfo != null && archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new UpdateException("Эта версия приложения требует более новую версию Android.");
        }
        Signature[] candidate = signers(archive);
        Signature[] current = signers(installed);
        if (candidate == null || candidate.length == 0 || current == null || current.length == 0
                || !new HashSet<>(Arrays.asList(candidate)).equals(new HashSet<>(Arrays.asList(current)))) {
            throw new UpdateException("Подпись APK не совпадает с подписью установленного приложения.");
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    private static HttpsURLConnection open(URI url, String accept) throws IOException {
        requireGitHub(url);
        HttpsURLConnection request = (HttpsURLConnection) url.toURL().openConnection();
        request.setConnectTimeout(12_000);
        request.setReadTimeout(25_000);
        request.setInstanceFollowRedirects(false);
        request.setUseCaches(false);
        request.setRequestProperty("User-Agent", "TG-WS-Breakwall");
        request.setRequestProperty("Accept", accept);
        request.setRequestProperty("Accept-Encoding", "identity");
        return request;
    }

    private static URI resolveRedirect(URI previous, HttpsURLConnection request) throws IOException {
        String location = request.getHeaderField("Location");
        if (location == null) throw new UpdateException("GitHub не указал адрес загрузки.");
        URI next;
        try {
            next = previous.resolve(location);
        } catch (IllegalArgumentException exc) {
            throw new UpdateException("GitHub вернул некорректный адрес.");
        }
        requireGitHub(next);
        return next;
    }

    private static void requireGitHub(URI url) throws IOException {
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null
                || !DOWNLOAD_HOSTS.contains(url.getHost().toLowerCase(Locale.ROOT))
                || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443)) {
            throw new UpdateException("Адрес обновления находится вне GitHub.");
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static final class UpdateException extends IOException {
        UpdateException(String message) {
            super(message);
        }
    }
}
