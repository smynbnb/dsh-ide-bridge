package dsh.web.plugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Publishes the IDE's currently open project so the harness side can scope its
 * MCP calls without a hard-coded path.
 *
 * <p>IDEA's MCP server requires the {@code IJ_MCP_SERVER_PROJECT_PATH} header on
 * every request; a static header in {@code cordis.patch.yml} breaks as soon as
 * the user opens a different project. Instead this plugin writes the path (and
 * the MCP port) to a hand-off file, and a small stdio proxy reads it when the
 * harness launches the proxy.</p>
 *
 * <p>File contract, shared with {@code dsh-plugin/ide-proxy.js}:</p>
 * <pre>{@code
 * { "projectPath": "D:/.../MyProject", "port": 64342, "updatedAt": 1793... }
 * }</pre>
 */
public final class ProjectBridge {

    private static final Logger LOG = Logger.getInstance(ProjectBridge.class);

    /** Same directory the harness-side bridge uses for its own hand-off file. */
    private static final String DIRECTORY = "dsh-ide-bridge";
    private static final String FILE_NAME = "ide-project.json";

    /** IDEA's built-in web server serves the MCP endpoint; this is its default port. */
    static final String DEFAULT_MCP_PORT = "64342";

    private ProjectBridge() {
    }

    /** Resolves {@code %LOCALAPPDATA%\dsh-ide-bridge\ide-project.json}. */
    public static Path file() {
        String localAppData = System.getenv("LOCALAPPDATA");
        Path base = localAppData != null && !localAppData.isBlank()
                ? Paths.get(localAppData)
                : Paths.get(System.getProperty("user.home"), "AppData", "Local");
        return base.resolve(DIRECTORY).resolve(FILE_NAME);
    }

    /** Writes the current project's path; safe to call repeatedly. */
    public static void publish(@Nullable Project project) {
        if (project == null) {
            return;
        }
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            return;
        }
        // The MCP server compares this against its own project path; forward
        // slashes avoid JSON backslash escaping and match IDEA's own output.
        String normalized = basePath.replace('\\', '/');

        StringBuilder json = new StringBuilder(160);
        json.append("{\n");
        json.append("  \"projectPath\": \"").append(escape(normalized)).append("\",\n");
        json.append("  \"port\": \"").append(port()).append("\",\n");
        json.append("  \"updatedAt\": ").append(System.currentTimeMillis()).append('\n');
        json.append("}\n");

        Path target = file();
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(temporary, json.toString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("published IDE project bridge: " + normalized);
        } catch (IOException e) {
            LOG.warn("cannot publish IDE project bridge to " + target, e);
        }
    }

    /** Removes the hand-off file when no project remains open. */
    public static void clear() {
        try {
            Files.deleteIfExists(file());
        } catch (IOException e) {
            LOG.debug("cannot remove IDE project bridge", e);
        }
    }

    private static String port() {
        String override = System.getProperty("dsh.bridge.idePort");
        if (override != null && !override.isBlank()) {
            return override.trim();
        }
        String env = System.getenv("IJ_MCP_SERVER_PORT");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return DEFAULT_MCP_PORT;
    }

    private static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 8);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.toString();
    }

    /** Startup hook: runs for every project that opens, including restored ones. */
    public static final class Startup implements com.intellij.openapi.startup.StartupActivity {
        @Override
        public void runActivity(@NotNull Project project) {
            publish(project);
            // Stable harness-facing endpoint; see McpRelayServer for why the
            // project path cannot come from the caller's configuration.
            McpRelayServer.ensureStarted();
        }
    }
}
