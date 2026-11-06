package dsh.web.plugin;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.JBUI;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Docks the DeepSeek Harness Web UI into an IDE tool window and keeps it
 * pointed at the already-running desktop instance.
 *
 * <p>Unlike the upstream plugin, the URL is not a static JVM property: it is
 * read from the hand-off file written by the DSH side plugin
 * (see {@link BridgeConfig}). That file carries the browser launch token the
 * desktop shell keeps in memory, which is the only way a second process can
 * attach to the same session store.</p>
 *
 * <p>State machine per project: {@code status} (desktop missing) and
 * {@code browser} (connected). A watcher promotes the panel as soon as the
 * file appears and demotes it when the token rotates or the process dies.</p>
 *
 * <p>A pinned "DeepSeek Chat" tab (chat.deepseek.com) stays next to the WebUI;
 * the reload button reloads whichever tab is active.</p>
 */
public final class DshWebToolWindowFactory implements ToolWindowFactory {

    private static final Logger LOG = Logger.getInstance(DshWebToolWindowFactory.class);

    /** Tool window id, as declared in plugin.xml. */
    public static final String TOOL_WINDOW_ID = "DeepSeek Harness Bridge";

    /** Manual override, kept as a fallback before the DSH side plugin exists. */
    public static final String URL_PROPERTY = "dsh.web.url";

    /** Address of the DeepSeek web chat, pinned as the second tab. */
    public static final String CHAT_URL = "https://chat.deepseek.com/";

    /** Display name of the pinned chat tab. */
    public static final String CHAT_TAB_NAME = "DeepSeek Chat";

    /** Project -> live WebUI browser, so actions (reload, ...) can reach the page. */
    private static final Map<Project, JBCefBrowser> BROWSERS = new ConcurrentHashMap<>();

    /** Project -> live chat (chat.deepseek.com) browser of the pinned tab. */
    private static final Map<Project, JBCefBrowser> CHAT_BROWSERS = new ConcurrentHashMap<>();

    /** Project -> whether the chat card (rather than the DSH panel) is showing. */
    private static final Map<Project, Boolean> CHAT_SELECTED = new ConcurrentHashMap<>();

    /** Returns the live WebUI browser of the given project's tool window, if any. */
    @Nullable
    public static JBCefBrowser browserFor(@Nullable Project project) {
        return project == null ? null : BROWSERS.get(project);
    }

    /**
     * Returns the browser of the currently showing card: the chat page when the
     * selector is on “DeepSeek Chat”, the DSH panel otherwise.
     */
    @Nullable
    public static JBCefBrowser activeBrowser(@NotNull Project project) {
        if (Boolean.TRUE.equals(CHAT_SELECTED.get(project))) {
            return CHAT_BROWSERS.get(project);
        }
        return BROWSERS.get(project);
    }

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        Controller controller = new Controller(project);
        Disposer.register(project, controller);

        // One content, one selector: the two cards are the DSH panel and the
        // pinned chat page. Keeping them as separate tool-window tabs made the
        // header overflow into IDEA's “hidden tabs” chevron and squeezed the
        // DeepSeek Harness tab out.
        JBCefBrowser chat = new JBCefBrowser(CHAT_URL);
        CHAT_BROWSERS.put(project, chat);
        CHAT_SELECTED.put(project, false);
        Disposer.register(project, chat);
        Disposer.register(project, () -> {
            CHAT_BROWSERS.remove(project);
            CHAT_SELECTED.remove(project);
        });

        JComboBox<String> selector = new JComboBox<>(new String[]{"DeepSeek Harness", CHAT_TAB_NAME});
        selector.setMaximumRowCount(2);
        JPanel selectorRow = new JPanel(new BorderLayout());
        selectorRow.setBorder(JBUI.Borders.empty(4, 8));
        selectorRow.add(selector, BorderLayout.WEST);

        JPanel cards = new JPanel(new CardLayout());
        cards.add(controller.component(), "harness");
        cards.add(chat.getComponent(), "chat");
        selector.addActionListener(event -> {
            boolean chatSelected = selector.getSelectedIndex() == 1;
            CHAT_SELECTED.put(project, chatSelected);
            ((CardLayout) cards.getLayout()).show(cards, chatSelected ? "chat" : "harness");
        });

        JPanel root = new JPanel(new BorderLayout());
        root.add(selectorRow, BorderLayout.NORTH);
        root.add(cards, BorderLayout.CENTER);

        Content content = ContentFactory.getInstance()
                .createContent(root, "DeepSeek Harness", false);
        content.setCloseable(false);
        toolWindow.getContentManager().addContent(content);

        ReloadPageAction reload = new ReloadPageAction();
        toolWindow.setTitleActions(List.of(reload));
        toolWindow.setAdditionalGearActions(new DefaultActionGroup(reload));

        // Publish the project path for the harness-side MCP proxy. The startup
        // activity already does this, but a project reopened from cache can race
        // the tool window, so refresh here as well.
        ProjectBridge.publish(project);

        controller.start();
    }

    /**
     * Owns one project's panel: the status card, the browser card, and the
     * watcher that moves between them.
     */
    private static final class Controller implements Disposable {

        private static final String CARD_STATUS = "status";
        private static final String CARD_BROWSER = "browser";

        private final Project project;
        private final CardLayout cards = new CardLayout();
        private final JPanel root = new JPanel(cards);
        private final JPanel browserHolder = new JPanel(new BorderLayout());
        private final BridgeStatusPanel status = new BridgeStatusPanel();
        private final AtomicReference<JBCefBrowser> browser = new AtomicReference<>();
        /**
         * Whether the next main-frame load is the authenticated launch URL that
         * should hand over to the compact panel. Re-armed on every (re)connect,
         * so a desktop restart lands on the panel again instead of the full UI.
         */
        private final AtomicBoolean panelNavigationPending = new AtomicBoolean();

        private BridgeWatcher watcher;
        private String loadedUrl;

        Controller(Project project) {
            this.project = project;
            root.add(status.component(), CARD_STATUS);
            root.add(browserHolder, CARD_BROWSER);
            cards.show(root, CARD_STATUS);
            status.setAction(this::retry);
        }

        JComponent component() {
            return root;
        }

        void start() {
            status.showBusy();
            watcher = new BridgeWatcher(this::refresh);
            watcher.start();
            Disposer.register(this, watcher);
        }

        /** Manual retry: probe off the EDT, then apply on it. */
        private void retry() {
            status.showBusy();
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                BridgeConfig.Result result = BridgeConfig.probe();
                ApplicationManager.getApplication().invokeLater(() -> refresh(result));
            });
        }

        /** Runs on the EDT. */
        private void refresh(BridgeConfig.Result result) {
            if (!result.isReady()) {
                String manual = manualUrl();
                if (manual != null && BridgeConfig.probeManual(manual) == BridgeConfig.Health.OK) {
                    load(manual);
                    return;
                }
                showStatus(result);
                return;
            }
            load(result.snapshot.url);
        }

        private void load(String url) {
            JBCefBrowser existing = browser.get();
            if (existing == null) {
                try {
                    // The launch URL carries the token and mints the auth cookie.
                    // The panel is a separate same-origin page, so it must be
                    // navigated to only after that first load finished.
                    JBCefBrowser created = new JBCefBrowser();
                    PanelTheme.install(this, created);
                    attachPanelNavigation(created);
                    panelNavigationPending.set(true);
                    created.loadURL(url);
                    browser.set(created);
                    loadedUrl = url;
                    browserHolder.removeAll();
                    browserHolder.add(created.getComponent(), BorderLayout.CENTER);
                    browserHolder.revalidate();
                    BROWSERS.put(project, created);
                    cards.show(root, CARD_BROWSER);
                } catch (Throwable t) {
                    LOG.warn("cannot create JCEF browser for " + url, t);
                    status.show(new BridgeConfig.Result(
                            BridgeConfig.Health.UNREACHABLE, null,
                            "内嵌浏览器初始化失败：" + t.getMessage()));
                }
                return;
            }
            if (!url.equals(loadedUrl)) {
                loadedUrl = url;
                panelNavigationPending.set(true);
                existing.loadURL(url);
            }
            cards.show(root, CARD_BROWSER);
        }

        /**
         * After the authenticated page loads (which set the cookie), navigate to
         * the compact panel instead of showing the full harness UI.
         */
        private void attachPanelNavigation(JBCefBrowser target) {
            target.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
                @Override
                public void onLoadEnd(CefBrowser cefBrowser, CefFrame frame, int httpStatusCode) {
                    if (frame == null || !frame.isMain()) {
                        return;
                    }
                    String current = cefBrowser.getURL();
                    if (current == null) {
                        return;
                    }
                    // Armed by load(); the panel page itself must not re-trigger
                    // this, or the panel would reload in a loop.
                    if (panelNavigationPending.compareAndSet(true, false)) {
                        String panel = panelUrl(current);
                        LOG.info("switching to DSH panel: " + panel);
                        cefBrowser.loadURL(panel);
                        return;
                    }
                    // Every panel load (first connect, reconnect, manual reload)
                    // re-applies the IDE palette; theme changes arrive through
                    // the listeners installed alongside this handler.
                    if (current.contains("/api/ide-panel")) {
                        PanelTheme.apply(target);
                    }
                }
            }, target.getCefBrowser());
        }

        /** Builds the panel address from the authenticated URL's origin. */
        private String panelUrl(String url) {
            String origin = url;
            int query = origin.indexOf('?');
            if (query >= 0) {
                origin = origin.substring(0, query);
            }
            // The launch URL is "<origin>/?token=...", so trimming the query leaves
            // a trailing slash that must not double up with the path below.
            while (origin.endsWith("/")) {
                origin = origin.substring(0, origin.length() - 1);
            }
            String basePath = project.getBasePath();
            String cwd = basePath == null ? "" : basePath.replace('\\', '/');
            return origin + "/api/ide-panel?cwd=" + URLEncoder.encode(cwd, StandardCharsets.UTF_8);
        }

        private void showStatus(BridgeConfig.Result result) {
            JBCefBrowser current = browser.get();
            if (current != null) {
                // Keep the instance for a fast reconnect but stop exposing it to
                // the reload action while the page is known to be stale.
                BROWSERS.remove(project, current);
            }
            loadedUrl = null;
            status.show(result);
            cards.show(root, CARD_STATUS);
        }

        @Override
        public void dispose() {
            JBCefBrowser current = browser.getAndSet(null);
            if (current != null) {
                BROWSERS.remove(project, current);
                Disposer.dispose(current);
            }
        }

        @Nullable
        private static String manualUrl() {
            String value = System.getProperty(URL_PROPERTY);
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

}
