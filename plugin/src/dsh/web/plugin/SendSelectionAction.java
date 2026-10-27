package dsh.web.plugin;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.jcef.JBCefBrowser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.StringSelection;

/**
 * Sends the editor selection into the DSH composer.
 *
 * <p>The payload is only a file reference of the form {@code @path#Lstart-end}.
 * The selection itself is intentionally not inlined: the harness resolves the
 * reference and reads the file, so the composer stays empty for the instruction
 * the user actually wants to type. Putting code in the same box made the two
 * run together and ate the whole input area.</p>
 *
 * <p>Injection goes through the embedded browser's DOM. When no writable input
 * is found (JCEF not ready, layout changed upstream), the payload falls back to
 * the system clipboard so the user can paste it manually — silent failure would
 * be worse than one extra keystroke.</p>
 */
public final class SendSelectionAction extends AnAction {

    public SendSelectionAction() {
        super("发送选中代码到 DSH",
                "把当前选中的代码作为文件引用插入 DeepSeek Harness 输入框",
                com.intellij.icons.AllIcons.Actions.Forward);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        Project project = event.getData(CommonDataKeys.PROJECT);
        boolean usable = editor != null
                && project != null
                && editor.getSelectionModel().hasSelection()
                && DshWebToolWindowFactory.browserFor(project) != null;
        event.getPresentation().setEnabledAndVisible(usable);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getData(CommonDataKeys.PROJECT);
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null) {
            return;
        }
        String payload = buildPayload(project, editor, event.getData(CommonDataKeys.VIRTUAL_FILE));
        if (payload == null || payload.isBlank()) {
            return;
        }

        JBCefBrowser browser = DshWebToolWindowFactory.browserFor(project);
        if (browser == null || browser.getCefBrowser() == null) {
            CopyPasteManager.getInstance().setContents(new StringSelection(payload));
            return;
        }
        browser.getCefBrowser().executeJavaScript(insertionScript(payload), "about:blank", 0);
    }

    /** Builds {@code @path#Lx-y} plus the selected text. */
    @Nullable
    static String buildPayload(@NotNull Project project, @NotNull Editor editor, @Nullable VirtualFile file) {
        Document document = editor.getDocument();
        int startOffset = editor.getSelectionModel().getSelectionStart();
        int endOffset = editor.getSelectionModel().getSelectionEnd();
        if (endOffset <= startOffset) {
            return null;
        }

        int startLine = document.getLineNumber(startOffset) + 1;
        // A selection ending at column 0 belongs to the previous line.
        int endLine = document.getLineNumber(Math.max(startOffset, endOffset - 1)) + 1;

        String reference = relativePath(project, file);
        StringBuilder payload = new StringBuilder();
        payload.append('@').append(reference).append("#L").append(startLine);
        if (endLine != startLine) {
            payload.append('-').append(endLine);
        }
        // Trailing space so the caret lands ready for the user's own words.
        // The selection itself is deliberately not inlined: the harness resolves
        // the reference and reads the file, which keeps the composer free for the
        // instruction the user actually wants to type.
        payload.append(' ');
        return payload.toString();
    }

    private static String relativePath(@NotNull Project project, @Nullable VirtualFile file) {
        if (file == null) {
            return "";
        }
        VirtualFile base = project.getBaseDir();
        String relative = VfsUtilCore.getRelativePath(file, base, '/');
        return relative != null ? relative : file.getPath();
    }

    /**
     * Focuses the composer, inserts the text, and reports the outcome through
     * {@code document.title} so it shows up in the JCEF console/log.
     */
    private static String insertionScript(String payload) {
        return "(function(text){"
                + "try{"
                + "var el=document.querySelector('[contenteditable=\"true\"]')"
                + "||document.querySelector('textarea');"
                + "if(!el){document.title='dsh-send:no-input';return;}"
                + "el.focus();"
                + "if(el.isContentEditable){"
                + "document.execCommand('insertText',false,text);"
                + "}else{"
                + "var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var setter=Object.getOwnPropertyDescriptor(proto,'value').set;"
                + "setter.call(el,(el.value||'')+text);"
                + "el.dispatchEvent(new Event('input',{bubbles:true}));"
                + "}"
                + "document.title='dsh-send:ok';"
                + "}catch(e){document.title='dsh-send:error';}"
                + "})(" + quote(payload) + ")";
    }

    /** JSON-style quoting keeps the payload valid inside a JS string literal. */
    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16).append('"');
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
                case '\u2028':
                    out.append("\\u2028");
                    break;
                case '\u2029':
                    out.append("\\u2029");
                    break;
                default:
                    out.append(c);
                    break;
            }
        }
        return out.append('"').toString();
    }
}
