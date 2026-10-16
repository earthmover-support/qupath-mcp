package io.earthmover.qupath.driver;

import java.io.File;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Serves MCP from inside QuPath while the "MCP server" preference is on. For tests, {@code qupath.driver.port} starts
 * the server regardless of the preference and {@code qupath.driver.script} runs a Groovy file instead, both on a
 * background thread once the main stage is showing, with {@link GuiDriver} helpers in the binding.
 */
public class GuiDriverExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(GuiDriverExtension.class);
    private static final long STARTUP_DELAY_MS = 3000;

    private static final BooleanProperty enabled = PathPrefs.createPersistentPreference("mcpServerEnabled", true);
    private static final IntegerProperty port = PathPrefs.createPersistentPreference("mcpServerPort", 51515);

    private DriverServer server;

    @Override
    public void installExtension(QuPathGUI qupath) {
        // One submenu for the extension, as QuPath's other extensions do, rather than items loose in Extensions.
        var menu = qupath.getMenu("Extensions>MCP server", true);
        var item = new CheckMenuItem("Enabled");
        item.selectedProperty().bindBidirectional(enabled);
        var show = new Menu("Show agent actions");
        var modes = new ToggleGroup();
        for (var m : Indicator.Mode.values()) {
            var radio = new RadioMenuItem(switch (m) {
                case OFF -> "Off";
                case MARK -> "Mark";
                case PACED -> "Paced";
            });
            radio.setToggleGroup(modes);
            radio.setSelected(Indicator.mode.get() == m);
            radio.setOnAction(e -> Indicator.mode.set(m));
            Indicator.mode.addListener((obs, was, now) -> radio.setSelected(now == m));
            show.getItems().add(radio);
        }
        menu.getItems().addAll(item, show);
        qupath.getPreferencePane().addPropertyPreference(ActionLog.enabled, Boolean.class, "Log agent actions", "MCP server",
                "Record every tool call the agent makes, with its arguments and result, as one JSON line per action in a "
                        + "file per QuPath session, so the session can be checked or reproduced.");
        qupath.getPreferencePane().addDirectoryPropertyPreference(ActionLog.folder, "Action log folder", "MCP server",
                "Where the action logs go. Empty means the mcp-logs folder in QuPath's user directory.");
        qupath.getPreferencePane().addPropertyPreference(ActionLog.limit, Integer.class, "Action log limit (MB)", "MCP server",
                "Total size of the action logs before qupath-mcp deletes the oldest (if allowed below) or warns you and the agent. "
                        + "0 means no limit.");
        qupath.getPreferencePane().addPropertyPreference(ActionLog.deleteOldest, Boolean.class,
                "Delete oldest action logs over the limit", "MCP server",
                "When the action logs pass the limit, delete the oldest until they are under it, instead of warning. "
                        + "Off by default, since the logs are the record of past sessions.");
        qupath.getPreferencePane().addPropertyPreference(port, Integer.class, "MCP server port", "MCP server",
                "Port for the MCP server on 127.0.0.1; takes effect when the server next starts.");
        qupath.getPreferencePane().addPropertyPreference(Indicator.delayMs, Integer.class, "Paced delay (ms)", "MCP server",
                "In the Paced setting of Extensions > MCP server > Show agent actions, how long the pointer takes to reach each "
                        + "control before the agent acts on it.");

        String script = System.getProperty("qupath.driver.script");
        var thread = new Thread(() -> run(qupath, script), "qupath-gui-driver");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(QuPathGUI qupath, String script) {
        // snap() writes PNGs here; the JVM's temp dir exists on every platform, /tmp does not.
        var driver = new GuiDriver(qupath, System.getProperty("qupath.driver.out", System.getProperty("java.io.tmpdir")));
        try {
            // installExtension runs before the main stage is shown.
            driver.waitForShowing(qupath.getStage(), 60);
            Thread.sleep(STARTUP_DELAY_MS);
            if (System.getProperty("qupath.driver.port") != null || enabled.get())
                startServer(driver);
            enabled.addListener((obs, was, now) -> {
                if (now)
                    startServer(driver);
                else
                    // Off the FX thread, which the in-flight request that flipped the preference may be waiting on.
                    new Thread(this::stopServer, "qupath-mcp-stop").start();
            });
            if (script != null) {
                logger.info("Driver running {}", script);
                var entry = driver.actions.begin("script", null, "script", Map.of("path", script));
                try {
                    var result = driver.evaluate(new File(script), null);
                    entry.done("result: " + result);
                } catch (Throwable t) {
                    entry.failed(t.toString());
                    throw t;
                }
                logger.info("Driver script finished");
            }
        } catch (Throwable t) {
            logger.error("Driver script failed", t);
        } finally {
            if (Boolean.getBoolean("qupath.driver.exit") && script != null)
                driver.quit();
        }
    }

    private synchronized void startServer(GuiDriver driver) {
        if (server != null)
            return;
        // The property lets tests pick a port without touching the stored preference.
        int p = Integer.getInteger("qupath.driver.port", port.get());
        try {
            server = new DriverServer(driver, p);
            server.start();
        } catch (Exception e) {
            logger.error("MCP server failed to start on port {}", p, e);
            server = null;
        }
    }

    private synchronized void stopServer() {
        try {
            if (server != null)
                server.stop();
        } catch (Exception e) {
            logger.error("MCP server failed to stop", e);
        }
        server = null;
    }

    @Override
    public String getName() {
        return "MCP server";
    }

    @Override
    public String getDescription() {
        return "Serves MCP over HTTP so an AI agent can drive QuPath. The agent can run any code in QuPath as you.";
    }
}
