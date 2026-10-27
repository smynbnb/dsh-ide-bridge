package dsh.web.plugin;

import com.intellij.ide.ui.LafManager;
import com.intellij.ide.ui.LafManagerListener;
import com.intellij.ide.ui.laf.UIThemeLookAndFeelInfo;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.ui.JBColor;
import com.intellij.ui.jcef.JBCefBrowser;
import com.intellij.util.ui.UIUtil;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Makes the IDE panel follow the *actual* IDE theme instead of only light/dark.
 *
 * <p>The page is plain HTML served by the harness, so it cannot read IntelliJ
 * colors. This class samples the live look-and-feel — tool window background,
 * labels, borders, text fields, link/accent color, warning color, editor font —
 * and injects them as the page's CSS custom properties. That covers every theme
 * family (Darcula, IntelliJ Light, Islands, High Contrast, custom Marketplace
 * themes) without maintaining a palette list.</p>
 *
 * <p>Sampling happens through the LAF's own {@code UIManager} defaults, so a
 * {@link JBColor} resolves to the variant of the active theme. Values that a
 * theme does not define fall back to semantic {@link UIUtil} colors, then to
 * neutral defaults; derived tints (soft backgrounds, user bubble, warning card)
 * are blended from the sampled pair, which keeps contrast correct in both dark
 * and light themes.</p>
 */
final class PanelTheme {

    private static final Logger LOG = Logger.getInstance(PanelTheme.class);

    /** Below this channel distance a theme's "raised" surface reads as the window itself. */
    private static final int MIN_CONTRAST_DISTANCE = 8;

    private PanelTheme() {
    }

    /**
     * Re-inject the palette whenever the IDE theme or editor scheme changes.
     *
     * @param parent  owner of the listener connections (project or controller).
     * @param browser browser showing the panel page.
     */
    static void install(Disposable parent, JBCefBrowser browser) {
        Application app = ApplicationManager.getApplication();
        if (app == null) {
            return;
        }
        app.getMessageBus().connect(parent).subscribe(
                LafManagerListener.TOPIC,
                (LafManagerListener) manager -> apply(browser));
        // Editor color schemes can change without a LAF switch; code colors and
        // the mono font come from that scheme.
        app.getMessageBus().connect(parent).subscribe(
                EditorColorsManager.TOPIC,
                (EditorColorsListener) scheme -> apply(browser));
    }

    /** Inject the current theme into one loaded panel page. */
    static void apply(JBCefBrowser browser) {
        if (browser == null) {
            return;
        }
        Application app = ApplicationManager.getApplication();
        Runnable task = () -> {
            try {
                Theme theme = sample();
                browser.runJavaScript(script(theme.vars, theme.dark, theme.contrast));
                LOG.info("panel theme: dark=" + theme.dark + ", contrast=" + theme.contrast
                        + ", colors=" + theme.vars);
            } catch (Throwable t) {
                // A closed or not-yet-displayed browser must not break the panel.
                LOG.warn("cannot apply panel theme", t);
            }
        };
        if (app == null || app.isDispatchThread()) {
            task.run();
        } else {
            app.invokeLater(task);
        }
    }

    /* ------------------------------------------------------------------ *
     * Sampling
     * ------------------------------------------------------------------ */

    private static final class Theme {
        final Map<String, String> vars;
        final boolean dark;
        final boolean contrast;

        Theme(Map<String, String> vars, boolean dark, boolean contrast) {
            this.vars = vars;
            this.dark = dark;
            this.contrast = contrast;
        }
    }

    private static Theme sample() {
        boolean dark = isDark();
        boolean contrast = isHighContrast();

        Color window = firstColor(
                named("ToolWindow.background", "Panel.background", "Viewport.background"),
                safeWindowColor());
        if (window == null) {
            window = dark ? new Color(0x1E1F22) : new Color(0xFFFFFF);
        }

        Color text = firstColor(
                named("Label.foreground", "Panel.foreground", "Component.foreground"),
                safeActiveTextColor());
        if (text == null) {
            text = dark ? new Color(0xDFE1E5) : new Color(0x1F2328);
        }

        Color dim = firstColor(
                named("Label.disabledForeground", "Component.infoForeground", "Label.infoForeground"),
                safeInactiveTextColor());
        if (dim == null) {
            dim = blend(window, text, 0.45);
        }

        Color border = firstColor(
                named("Component.borderColor", "Separator.separatorColor", "Borders.color", "ToolWindow.borderColor"));
        if (border == null) {
            border = blend(window, text, 0.16);
        }

        Color accent = firstColor(
                named("Link.activeForeground", "Component.focusColor"),
                safeFocusedBorderColor());
        if (accent == null) {
            accent = dark ? new Color(0x6EA8FE) : new Color(0x2563EB);
        }

        // Filled buttons are not links: High Contrast, for example, uses a
        // fluorescent green for links but an electric cyan default button with
        // black text. Sampling the button pair keeps the fill and its label in
        // the relationship the theme author designed.
        Color accentFill = firstColor(
                named("Button.default.startBackground", "Button.default.background", "Button.default.endBackground"),
                accent);
        Color onAccent = named("Button.default.foreground");
        if (onAccent == null || contrastRatio(accentFill, onAccent) < 3.0) {
            onAccent = readableOn(accentFill);
        }

        Color warning = named("Notification.Warning.foreground", "Label.warningForeground");
        if (warning == null) {
            warning = dark ? new Color(0xFBBF24) : new Color(0xB45309);
        }

        // A surface that reads as "raised" only if the theme actually separates
        // it from the window; otherwise a tiny blend guarantees a visible edge.
        Color field = named("TextField.background", "TextArea.background", "EditorPane.background");
        Color raised = field != null && distance(field, window) >= MIN_CONTRAST_DISTANCE
                ? field
                : blend(window, text, 0.06);

        Map<String, String> vars = new LinkedHashMap<>();
        put(vars, "--bg", window);
        put(vars, "--bg-soft", blend(window, text, 0.04));
        put(vars, "--bg-raised", raised);
        put(vars, "--border", border);
        put(vars, "--text", text);
        put(vars, "--text-dim", dim);
        put(vars, "--accent", accent);
        put(vars, "--accent-fill", accentFill);
        put(vars, "--on-accent", onAccent);
        put(vars, "--accent-soft", blend(window, accent, dark ? 0.24 : 0.12));
        put(vars, "--tool-bg", blend(window, text, 0.07));
        put(vars, "--user-bg", blend(window, accent, dark ? 0.18 : 0.10));
        put(vars, "--warn", warning);
        put(vars, "--warn-soft", blend(window, warning, dark ? 0.16 : 0.10));
        put(vars, "--warn-border", warning);

        String uiFont = family(UIManager.getFont("Label.font"));
        if (uiFont != null) {
            vars.put("--ui-font", quoteFamily(uiFont, "sans-serif"));
        }
        String editorFont = editorFontFamily();
        if (editorFont != null) {
            vars.put("--mono", quoteFamily(editorFont, "monospace"));
        }
        return new Theme(vars, dark, contrast);
    }

    private static boolean isDark() {
        try {
            // JBColor's flag is set by the active LAF; it covers Darcula, Islands
            // Dark and high-contrast dark alike.
            return !JBColor.isBright();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isHighContrast() {
        try {
            Application app = ApplicationManager.getApplication();
            if (app == null) {
                return false;
            }
            LafManager manager = app.getService(LafManager.class);
            if (manager == null) {
                return false;
            }
            UIThemeLookAndFeelInfo info = manager.getCurrentUIThemeLookAndFeel();
            if (info == null) {
                return false;
            }
            String label = (String.valueOf(info.getName()) + ' ' + String.valueOf(info.getId()))
                    .toLowerCase(Locale.ROOT);
            return label.contains("contrast");
        } catch (Throwable t) {
            return false;
        }
    }

    private static String editorFontFamily() {
        try {
            Application app = ApplicationManager.getApplication();
            if (app == null) {
                return null;
            }
            EditorColorsManager manager = app.getService(EditorColorsManager.class);
            if (manager == null) {
                return null;
            }
            EditorColorsScheme scheme = manager.getGlobalScheme();
            if (scheme == null) {
                return null;
            }
            String name = scheme.getEditorFontName();
            return usableFamily(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /* ------------------------------------------------------------------ *
     * Color helpers
     * ------------------------------------------------------------------ */

    private static Color named(String... keys) {
        for (String key : keys) {
            Color color = null;
            try {
                color = UIManager.getColor(key);
            } catch (Throwable ignored) {
                // A missing key is normal: themes define different sets.
            }
            if (color == null) {
                try {
                    color = JBColor.namedColorOrNull(key);
                } catch (Throwable ignored) {
                    // Same as above.
                }
            }
            if (color != null) {
                // A transparent theme entry ("transparent", #00000000) cannot be
                // painted as a surface; let the next candidate or the blend win.
                if (color.getAlpha() < 255) {
                    continue;
                }
                return new Color(color.getRGB());
            }
        }
        return null;
    }

    private static Color firstColor(Color... candidates) {
        for (Color candidate : candidates) {
            if (candidate != null) {
                return opaque(candidate);
            }
        }
        return null;
    }

    private static Color safeWindowColor() {
        try {
            return UIUtil.getWindowColor();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Color safeActiveTextColor() {
        try {
            return UIUtil.getActiveTextColor();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Color safeInactiveTextColor() {
        try {
            return UIUtil.getInactiveTextColor();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Color safeFocusedBorderColor() {
        try {
            return UIUtil.getFocusedBorderColor();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Drop alpha so blending and CSS output stay predictable. */
    private static Color opaque(Color color) {
        return new Color(color.getRGB());
    }

    /**
     * Mix {@code mix} into {@code base} by {@code ratio}.
     *
     * @param base  background the result sits on.
     * @param mix   ink color to tint with.
     * @param ratio share of {@code mix}, 0..1.
     * @return the blended opaque color.
     */
    private static Color blend(Color base, Color mix, double ratio) {
        double keep = 1.0 - ratio;
        return new Color(
                clamp(base.getRed() * keep + mix.getRed() * ratio),
                clamp(base.getGreen() * keep + mix.getGreen() * ratio),
                clamp(base.getBlue() * keep + mix.getBlue() * ratio));
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    private static int distance(Color left, Color right) {
        return Math.max(
                Math.abs(left.getRed() - right.getRed()),
                Math.max(Math.abs(left.getGreen() - right.getGreen()), Math.abs(left.getBlue() - right.getBlue())));
    }

    /** Black or white, whichever is more legible on the given fill. */
    private static Color readableOn(Color background) {
        return relativeLuminance(background) > 0.45 ? Color.BLACK : Color.WHITE;
    }

    /** WCAG relative luminance of an opaque sRGB color. */
    private static double relativeLuminance(Color color) {
        return 0.2126 * linear(color.getRed())
                + 0.7152 * linear(color.getGreen())
                + 0.0722 * linear(color.getBlue());
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** WCAG contrast ratio, 1..21. */
    private static double contrastRatio(Color left, Color right) {
        double a = relativeLuminance(left);
        double b = relativeLuminance(right);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static void put(Map<String, String> vars, String name, Color color) {
        vars.put(name, css(color));
    }

    private static String css(Color color) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", color.getRed(), color.getGreen(), color.getBlue());
    }

    private static String family(Font font) {
        return font == null ? null : usableFamily(font.getFamily());
    }

    private static String usableFamily(String family) {
        if (family == null || family.isBlank()) {
            return null;
        }
        // Java logical fonts have no CSS counterpart; injecting "Dialog" would
        // make Chromium fall back to a serif default. Keep the page's own stack.
        return switch (family.toLowerCase(Locale.ROOT)) {
            case "dialog", "dialoginput", "sansserif", "serif", "monospaced" -> null;
            default -> family;
        };
    }

    /** Quote a font family for a CSS custom property, with a generic fallback. */
    private static String quoteFamily(String family, String fallback) {
        return "\"" + family.replace("\\", "\\\\").replace("\"", "\\\"") + "\", " + fallback;
    }

    /* ------------------------------------------------------------------ *
     * Script
     * ------------------------------------------------------------------ */

    /**
     * Build the injection script. Kept separate from the browser so the payload
     * can be checked without an IDE.
     */
    static String script(Map<String, String> vars, boolean dark, boolean contrast) {
        StringBuilder out = new StringBuilder(512);
        out.append("(function(p){var r=document.documentElement;");
        out.append("r.dataset.theme=p.dark?'dark':'light';");
        out.append("if(p.contrast){r.dataset.contrast='high';}else{delete r.dataset.contrast;}");
        out.append("var s=r.style;for(var k in p.vars){s.setProperty(k,p.vars[k]);}");
        // Cache the palette so the page can paint the right theme before this
        // script runs again on the next load (no light flash in a dark IDE).
        out.append("try{localStorage.setItem('dsh-ide-theme',JSON.stringify(p));}catch(e){}");
        out.append("})({");
        out.append("\"dark\":").append(dark);
        out.append(",\"contrast\":").append(contrast);
        out.append(",\"vars\":{");
        boolean first = true;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        out.append("}});");
        return out.toString();
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
