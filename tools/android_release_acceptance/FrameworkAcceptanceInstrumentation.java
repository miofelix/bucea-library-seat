package cn.edu.bucea.library.releaseacceptance;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.Instrumentation;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.security.NetworkSecurityPolicy;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SDK-only release checks, independent of the application's obfuscated dependencies. */
public final class FrameworkAcceptanceInstrumentation extends Instrumentation {
    private Bundle arguments;
    private final Bundle transportTelemetry = new Bundle();
    private static final String RELEASE_PREFS = "update_release_acceptance";
    private static final String TRANSPORT_PREFS = "instrumentation_live_update_transport";
    private static final String OLD_RELEASE_SHA256 = "67c90dc11effc60329cbbdf3e4941e21b3b1608c1961505bb90ddbd02c733aab";

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        arguments = args == null ? new Bundle() : args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        String step = arguments.getString("step", "");
        try {
            require("true".equals(arguments.getString("live_update_acceptance")), "Explicit release acceptance opt-in is required");
            if ("final".equals(step)) checkFinalRelease();
            else if ("snapshot-state".equals(step)) snapshotRawState();
            else if ("check-state".equals(step)) assertRawState();
            else if ("no-task".equals(step)) checkNoTask();
            else if ("download-state".equals(step)) snapshotDownloadState();
            else if ("endpoint".equals(step)) inspectEndpoint();
            else if ("campus-endpoint".equals(step)) inspectCampusEndpoint();
            else if ("record-task".equals(step)) recordReadyTask();
            else if ("transport".equals(step)) checkTransport();
            else if ("cancel-transport".equals(step)) cancelTrackedTransport();
            else if ("cleanup".equals(step)) cleanupAcceptance();
            else throw new AssertionError("Choose final, snapshot-state, check-state, no-task, download-state, endpoint, campus-endpoint, record-task, transport, cancel-transport or cleanup acceptance step");
            result.putAll(transportTelemetry);
            result.putString("result", "PASS");
            result.putString("step", step);
            result.putString("stream", "PASS: framework release acceptance " + step + "\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) {
            // Assertion messages are fixed statements; no private preference values are printed.
            String reason = failure instanceof AssertionError ? failure.getMessage() : failure.getClass().getSimpleName();
            result.putAll(transportTelemetry);
            result.putString("result", "FAIL");
            result.putString("step", step);
            result.putString("stream", "FAIL: framework release acceptance: " + reason + "\n");
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void checkNoTask() throws Exception {
        File record = new File(getTargetContext().getFilesDir(), "updates/task.json");
        int records = taskRecordFiles(record);
        int rows = managedDownloadRows();
        transportTelemetry.putInt("task_record_files", records);
        transportTelemetry.putInt("managed_download_rows", rows);
        require(records == 0, "Closing or deferring the prompt must not create an update task record");
        require(rows == 0, "Closing or deferring the prompt must not create a managed APK download");
    }

    private int managedDownloadRows() throws Exception {
        Context context = getTargetContext();
        File external = context.getExternalFilesDir(null);
        require(external != null, "Managed download storage must be available for inspection");
        File directory = new File(external, "updates").getCanonicalFile();
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        int count = 0;
        try (Cursor cursor = manager.query(new DownloadManager.Query())) {
            require(cursor != null, "System download ownership inspection must return a cursor");
            int localUri = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI);
            while (cursor.moveToNext()) {
                String value = cursor.getString(localUri);
                if (value == null) continue;
                Uri uri = Uri.parse(value);
                if (!"file".equals(uri.getScheme()) || uri.getPath() == null) continue;
                File file = new File(uri.getPath()).getCanonicalFile();
                if (directory.equals(file.getParentFile()) && file.getName().matches(
                    "download-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.apk")) count++;
            }
        }
        return count;
    }

    private static int taskRecordFiles(File record) {
        return (record.exists() ? 1 : 0) + (new File(record.getPath() + ".bak").exists() ? 1 : 0)
            + (new File(record.getPath() + ".new").exists() ? 1 : 0);
    }

    private void inspectEndpoint() {
        Context context = getTargetContext();
        boolean permission = context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED;
        transportTelemetry.putBoolean("has_network_state_permission", permission);
        if (permission) {
            try {
                ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
                Network network = connectivity.getActiveNetwork();
                NetworkCapabilities capabilities = network == null ? null : connectivity.getNetworkCapabilities(network);
                transportTelemetry.putBoolean("active_network", network != null);
                transportTelemetry.putBoolean("connected", capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
                transportTelemetry.putBoolean("validated", capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
                transportTelemetry.putBoolean("has_wifi", capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            } catch (Exception diagnosticFailure) {
                transportTelemetry.putString("connectivity_exception_class", diagnosticFailure.getClass().getName());
            }
        }
        HttpURLConnection connection = null;
        transportTelemetry.putInt("http_status", -1);
        try {
            connection = (HttpURLConnection) new URL("https://bucea-library-seat.miofelix.com/updates/latest.json").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            connection.setAllowUserInteraction(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "okhttp/4.12.0");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");
            // The app's campus clients use separate OkHttp jars; no credentials are read.
            connection.setRequestProperty("Cookie", "");
            connection.setRequestProperty("Authorization", "");
            int status = connection.getResponseCode();
            transportTelemetry.putInt("http_status", status);
            if (status == 200) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) {
                        require(output.size() + count <= 131072, "The diagnostic manifest must remain within the normal size limit");
                        output.write(buffer, 0, count);
                    }
                }
                JSONObject manifest = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                transportTelemetry.putLong("manifest_version_code", manifest.getLong("versionCode"));
                transportTelemetry.putString("manifest_version_name", manifest.getString("versionName"));
                transportTelemetry.putBoolean("manifest_prerelease", manifest.getBoolean("prerelease"));
                JSONObject apk = manifest.getJSONObject("apk");
                transportTelemetry.putLong("manifest_asset_id", apk.getLong("assetId"));
                transportTelemetry.putLong("manifest_apk_bytes", apk.getLong("size"));
                String digest = apk.getString("sha256");
                require(digest.matches("[0-9a-f]{64}"), "The diagnostic manifest must record a valid public digest");
                transportTelemetry.putString("manifest_sha256", digest);
            }
            require(status == 200, "The fixed public update endpoint must return HTTP 200");
        } catch (Exception failure) {
            transportTelemetry.putString("exception_class", failure.getClass().getName());
            throw new AssertionError("The SDK-only fixed update endpoint diagnostic request failed");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Read-only, application-UID HTTP reachability; never a credential/login submission. */
    private void inspectCampusEndpoint() {
        long began = SystemClock.elapsedRealtime();
        transportTelemetry.putInt("http_status", -1);
        transportTelemetry.putBoolean("http_reachable", false);
        transportTelemetry.putBoolean("login_form_marker", false);
        transportTelemetry.putBoolean("cleartext_permitted", NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("10.1.20.7"));
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://10.1.20.7/login?targetUri=%2F").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setAllowUserInteraction(false);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "BUCEA-Library-Android/0.1");
            connection.setRequestProperty("Cookie", "");
            connection.setRequestProperty("Authorization", "");
            int status = connection.getResponseCode();
            transportTelemetry.putInt("http_status", status);
            require(status >= 100 && status <= 599, "Campus probe must receive a valid HTTP response status");
            transportTelemetry.putBoolean("http_reachable", true);
            transportTelemetry.putBoolean("same_campus_redirect", false);
            if (status >= 300 && status <= 399) {
                String location = connection.getHeaderField("Location");
                if (location != null) {
                    URL destination = new URL(connection.getURL(), location);
                    transportTelemetry.putBoolean("same_campus_redirect", "10.1.20.7".equals(destination.getHost()));
                }
            }
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                try (InputStream input = stream) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while (output.size() < 131072 && (count = input.read(buffer, 0, Math.min(buffer.length, 131072 - output.size()))) >= 0) {
                        output.write(buffer, 0, count);
                    }
                }
                String body = new String(output.toByteArray(), StandardCharsets.UTF_8);
                boolean loginMarker = body.contains("/auth/signIn") ||
                    (body.contains("name=\"username\"") && body.contains("name=\"password\"")) ||
                    (body.contains("name='username'") && body.contains("name='password'"));
                transportTelemetry.putBoolean("login_form_marker", loginMarker);
            }
        } catch (Exception failure) {
            transportTelemetry.putString("exception_class", failure.getClass().getName());
            Throwable root = failure;
            for (int depth = 0; depth < 16 && root.getCause() != null && root.getCause() != root; depth++) root = root.getCause();
            transportTelemetry.putString("root_cause_class", root.getClass().getName());
            // Receiving any HTTP status proves reachability even if reading its body fails.
            if (!transportTelemetry.getBoolean("http_reachable")) {
                throw new AssertionError("The application-UID campus HTTP probe did not receive a response");
            }
        } finally {
            transportTelemetry.putLong("elapsed_ms", SystemClock.elapsedRealtime() - began);
            if (connection != null) connection.disconnect();
        }
    }

    private void snapshotDownloadState() throws Exception {
        Context context = getTargetContext();
        File record = new File(context.getFilesDir(), "updates/task.json");
        require(record.isFile(), "The native updater must have persisted an actual task");
        JSONObject json = new JSONObject(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
        JSONObject manifest = json.getJSONObject("manifest");
        require(context.getPackageName().equals(manifest.getString("packageName")), "The native task must target this application");
        require(manifest.getLong("versionCode") == expectedVersionCode(), "The native task must target the expected release code");
        require(expectedVersionName().equals(manifest.getString("versionName")), "The native task must target the expected release name");
        String token = json.getString("token");
        require(validToken(token), "The native task must have a valid unique token");
        String phase = json.getString("phase");
        require("ENQUEUING".equals(phase) || "DOWNLOADING".equals(phase) || "VERIFYING".equals(phase) ||
            "READY".equals(phase) || "FAILED".equals(phase), "The native task must have a recognized update phase");
        long id = json.isNull("downloadId") ? -1 : json.getLong("downloadId");
        require(id == -1 || id > 0, "A recorded native download ID must be positive");
        transportTelemetry.putString("task_phase", phase);
        transportTelemetry.putLong("download_id", id);
        transportTelemetry.putInt("task_record_files", taskRecordFiles(record));
        boolean requireWait = "true".equals(arguments.getString("require_network_wait"));
        require(!requireWait || ("DOWNLOADING".equals(phase) && id > 0), "Offline queue evidence requires a native downloading task");
        File external = context.getExternalFilesDir(null);
        require(external != null, "Native update download storage must be available for inspection");
        File expected = new File(new File(external, "updates"), "download-" + token + ".apk").getCanonicalFile();
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        long deadline = SystemClock.elapsedRealtime() + 5000;
        while (true) {
            int status = -1;
            int reason = -1;
            long bytes = 0;
            long total = -1;
            boolean present = false;
            if (id > 0) {
                try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                    require(cursor != null, "Native download state inspection must return a cursor");
                    if (cursor.moveToFirst()) {
                        present = true;
                        Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                        require("file".equals(uri.getScheme()) && uri.getPath() != null &&
                            expected.equals(new File(uri.getPath()).getCanonicalFile()), "The native task ID must identify its exact managed update file");
                        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                        reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                        bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                    }
                }
            }
            transportTelemetry.putBoolean("download_present", present);
            transportTelemetry.putInt("status", status);
            transportTelemetry.putInt("reason", reason);
            transportTelemetry.putLong("downloaded_bytes", bytes);
            transportTelemetry.putLong("total_bytes", total);
            transportTelemetry.putInt("managed_download_rows", managedDownloadRows());
            if (!requireWait) return;
            if (present && (status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED)) return;
            require(SystemClock.elapsedRealtime() < deadline, "Offline native download must become pending or paused within the bounded transition");
            SystemClock.sleep(200);
        }
    }

    @SuppressWarnings("deprecation")
    private void checkFinalRelease() throws Exception {
        final Context context = getTargetContext();
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        long version = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        require(version == expectedVersionCode(), "Actual installed release must have the expected version code");
        require(expectedVersionName().equals(installed.versionName), "Actual installed release must have the expected version name");
        require(installed.applicationInfo != null && (installed.applicationInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0,
            "Actual installed release must not be debuggable");
        SharedPreferences acceptance = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE);
        String expected = acceptance.getString("candidate_digest", null);
        require(expected != null && expected.matches("[0-9a-f]{64}"), "Verified candidate digest must have been recorded");
        String suppliedDigest = arguments.getString("expected_sha256", null);
        require(suppliedDigest == null || suppliedDigest.equals(expected), "Recorded candidate digest must match the supplied release digest");
        try (InputStream input = new FileInputStream(installed.applicationInfo.sourceDir)) {
            require(expected.equals(fileDigest(input)), "Installed APK bytes must match the verified final candidate");
        }
        assertRawState();
        String token = acceptance.getString("candidate_token", null);
        require(validToken(token), "A valid unique acceptance candidate token must have been recorded");
        String internalPath = "updates/install/" + token + ".apk";
        String externalPath = "updates/download-" + token + ".apk";
        require(internalPath.equals(acceptance.getString("candidate_internal_path", null)), "Tracked internal path must remain in the install directory");
        require(externalPath.equals(acceptance.getString("candidate_external_path", null)), "Tracked external path must remain in the update directory");
        File record = new File(context.getFilesDir(), "updates/task.json");
        if (record.exists()) {
            JSONObject json = new JSONObject(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
            require(token.equals(json.getString("token")), "The actual app may clean only the acceptance run's tracked task");
            require(json.getJSONObject("manifest").getLong("versionCode") == expectedVersionCode(), "The tracked update must target the final release");
            require(expectedVersionName().equals(json.getJSONObject("manifest").getString("versionName")), "The tracked update must have the expected final release name");
        }
        Intent launcher = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        require(launcher != null, "The actual release launcher must be available");
        final Activity activity = startActivitySync(launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            File ready = new File(context.getFilesDir(), internalPath);
            File part = new File(context.getFilesDir(), "updates/install/" + token + ".part");
            File backup = new File(record.getPath() + ".bak");
            File writing = new File(record.getPath() + ".new");
            File externalDir = context.getExternalFilesDir(null);
            File downloaded = externalDir == null ? null : new File(externalDir, externalPath);
            long deadline = SystemClock.elapsedRealtime() + 20000;
            while (SystemClock.elapsedRealtime() < deadline &&
                (record.exists() || backup.exists() || writing.exists() || ready.exists() || part.exists() || (downloaded != null && downloaded.exists()))) {
                SystemClock.sleep(200);
            }
            require(!record.exists() && !backup.exists() && !writing.exists(), "Actual release startup must clear the installed update record");
            require(!ready.exists() && !part.exists(), "Actual release startup must remove the tracked install and partial files");
            require(downloaded == null || !downloaded.exists(), "Actual release startup must remove the tracked update download file");
            assertRawState();
        } finally {
            runOnMainSync(new Runnable() { @Override public void run() { activity.finish(); } });
        }
    }

    private void recordReadyTask() throws Exception {
        Context context = getTargetContext();
        File record = new File(context.getFilesDir(), "updates/task.json");
        require(record.isFile(), "The native updater must have persisted an actual task");
        JSONObject json = new JSONObject(new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8));
        require("READY".equals(json.getString("phase")), "The native updater must finish verification before recording its task");
        String token = json.getString("token");
        require(validToken(token), "The native task must have a valid unique token");
        JSONObject manifest = json.getJSONObject("manifest");
        require(context.getPackageName().equals(manifest.getString("packageName")), "The native task must target this application");
        require(manifest.getLong("versionCode") == expectedVersionCode(), "The native task must target the expected release code");
        require(expectedVersionName().equals(manifest.getString("versionName")), "The native task must target the expected release name");
        JSONObject apk = manifest.getJSONObject("apk");
        String digest = apk.getString("sha256");
        require(digest.matches("[0-9a-f]{64}"), "The native task must record a valid APK digest");
        String suppliedDigest = arguments.getString("expected_sha256", null);
        require(suppliedDigest == null || suppliedDigest.equals(digest), "The native task must match the supplied release APK digest");
        String internalPath = "updates/install/" + token + ".apk";
        String externalPath = "updates/download-" + token + ".apk";
        File verified = new File(context.getFilesDir(), internalPath);
        require(verified.isFile() && verified.length() == apk.getLong("size"), "The native verified APK must retain the expected size");
        try (InputStream input = new FileInputStream(verified)) {
            require(digest.equals(fileDigest(input)), "The native verified APK bytes must match the confirmed release digest");
        }
        SharedPreferences acceptance = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE);
        require(acceptance.getString("salt", null) != null && acceptance.getString("data_digest", null) != null,
            "A private raw state baseline must exist before recording the native task");
        require(acceptance.edit().putString("candidate_token", token).putString("candidate_digest", digest)
            .putString("candidate_internal_path", internalPath).putString("candidate_external_path", externalPath).commit(),
            "The native task readback metadata must persist without replacing the raw baseline");
        transportTelemetry.putString("task_phase", "READY");
        transportTelemetry.putInt("task_record_files", taskRecordFiles(record));
        transportTelemetry.putInt("managed_download_rows", managedDownloadRows());
        transportTelemetry.putString("candidate_sha256", digest);
    }

    private long expectedVersionCode() {
        String value = arguments.getString("expected_version_code", "9");
        try {
            long version = Long.parseLong(value);
            require(version > 0 && version <= 2100000000L, "Expected release version code must be a valid positive integer");
            return version;
        } catch (NumberFormatException invalid) {
            throw new AssertionError("Expected release version code must be a valid positive integer");
        }
    }

    private String expectedVersionName() {
        String name = arguments.getString("expected_version_name", "0.3.4");
        require(name != null && !name.isEmpty() && name.length() <= 100, "Expected release version name must be valid text");
        return name;
    }

    private void snapshotRawState() throws Exception {
        byte[] salt = new byte[32];
        new SecureRandom().nextBytes(salt);
        String[] digests = rawStateDigests(salt);
        SharedPreferences acceptance = getTargetContext().getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE);
        require(acceptance.edit().putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("data_digest", digests[0]).putString("cookies_digest", digests[1]).commit(),
            "Only salted private state digests must persist for overwrite acceptance");
    }

    private void assertRawState() throws Exception {
        Context context = getTargetContext();
        SharedPreferences acceptance = context.getSharedPreferences(RELEASE_PREFS, Context.MODE_PRIVATE);
        String encoded = acceptance.getString("salt", null);
        require(encoded != null, "A private upgrade snapshot must have been recorded");
        byte[] salt = Base64.decode(encoded, Base64.NO_WRAP);
        require(salt.length == 32, "The private upgrade snapshot must have a full random salt");
        String[] digests = rawStateDigests(salt);
        require(digests[0].equals(acceptance.getString("data_digest", null)), "Encrypted accounts, choices, preferences, sign-out, manual favorites and appearance must be preserved");
        if ("true".equals(arguments.getString("compare_upgrade_cookies"))) {
            require(digests[1].equals(acceptance.getString("cookies_digest", null)), "Saved encrypted session cookies must be preserved");
        }
    }

    private String[] rawStateDigests(byte[] salt) throws Exception {
        Context context = getTargetContext();
        SaltedDigest data = new SaltedDigest(salt, "release-data-v1");
        SaltedDigest cookies = new SaltedDigest(salt, "release-cookies-v1");
        SharedPreferences vault = context.getSharedPreferences("library_vault", Context.MODE_PRIVATE);
        data.addPreferences("library_vault", vault, 1);
        cookies.addPreferences("library_vault", vault, 2);
        data.addPreferences("seat_favorites", context.getSharedPreferences("seat_favorites", Context.MODE_PRIVATE), 0);
        data.addPreferences("library_appearance", context.getSharedPreferences("library_appearance", Context.MODE_PRIVATE), 0);
        return new String[] { data.finish(), cookies.finish() };
    }

    private void checkTransport() throws Exception {
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(TRANSPORT_PREFS, Context.MODE_PRIVATE);
        long id = preferences.getLong("download_id", -1);
        String token = preferences.getString("token", null);
        require(id > 0 && validToken(token), "A valid isolated HTTPS transport fixture must have been recorded");
        require(preferences.getInt("enqueued_pid", -1) != android.os.Process.myPid(), "Transport check must run after the enqueue process has ended");
        File external = context.getExternalFilesDir(null);
        require(external != null, "External transport storage must be available");
        File file = new File(new File(external, "update-transport"), "download-" + token + ".apk");
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        long deadline = SystemClock.elapsedRealtime() + 60000;
        long bytes = 0;
        long total = 0;
        long lastReported = 0;
        while (true) {
            try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
                require(cursor != null && cursor.moveToFirst(), "Tracked system download must survive the process restart");
                Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                require("file".equals(uri.getScheme()) && uri.getPath() != null &&
                    file.getCanonicalFile().equals(new File(uri.getPath()).getCanonicalFile()), "Tracked download ID must identify only the isolated transport fixture");
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                transportTelemetry.putInt("status", status);
                transportTelemetry.putInt("reason", reason);
                transportTelemetry.putLong("downloaded_bytes", bytes);
                transportTelemetry.putLong("total_bytes", total);
                long now = SystemClock.elapsedRealtime();
                if (lastReported == 0 || now - lastReported >= 5000 || status == DownloadManager.STATUS_SUCCESSFUL) {
                    sendStatus(0, new Bundle(transportTelemetry));
                    lastReported = now;
                }
                require(status != DownloadManager.STATUS_FAILED, "Tracked HTTPS transport must not have failed");
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    break;
                }
            }
            require(SystemClock.elapsedRealtime() < deadline, "Tracked transport has not completed within the bounded wait");
            SystemClock.sleep(200);
        }
        require(bytes > 0 && bytes == total, "Completed system transfer must contain all expected bytes");
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(manager.openDownloadedFile(id))) {
            require(OLD_RELEASE_SHA256.equals(fileDigest(input)), "Transferred APK digest must match the published old release");
        }
        cancelTrackedTransport();
    }

    private void cancelTrackedTransport() throws Exception {
        Context context = getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences(TRANSPORT_PREFS, Context.MODE_PRIVATE);
        if (preferences.getAll().isEmpty()) return;
        long id = preferences.getLong("download_id", -1);
        String token = preferences.getString("token", null);
        require(id > 0 && validToken(token), "Tracked transport cleanup requires a valid row ID and unique token");
        File external = context.getExternalFilesDir(null);
        require(external != null, "Tracked transport storage must be available for scoped cleanup");
        File file = new File(new File(external, "update-transport"), "download-" + token + ".apk");
        DownloadManager manager = context.getSystemService(DownloadManager.class);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor != null && cursor.moveToFirst()) {
                Uri uri = Uri.parse(cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)));
                require("file".equals(uri.getScheme()) && uri.getPath() != null &&
                    file.getCanonicalFile().equals(new File(uri.getPath()).getCanonicalFile()),
                    "Refuse transport cleanup when the tracked ID identifies an unrelated file");
                transportTelemetry.putInt("status", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)));
                transportTelemetry.putInt("reason", cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)));
                transportTelemetry.putLong("downloaded_bytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)));
                transportTelemetry.putLong("total_bytes", cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)));
            }
        }
        manager.remove(id);
        try (Cursor cursor = manager.query(new DownloadManager.Query().setFilterById(id))) {
            require(cursor == null || !cursor.moveToFirst(), "Only the tracked transport row must be removed");
        }
        if (file.exists()) file.delete();
        require(!file.exists(), "Only the tracked transport file must be removed");
        require(preferences.edit().clear().commit(), "Only transport acceptance preferences must be cleared");
        require(context.deleteSharedPreferences(TRANSPORT_PREFS), "Only tracked transport acceptance preferences must be removed");
    }

    private void cleanupAcceptance() throws Exception {
        cancelTrackedTransport();
        Context context = getTargetContext();
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            File candidate = new File(external, "update-acceptance/candidate.apk");
            require(!candidate.exists() || candidate.delete(), "Only the final acceptance candidate APK must be removed");
        }
        require(context.deleteSharedPreferences("update_install_acceptance"), "Only semantic acceptance snapshot preferences must be removed");
        require(context.deleteSharedPreferences(RELEASE_PREFS), "Only raw release acceptance snapshot preferences must be removed");
    }

    private static boolean validToken(String token) {
        if (token == null) return false;
        try { return UUID.fromString(token).toString().equals(token); }
        catch (IllegalArgumentException ignored) { return false; }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String fileDigest(InputStream input) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        int count;
        while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", value));
        return output.toString();
    }

    private static final class SaltedDigest {
        private final MessageDigest digest;
        SaltedDigest(byte[] salt, String namespace) throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            add(namespace);
        }
        void add(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
            digest.update(bytes);
        }
        void addPreferences(String name, SharedPreferences preferences, int filter) {
            add(name);
            Map<String, ?> all = preferences.getAll();
            List<String> keys = new ArrayList<String>();
            for (String key : all.keySet()) {
                boolean cookie = key.startsWith("cookies_");
                if (filter == 0 || (filter == 1 && !cookie) || (filter == 2 && cookie)) keys.add(key);
            }
            Collections.sort(keys);
            add(Integer.toString(keys.size()));
            for (String key : keys) {
                add(key);
                Object value = all.get(key);
                if (value == null) add("null");
                else if (value instanceof String) { add("string"); add((String) value); }
                else if (value instanceof Boolean) { add("boolean"); add(value.toString()); }
                else if (value instanceof Integer) { add("int"); add(value.toString()); }
                else if (value instanceof Long) { add("long"); add(value.toString()); }
                else if (value instanceof Float) { add("float"); add(Integer.toString(Float.floatToRawIntBits((Float) value))); }
                else if (value instanceof Set) {
                    add("string-set");
                    List<String> values = new ArrayList<String>();
                    for (Object item : (Set<?>) value) { require(item instanceof String, "Private preference set must contain text"); values.add((String) item); }
                    Collections.sort(values);
                    add(Integer.toString(values.size()));
                    for (String item : values) add(item);
                } else throw new AssertionError("Unsupported private preference type");
            }
        }
        String finish() { return hex(digest.digest()); }
    }
}
