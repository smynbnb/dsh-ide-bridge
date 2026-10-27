package dsh.web.plugin;

import com.intellij.icons.AllIcons;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.event.ActionListener;

/**
 * Placeholder shown while the desktop endpoint is unavailable: no bridge file,
 * a dead process, or a rotated token after a desktop restart.
 *
 * <p>Deliberately plain Swing: it has to render before any browser exists, and
 * before JCEF is known to be functional.</p>
 */
public final class BridgeStatusPanel {

    private final JPanel root;
    private final JBLabel iconLabel;
    private final JBLabel headline;
    private final JBLabel detail;
    private final JButton action;

    public BridgeStatusPanel() {
        this.root = new JPanel(new GridBagLayout());

        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        column.setBorder(JBUI.Borders.empty(24));

        iconLabel = new JBLabel();
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);

        headline = new JBLabel();
        headline.setAlignmentX(Component.CENTER_ALIGNMENT);
        headline.setHorizontalAlignment(SwingConstants.CENTER);
        Font base = headline.getFont();
        headline.setFont(base.deriveFont(Font.BOLD, base.getSize2D() + 1.5f));

        detail = new JBLabel();
        detail.setAlignmentX(Component.CENTER_ALIGNMENT);
        detail.setHorizontalAlignment(SwingConstants.CENTER);
        detail.setForeground(JBColor.GRAY);

        action = new JButton("重试");
        action.setAlignmentX(Component.CENTER_ALIGNMENT);

        column.add(iconLabel);
        column.add(Box.createVerticalStrut(JBUI.scale(8)));
        column.add(headline);
        column.add(Box.createVerticalStrut(JBUI.scale(6)));
        column.add(detail);
        column.add(Box.createVerticalStrut(JBUI.scale(14)));
        column.add(action);

        Dimension actionSize = new Dimension(JBUI.scale(120), JBUI.scale(30));
        action.setMaximumSize(actionSize);
        action.setPreferredSize(actionSize);

        root.add(column);
    }

    /** The component to install in the tool window content. */
    public JComponent component() {
        return root;
    }

    /** Binds the retry handler; runs on the EDT when the button is pressed. */
    public void setAction(@Nullable Runnable handler) {
        for (ActionListener listener : action.getActionListeners()) {
            action.removeActionListener(listener);
        }
        action.setEnabled(handler != null);
        action.addActionListener(event -> {
            if (handler != null) {
                handler.run();
            }
        });
    }

    /** Shows a spinner-like "connecting" state before the first probe lands. */
    public void showBusy() {
        iconLabel.setIcon(AllIcons.Process.Step_1);
        headline.setText("正在连接 DeepSeek Harness…");
        detail.setText("读取桌面版交接文件");
        action.setEnabled(false);
    }

    /** Renders one probe result. */
    public void show(@NotNull BridgeConfig.Result result) {
        switch (result.health) {
            case MISSING:
                iconLabel.setIcon(AllIcons.General.Information);
                headline.setText("桌面版未运行");
                detail.setText("请先启动 DeepSeek Harness 桌面版，插件会自动连接。");
                break;
            case DEAD_PID:
                iconLabel.setIcon(AllIcons.General.Warning);
                headline.setText("桌面版已退出");
                detail.setText(result.message);
                break;
            case UNAUTHORIZED:
                iconLabel.setIcon(AllIcons.General.Warning);
                headline.setText("需要重新连接");
                detail.setText(result.message);
                break;
            case MALFORMED:
                iconLabel.setIcon(AllIcons.General.Error);
                headline.setText("交接文件无法识别");
                detail.setText(result.message);
                break;
            case UNREACHABLE:
                iconLabel.setIcon(AllIcons.General.Warning);
                headline.setText("连接失败");
                detail.setText(result.message);
                break;
            default:
                iconLabel.setIcon(AllIcons.General.Information);
                headline.setText("已连接");
                detail.setText(result.message);
                break;
        }
        action.setEnabled(true);
    }

    /** Optional icon override, kept for symmetry with other status panels. */
    public void setIcon(@Nullable Icon icon) {
        iconLabel.setIcon(icon);
    }
}
