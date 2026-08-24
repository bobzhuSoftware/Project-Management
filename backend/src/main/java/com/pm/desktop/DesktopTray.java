package com.pm.desktop;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.URI;

import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * System-tray launcher for the "app-like" resident mode. Only active when the
 * app is started with {@code --pm.tray.enabled=true}; the normal dev flow
 * (start-dev.cmd) never sets this, so its behaviour is unchanged.
 */
@Component
@ConditionalOnProperty(name = "pm.tray.enabled", havingValue = "true")
public class DesktopTray {

    private static final Logger log = LoggerFactory.getLogger(DesktopTray.class);

    private final ConfigurableApplicationContext context;

    @Value("${server.port:8090}")
    private int port;

    @Value("${server.address:127.0.0.1}")
    private String host;

    private TrayIcon trayIcon;

    public DesktopTray(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            log.warn("System tray not supported; open {} in a browser manually.", url());
            return;
        }
        try {
            // Swing JPopupMenu draws its own text with Java2D, so CJK labels render
            // correctly regardless of the platform charset. The old AWT PopupMenu
            // routed labels through the native Win32 menu and garbled them on some
            // codepages.
            JPopupMenu menu = new JPopupMenu();
            JMenuItem open = new JMenuItem("打开界面");
            open.addActionListener(e -> openUi());
            JMenuItem quit = new JMenuItem("退出");
            quit.addActionListener(e -> shutdown());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);

            // A hidden window is needed as the popup's invoker so the menu can take
            // focus and auto-dismiss when the user clicks elsewhere.
            JDialog invoker = new JDialog();
            invoker.setUndecorated(true);
            invoker.setSize(1, 1);
            invoker.setAlwaysOnTop(true);
            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    invoker.setVisible(false);
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    invoker.setVisible(false);
                }
            });

            trayIcon = new TrayIcon(buildIcon(), "Project Management");
            trayIcon.setImageAutoSize(true);
            trayIcon.addActionListener(e -> openUi()); // double-click opens the UI
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    maybeShowPopup(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    maybeShowPopup(e);
                }

                private void maybeShowPopup(MouseEvent e) {
                    if (!e.isPopupTrigger()) {
                        return;
                    }
                    // TrayIcon event coords are unreliable in the Win11 overflow
                    // flyout, so use the real cursor position instead.
                    java.awt.Point p = java.awt.MouseInfo.getPointerInfo().getLocation();
                    SwingUtilities.invokeLater(() -> {
                        invoker.setLocation(p.x, p.y);
                        invoker.setVisible(true);
                        // Swing keeps the menu on-screen, flipping it above the tray.
                        menu.show(invoker, 0, 0);
                        invoker.toFront();
                    });
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
            log.info("Tray ready. UI available at {}", url());
            openUi(); // open once on first launch for convenience
        } catch (Exception e) {
            log.error("Failed to initialise system tray", e);
        }
    }

    private String url() {
        return "http://" + host + ":" + port;
    }

    private void openUi() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url()));
            } else {
                log.warn("Desktop browse unsupported; open {} manually.", url());
            }
        } catch (Exception e) {
            log.error("Failed to open UI at {}", url(), e);
        }
    }

    private void shutdown() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        int code = SpringApplication.exit(context, () -> 0);
        System.exit(code);
    }

    private Image buildIcon() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 30, 46));
        g.fillRoundRect(0, 0, size, size, 4, 4);
        g.setColor(new Color(120, 200, 255));
        g.fillPolygon(new int[]{5, 5, 12}, new int[]{4, 12, 8}, 3);
        g.dispose();
        return img;
    }
}
