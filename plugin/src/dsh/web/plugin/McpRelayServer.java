package dsh.web.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exposes the IDE's MCP server on a stable loopback endpoint that needs no
 * project argument.
 *
 * <p>IDEA's own endpoint ({@code 127.0.0.1:64342/stream}) requires an
 * {@code IJ_MCP_SERVER_PROJECT_PATH} header on every request, which a static
 * harness configuration cannot supply. Rather than proxy stdio and pin the path
 * in an environment variable, this relay forwards HTTP and fills the header from
 * the project that is actually open — so the harness only needs a fixed URL.</p>
 *
 * <p>Forwarding is deliberately header-only: request bodies and response streams
 * pass through untouched, which keeps MCP's chunked and {@code text/event-stream}
 * responses working without this class understanding the protocol.</p>
 */
public final class McpRelayServer implements Disposable {

    private static final Logger LOG = Logger.getInstance(McpRelayServer.class);

    /** Stable port the harness configuration points at. */
    static final int LISTEN_PORT = 64343;

    /** IDEA's own MCP endpoint. */
    private static final String UPSTREAM = "http://127.0.0.1:64342/stream";

    private static final String PROJECT_HEADER = "IJ_MCP_SERVER_PROJECT_PATH";
    private static final String PATH = "/mcp";

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private HttpServer server;
    private ExecutorService executor;

    /** Starts the relay once per IDE process. */
    public static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        McpRelayServer relay = new McpRelayServer();
        if (relay.start()) {
            Disposer.register(ApplicationManager.getApplication(), relay);
        } else {
            STARTED.set(false);
        }
    }

    private boolean start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", LISTEN_PORT), 0);
            executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "dsh-mcp-relay");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(PATH, this::handle);
            server.start();
            LOG.info("DSH MCP relay listening on http://127.0.0.1:" + LISTEN_PORT + PATH);
            return true;
        } catch (IOException e) {
            LOG.warn("cannot start DSH MCP relay on port " + LISTEN_PORT, e);
            return false;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            forward(exchange);
        } catch (Throwable t) {
            LOG.warn("DSH MCP relay failed to forward a request", t);
            try {
                sendError(exchange, 502, "relay failure: " + t.getMessage());
            } catch (IOException ignored) {
                // The peer already went away.
            }
        } finally {
            exchange.close();
        }
    }

    private void forward(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        String method = exchange.getRequestMethod();

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(UPSTREAM))
                .timeout(Duration.ofMinutes(5))
                .method(method, body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));

        copyRequestHeaders(exchange, request);
        // The whole point of this class: the project comes from the IDE, not
        // from the caller's configuration.
        request.header(PROJECT_HEADER, currentProjectPath());

        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException e) {
            sendError(exchange, 502, "IDE MCP endpoint unreachable: " + e.getMessage());
            return;
        }

        response.headers().map().forEach((name, values) -> {
            if (isHopByHop(name) || name.equalsIgnoreCase("content-length")) {
                return;
            }
            for (String value : values) {
                exchange.getResponseHeaders().add(name, value);
            }
        });

        int status = response.statusCode();
        boolean bodyless = status == 204 || status == 304 || status < 200;
        // 0 = chunked, which carries both one-shot JSON and event streams.
        exchange.sendResponseHeaders(status, bodyless ? -1 : 0);
        if (bodyless) {
            return;
        }
        try (InputStream upstream = response.body();
             OutputStream downstream = exchange.getResponseBody()) {
            upstream.transferTo(downstream);
        }
    }

    private static void copyRequestHeaders(HttpExchange exchange, HttpRequest.Builder request) {
        for (Map.Entry<String, List<String>> entry : exchange.getRequestHeaders().entrySet()) {
            String name = entry.getKey();
            if (name == null || isHopByHop(name) || name.equalsIgnoreCase("content-length")) {
                continue;
            }
            for (String value : entry.getValue()) {
                request.header(name, value);
            }
        }
    }

    /** Headers that must not be forwarded verbatim across a proxy hop. */
    private static boolean isHopByHop(String name) {
        return name.equalsIgnoreCase("host")
                || name.equalsIgnoreCase("connection")
                || name.equalsIgnoreCase("transfer-encoding")
                || name.equalsIgnoreCase("upgrade")
                || name.equalsIgnoreCase("keep-alive")
                || name.equalsIgnoreCase("proxy-authenticate")
                || name.equalsIgnoreCase("proxy-authorization")
                || name.equalsIgnoreCase("te")
                || name.equalsIgnoreCase("trailer");
    }

    /** Normalizes to forward slashes, matching the format the MCP server expects. */
    private static String currentProjectPath() {
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            String base = project.getBasePath();
            if (base != null && !base.isBlank()) {
                return base.replace('\\', '/');
            }
        }
        return "";
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"error\":\"" +
                message.replace("\\", "\\\\").replace("\"", "\\\"") +
                "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public void dispose() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        LOG.info("DSH MCP relay stopped");
    }
}
