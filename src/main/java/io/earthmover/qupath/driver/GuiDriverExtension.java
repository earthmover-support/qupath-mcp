package io.earthmover.qupath.driver;

import java.io.File;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.extensions.QuPathExtension;

/**
 * Test-only extension: runs the Groovy file named by {@code qupath.driver.script} on a background
 * thread once the main stage is showing, with {@link GuiDriver} helpers in the binding.
 */
public class GuiDriverExtension implements QuPathExtension {

    private static final Logger logger = LoggerFactory.getLogger(GuiDriverExtension.class);
    private static final long STARTUP_DELAY_MS = 3000;

    @Override
    public void installExtension(QuPathGUI qupath) {
        String script = System.getProperty("qupath.driver.script");
        String port = System.getProperty("qupath.driver.port");
        if (script == null && port == null)
            return;
        var thread = new Thread(() -> run(qupath, script, port), "qupath-gui-driver");
        thread.setDaemon(true);
        thread.start();
    }

    private void run(QuPathGUI qupath, String script, String port) {
        var driver = new GuiDriver(qupath, System.getProperty("qupath.driver.out", "/tmp"));
        try {
            // installExtension runs before the main stage is shown.
            driver.waitForShowing(qupath.getStage(), 60);
            Thread.sleep(STARTUP_DELAY_MS);
            if (port != null)
                new DriverServer(driver, Integer.parseInt(port)).start();
            if (script != null) {
                logger.info("Driver running {}", script);
                driver.evaluate(new File(script), null);
                logger.info("Driver script finished");
            }
        } catch (Throwable t) {
            logger.error("Driver script failed", t);
        } finally {
            if (Boolean.getBoolean("qupath.driver.exit") && script != null)
                driver.quit();
        }
    }

    @Override
    public String getName() {
        return "GUI driver";
    }

    @Override
    public String getDescription() {
        return "Runs a Groovy script against the GUI for testing.";
    }
}
