package dsh.web.plugin;

import javax.swing.UIManager;
import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Offline check for {@link PanelTheme}'s palette sampling and script payload.
 *
 * Runs against the plugin classes plus the IDE platform jars, with no running
 * IDE: it populates the same {@code UIManager} keys a theme sets, flips
 * {@code JBColor.setDark}, and inspects the produced CSS variables. This does
 * not prove visual fidelity in a real theme, but it does prove the fallbacks,
 * blending, dark detection and JSON escaping.
 *
 * Compile/run (see tools/README-theme-check.md):
 *   javac -cp "plugin/classes;IDE/lib/*;IDE/plugins/jcef-plugin/lib/modules/*" -d tools/java-classes tools/java/ThemeCheck.java
 *   java  -cp "tools/java-classes;plugin/classes;IDE/lib/*;IDE/plugins/jcef-plugin/lib/modules/*" dsh.web.plugin.ThemeCheck
 */
public final class ThemeCheck {

    public static void main(String[] args) throws Exception {
        darkPalette();
        lightPalette();
        highContrastPalette();
        buttonFallback();
        scriptEscaping();
        System.out.println("ok: panel theme palette, fallbacks, blends, dark flag, high-contrast button, script payload");
    }

    private static void darkPalette() throws Exception {
        JBColorHook.setDark(true);
        UIManager.put("ToolWindow.background", new Color(0x191A1C));
        UIManager.put("Label.foreground", new Color(0xD1D3D9));
        UIManager.put("Label.disabledForeground", new Color(0x9FA2A8));
        UIManager.put("Component.borderColor", new Color(0x40434A));
        UIManager.put("Link.activeForeground", new Color(0x71A1FE));
        // Same as the window on purpose: "raised" must fall back to a blend.
        UIManager.put("TextField.background", new Color(0x191A1C));

        Map<String, String> vars = sample();

        check("#191a1c", vars.get("--bg"), "dark window");
        check("#d1d3d9", vars.get("--text"), "dark label");
        check("#9fa2a8", vars.get("--text-dim"), "dark dim");
        check("#40434a", vars.get("--border"), "dark border");
        check("#71a1fe", vars.get("--accent"), "dark accent");
        check("#191a1c", vars.get("--bg-raised"), "raised must fall back when equal to bg", false);
        if (distance(vars.get("--text"), vars.get("--bg")) < 40) {
            throw new AssertionError("dark text/bg too close: " + vars.get("--text") + " vs " + vars.get("--bg"));
        }
        if (vars.get("--ui-font") != null && !vars.get("--ui-font").contains(",")) {
            throw new AssertionError("ui font must carry a generic fallback: " + vars.get("--ui-font"));
        }
        System.out.println("dark   " + vars);
    }

    private static void lightPalette() throws Exception {
        JBColorHook.setDark(false);
        UIManager.put("ToolWindow.background", new Color(0xFFFFFF));
        UIManager.put("Label.foreground", new Color(0x1F2328));
        UIManager.put("Component.borderColor", new Color(0xE3E3E6));
        UIManager.put("Link.activeForeground", new Color(0x2563EB));
        UIManager.put("TextField.background", new Color(0xF7F7F8));

        Map<String, String> vars = sample();
        check("#ffffff", vars.get("--bg"), "light window");
        check("#1f2328", vars.get("--text"), "light label");
        check("#f7f7f8", vars.get("--bg-raised"), "light raised keeps the field surface");
        if (darkFlag() != false) {
            throw new AssertionError("light theme reported as dark");
        }
        System.out.println("light  " + vars);
    }

    /**
     * High Contrast: links are fluorescent green (#D2F53C) while the default
     * button is electric cyan (#1AEBFF) with black text. The panel must take
     * the button pair for filled controls, not the link color.
     */
    private static void highContrastPalette() throws Exception {
        JBColorHook.setDark(true);
        UIManager.put("ToolWindow.background", new Color(0x000000));
        UIManager.put("Label.foreground", new Color(0xFFFFFF));
        UIManager.put("Component.borderColor", new Color(0xFFFFFF));
        UIManager.put("Link.activeForeground", new Color(0xD2F53C));
        UIManager.put("Button.default.startBackground", new Color(0x1AEBFF));
        UIManager.put("Button.default.background", new Color(0x1AEBFF));
        UIManager.put("Button.default.foreground", new Color(0x000000));

        Map<String, String> vars = sample();
        check("#d2f53c", vars.get("--accent"), "hc link color");
        check("#1aebff", vars.get("--accent-fill"), "hc default button fill");
        check("#000000", vars.get("--on-accent"), "hc default button label");
        if (contrast(vars.get("--accent-fill"), vars.get("--on-accent")) < 4.5) {
            throw new AssertionError("hc button label is not legible on its fill: " + vars);
        }
        System.out.println("hc     " + vars);
    }

    /** Without a theme button pair, a bright accent must get a black label. */
    private static void buttonFallback() throws Exception {
        JBColorHook.setDark(true);
        UIManager.getDefaults().remove("Button.default.startBackground");
        UIManager.getDefaults().remove("Button.default.background");
        UIManager.getDefaults().remove("Button.default.endBackground");
        UIManager.getDefaults().remove("Button.default.foreground");
        UIManager.put("Link.activeForeground", new Color(0xD2F53C));

        Map<String, String> vars = sample();
        check("#d2f53c", vars.get("--accent-fill"), "fallback fill uses the accent");
        check("#000000", vars.get("--on-accent"), "bright fill gets a black label");
        System.out.println("fallback " + vars);
    }

    private static void scriptEscaping() {
        String script = PanelTheme.script(new java.util.LinkedHashMap<>(Map.of(
                "--bg", "#123456",
                "--ui-font", "A \"quoted\" Font")), true, true);
        if (!script.contains("\"dark\":true") || !script.contains("\"contrast\":true")) {
            throw new AssertionError("payload flags missing: " + script);
        }
        if (!script.contains("\"--bg\":\"#123456\"")) {
            throw new AssertionError("background variable missing: " + script);
        }
        if (!script.contains("\\\"quoted\\\"")) {
            throw new AssertionError("font quotes were not escaped: " + script);
        }
        if (!script.contains("r.dataset.theme=p.dark?'dark':'light'")) {
            throw new AssertionError("theme attribute not set: " + script);
        }
    }

    /* ------------------------------------------------------------------ */

    @SuppressWarnings("unchecked")
    private static Map<String, String> sample() throws Exception {
        Method method = PanelTheme.class.getDeclaredMethod("sample");
        method.setAccessible(true);
        Object theme = method.invoke(null);
        Field vars = theme.getClass().getDeclaredField("vars");
        vars.setAccessible(true);
        return (Map<String, String>) vars.get(theme);
    }

    private static boolean darkFlag() throws Exception {
        Method method = PanelTheme.class.getDeclaredMethod("isDark");
        method.setAccessible(true);
        return (Boolean) method.invoke(null);
    }

    private static void check(String expected, String actual, String label) {
        check(expected, actual, label, true);
    }

    private static void check(String expected, String actual, String label, boolean equal) {
        boolean matches = expected.equals(actual);
        if (matches != equal) {
            throw new AssertionError(label + ": expected " + (equal ? "" : "not ") + expected + " but got " + actual);
        }
    }

    private static int distance(String left, String right) {
        return Math.max(
                Math.abs(Integer.parseInt(left.substring(1, 3), 16) - Integer.parseInt(right.substring(1, 3), 16)),
                Math.max(
                        Math.abs(Integer.parseInt(left.substring(3, 5), 16) - Integer.parseInt(right.substring(3, 5), 16)),
                        Math.abs(Integer.parseInt(left.substring(5, 7), 16) - Integer.parseInt(right.substring(5, 7), 16))));
    }

    private static double contrast(String left, String right) {
        double a = luminance(left);
        double b = luminance(right);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double luminance(String hex) {
        return 0.2126 * linear(hex, 1) + 0.7152 * linear(hex, 3) + 0.0722 * linear(hex, 5);
    }

    private static double linear(String hex, int start) {
        double value = Integer.parseInt(hex.substring(start, start + 2), 16) / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** Wrapper so the check does not link {@code JDK}'s JBColor at compile time beyond this helper. */
    private static final class JBColorHook {
        static void setDark(boolean dark) throws Exception {
            Class<?> jb = Class.forName("com.intellij.ui.JBColor");
            Method setDark = jb.getMethod("setDark", boolean.class);
            setDark.invoke(null, dark);
        }
    }
}
