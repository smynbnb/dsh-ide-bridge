package dsh.web.plugin;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the hand-off file that the DeepSeek Harness side plugin writes.
 *
 * <p>The desktop build keeps its browser launch token in process memory only:
 * {@code dsh web} prints {@code dsh web: http://host:port/?token=...} once at
 * startup, but the Electron shell swallows that line, so no other process can
 * reconstruct the URL. A DSH plugin running inside the same process can call
 * {@code connection.authenticatedUrl(...)} and drop the result here.</p>
 *
 * <p>File contract (both sides must agree):</p>
 * <pre>{@code
 * {
 *   "schema": 1,
 *   "url": "http://127.0.0.1:61030/?token=...",
 *   "pid": 32804,
 *   "startedAt": 1790000000000,
 *   "version": "0.2.1-rc.2",
 *   "updatedAt": 1790000000000
 * }
 * }</pre>
 *
 * <p>The daemon never deletes the file on a crash, so a live {@code pid} check
 * is mandatory before trusting the contents.</p>
 */
public final class BridgeConfig {

    /** System property overriding the bridge file location (useful for testing). */
    public static final String PATH_PROPERTY = "dsh.bridge.path";

    /** Connection attempt budget for the readiness probe, in milliseconds. */
    private static final int PROBE_TIMEOUT_MS = 2500;

    public enum Health {
        /** File present, process alive, HTTP endpoint answers with a 3xx. */
        OK,
        /** No bridge file: the desktop build is not running (or not instrumented). */
        MISSING,
        /** File exists but is unreadable or lacks required fields. */
        MALFORMED,
        /** File exists but its recorded process is gone (stale after a crash). */
        DEAD_PID,
        /** Endpoint answers 401/403: the launch token was rotated by a restart. */
        UNAUTHORIZED,
        /** Endpoint unreachable: port closed, listener not ready, or a firewall drop. */
        UNREACHABLE
    }

    /** Immutable view of one bridge file. */
    public static final class Snapshot {
        public final String url;
        public final long pid;
        public final long updatedAt;
        public final String version;
        public final long size;
        public final long modifiedAt;

        Snapshot(String url, long pid, long updatedAt, String version, long size, long modifiedAt) {
            this.url = url;
            this.pid = pid;
            this.updatedAt = updatedAt;
            this.version = version;
            this.size = size;
            this.modifiedAt = modifiedAt;
        }
    }

    /** One complete probe result. */
    public static final class Result {
        public final Health health;
        public final Snapshot snapshot;
        public final String message;

        Result(Health health, Snapshot snapshot, String message) {
            this.health = health;
            this.snapshot = snapshot;
            this.message = message;
        }

        public boolean isReady() {
            return health == Health.OK;
        }
    }

    private static final Pattern STRING_FIELD =
            Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern NUMBER_FIELD =
            Pattern.compile("\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*(-?\\d+)");

    private BridgeConfig() {
    }

    /** Resolves the agreed hand-off file path. */
    public static Path file() {
        String override = System.getProperty(PATH_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Paths.get(override);
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null && !localAppData.isBlank()
                ? Paths.get(localAppData)
                : Paths.get(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve("dsh-ide-bridge").resolve("bridge.json");
    }

    /**
     * Reads the bridge file and verifies the endpoint it advertises.
     *
     * <p>Performs one HTTP request, so call it off the EDT.</p>
     */
    public static Result probe() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return new Result(Health.MISSING, null, "未找到 " + path);
        }

        long size;
        long modified;
        String text;
        try {
            size = Files.size(path);
            modified = Files.getLastModifiedTime(path).toMillis();
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return new Result(Health.MALFORMED, null, "无法读取 " + path + "：" + e.getMessage());
        }

        String url = stringField(text, "url");
        Long pid = numberField(text, "pid");
        Long updatedAt = numberField(text, "updatedAt");
        String version = stringField(text, "version");

        if (url == null || url.isBlank() || pid == null) {
            return new Result(Health.MALFORMED, null, "桥接文件缺少 url 或 pid 字段。");
        }
        Snapshot snapshot = new Snapshot(url, pid, updatedAt == null ? 0L : updatedAt,
                version == null ? "" : version, size, modified);

        if (!isProcessAlive(pid)) {
            return new Result(Health.DEAD_PID, snapshot,
                    "记录的进程 " + pid + " 已不存在，桌面版可能崩溃退出了。");
        }

        Health http = httpProbe(url);
        if (http != Health.OK) {
            return new Result(http, snapshot, messageFor(http));
        }
        return new Result(Health.OK, snapshot, "已连接。");
    }

    private static boolean isProcessAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /**
     * Probes an explicitly supplied URL, bypassing the bridge file.
     *
     * <p>Kept for the interim period before the DSH side plugin exists: point
     * {@code -Ddsh.web.url} at the URL printed by {@code npx dsh web} and the
     * panel connects without a hand-off file.</p>
     */
    public static Health probeManual(String url) {
        if (url == null || url.isBlank()) {
            return Health.MISSING;
        }
        return httpProbe(url);
    }

    /**
     * One request, redirects not followed: the auth handshake answers 303 for a
     * valid token and 401 for a rotated one, so the status code alone decides.
     */
    private static Health httpProbe(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(PROBE_TIMEOUT_MS);
            connection.setReadTimeout(PROBE_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "text/html");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) {
                return Health.OK;
            }
            if (code == 401 || code == 403) {
                return Health.UNAUTHORIZED;
            }
            return Health.UNREACHABLE;
        } catch (IllegalArgumentException | IOException e) {
            return Health.UNREACHABLE;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String messageFor(Health health) {
        switch (health) {
            case UNAUTHORIZED:
                return "桌面版已重启，访问令牌已更换，需要重新连接。";
            case UNREACHABLE:
                return "连不上桌面版的服务端口，可能还在启动中。";
            case MALFORMED:
                return "桥接文件内容无法识别。";
            case DEAD_PID:
                return "桌面版进程已退出。";
            case MISSING:
                return "桌面版未运行。";
            default:
                return "";
        }
    }

    private static String stringField(String json, String key) {
        Matcher matcher = STRING_FIELD.matcher(json);
        while (matcher.find()) {
            if (key.equals(matcher.group(1))) {
                return unescape(matcher.group(2));
            }
        }
        return null;
    }

    private static Long numberField(String json, String key) {
        Matcher matcher = NUMBER_FIELD.matcher(json);
        while (matcher.find()) {
            if (key.equals(matcher.group(1))) {
                try {
                    return Long.parseLong(matcher.group(2));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String unescape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '\\' || i + 1 >= raw.length()) {
                out.append(c);
                continue;
            }
            char next = raw.charAt(++i);
            switch (next) {
                case 'n':
                    out.append('\n');
                    break;
                case 'r':
                    out.append('\r');
                    break;
                case 't':
                    out.append('\t');
                    break;
                case 'b':
                    out.append('\b');
                    break;
                case 'f':
                    out.append('\f');
                    break;
                case 'u':
                    if (i + 4 < raw.length()) {
                        try {
                            out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException e) {
                            out.append('u');
                        }
                    } else {
                        out.append('u');
                    }
                    break;
                default:
                    out.append(next);
                    break;
            }
        }
        return out.toString();
    }
}
