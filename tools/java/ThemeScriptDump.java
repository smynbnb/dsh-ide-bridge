package dsh.web.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/** Prints one {@link PanelTheme} injection script for the Node-side syntax check. */
public final class ThemeScriptDump {
    public static void main(String[] args) {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("--bg", "#191a1c");
        vars.put("--text", "#d1d3d9");
        vars.put("--mono", "\"JetBrains Mono\", monospace");
        System.out.print(PanelTheme.script(vars, true, true));
    }
}
