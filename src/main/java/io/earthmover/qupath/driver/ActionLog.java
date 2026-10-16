package io.earthmover.qupath.driver;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Node;
import javafx.stage.Window;
import qupath.fx.dialogs.Dialogs;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;
import qupath.lib.io.GsonTools;

/**
 * Records every action the agent takes, one JSON object per line, so a session can be checked afterwards or redone.
 * QuPath keeps no history of its own, so without this a run driven by an agent leaves no record of what was done.
 *
 * <p>An entry is written for each MCP tool call, each {@code /groovy} request and each start-up script, whatever the
 * on-screen indicator is set to: the indicator is for the person watching, the log is for the record. The unit is the
 * tool call, because that is the unit the agent acts in: it asks for a click on a named control rather than moving a
 * pointer, so nothing finer exists to record. Each entry carries the tool, its arguments verbatim (the Groovy source,
 * for {@code qupath_run_groovy}), the window and control the driver resolved them to, whether it succeeded, how long it
 * took, the text it returned, and the project and image that were open.
 *
 * <p>One file per QuPath session, {@code mcp-<date>-<time>.jsonl} in the log folder, created on the first action so a
 * session with no agent activity leaves nothing behind. Lines are appended and flushed as they happen, so the log is
 * intact up to the action that was running if QuPath goes down. The folder defaults to {@code mcp-logs} in QuPath's
 * user directory (preference "Action log folder").
 *
 * <p>Logs are kept until they add up to more than "Action log limit (MB)", because the log is the record a session is
 * checked or reproduced from. Over the limit, the oldest are deleted if "Delete oldest action logs over the limit" is
 * on; otherwise the user gets a notification and the agent a note in its next tool result, so whoever is driving finds
 * out. Both happen when a session opens its log file.
 */
class ActionLog {

    private static final Logger logger = LoggerFactory.getLogger(ActionLog.class);
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** The returned text kept per entry; a long qupath_describe result is cut to this. */
    static final int RESULT_CHARS = 4000;

    static final BooleanProperty enabled = PathPrefs.createPersistentPreference("mcpActionLog", true);
    static final StringProperty folder = PathPrefs.createPersistentPreference("mcpActionLogDir", "");
    static final IntegerProperty limit = PathPrefs.createPersistentPreference("mcpActionLogLimitMB", 1024);
    static final BooleanProperty deleteOldest = PathPrefs.createPersistentPreference("mcpActionLogDeleteOldest", false);

    /** Only files of exactly this shape are counted or deleted, so a shared folder's other logs are left alone. */
    private static final Pattern LOG_NAME = Pattern.compile("mcp-\\d{8}-\\d{6}\\.jsonl");
    private static final boolean POSIX = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private static final Gson GSON = GsonTools.getInstance().newBuilder().disableHtmlEscaping().create();

    /**
     * The entry of the call running on this thread, so the driver can add the window and control it resolved. Not
     * inheritable: thread pools started during a call would keep a finished entry alive. A Groovy evaluation, which runs
     * on a thread of its own, is handed its caller's entry with {@link #attach}.
     */
    private static final ThreadLocal<Entry> CURRENT = new ThreadLocal<>();

    private final QuPathGUI qupath;
    private File file;
    private Writer writer;
    private volatile String warning;
    /** The folder that last failed to open, so a folder that can't be written is reported once rather than per action. */
    private File failed;

    ActionLog(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    /** The folder the log goes in: the preference if set, else {@code mcp-logs} in QuPath's user directory. */
    static File directory() {
        var pref = folder.get();
        if (pref != null && !pref.isBlank())
            return new File(pref);
        var user = PathPrefs.userPathProperty().get();
        var base = user != null && !user.isBlank() ? new File(user) : PathPrefs.getDefaultQuPathUserDirectory().toFile();
        return new File(base, "mcp-logs");
    }

    /** Times one action; call {@link Entry#done} or {@link Entry#failed} when it finishes. */
    Entry begin(String source, String client, String tool, Map<String, Object> args) {
        var entry = new Entry(source, client, tool, args);
        CURRENT.set(entry);
        return entry;
    }

    /** The entry of the call running on this thread, or null; pass it to {@link #attach} on a thread the call starts. */
    static Entry current() {
        return CURRENT.get();
    }

    /** A note for the agent about the size of the log folder, at most once per check; null when there is nothing to say. */
    String takeWarning() {
        var w = warning;
        warning = null;
        return w;
    }

    static void attach(Entry entry) {
        CURRENT.set(entry);
    }

    /** Marks the running call as having entered text in a password field, so its text arguments are not written. */
    static void secret() {
        var entry = CURRENT.get();
        if (entry != null)
            entry.secret = true;
    }

    static void target(Node node, String action) {
        target(node == null || node.getScene() == null ? null : node.getScene().getWindow(), action);
    }

    /**
     * Notes what the running call acted on: the window's title and the driver's description of the action on the control,
     * such as {@code click “Apply”} or {@code set “Size:” to 7}. A call that acts more than once (a Groovy script) lists each.
     */
    static void target(Window window, String action) {
        var entry = CURRENT.get();
        if (entry == null)
            return;
        synchronized (entry) {
            if (entry.window == null && window != null)
                entry.window = GuiDriver.titleOf(window);
            entry.targets.add(action);
        }
    }

    class Entry {
        private final OffsetDateTime time = OffsetDateTime.now();
        private final long start = System.nanoTime();
        /** Read at the start too, so the call that turns logging off is still recorded. */
        private final boolean logging = enabled.get();
        private final String source, client, tool;
        private final Map<String, Object> args;
        private String window;
        private volatile boolean secret;
        private final List<String> targets = new ArrayList<>();

        private Entry(String source, String client, String tool, Map<String, Object> args) {
            this.source = source;
            this.client = client;
            this.tool = tool;
            this.args = args;
        }

        void done(String result) {
            write(true, result, null);
        }

        void failed(String error) {
            write(false, null, error);
        }

        private void write(boolean ok, String result, String error) {
            if (CURRENT.get() == this)
                CURRENT.remove();
            if (!logging && !enabled.get())
                return;
            try {
                append(GSON.toJson(entry(ok, result, error)));
            } catch (RuntimeException e) {
                // The log must never fail the action it records.
                logger.error("Could not write the action log", e);
            }
        }

        private Map<String, Object> entry(boolean ok, String result, String error) {
            var e = new LinkedHashMap<String, Object>();
            e.put("time", time.toString());
            e.put("source", source);
            if (client != null)
                e.put("client", client);
            e.put("tool", tool);
            e.put("args", args == null ? Map.of() : secret ? hidden(args) : args);
            synchronized (this) {
                if (window != null)
                    e.put("window", window);
                if (!targets.isEmpty())
                    e.put("target", String.join("; ", targets));
            }
            e.put("ok", ok);
            e.put("ms", Math.round((System.nanoTime() - start) / 1e6));
            if (result != null)
                e.put("result", result.length() > RESULT_CHARS ? result.substring(0, RESULT_CHARS) + "…" : result);
            if (error != null)
                e.put("error", error);
            // What the action acted on, so the line stands on its own.
            try {
                var project = qupath.getProject();
                if (project != null)
                    e.put("project", project.getPath().toString());
                var image = qupath.getImageData();
                if (image != null)
                    e.put("image", image.getServerPath());
            } catch (RuntimeException ignored) {
                // A project or image mid-change; the entry is still worth keeping.
            }
            return e;
        }
    }

    /** The arguments of a call that typed into a password field, with the text it typed replaced. */
    private static Map<String, Object> hidden(Map<String, Object> args) {
        var copy = new LinkedHashMap<>(args);
        for (var key : List.of("text", "value"))
            copy.computeIfPresent(key, (k, v) -> GuiDriver.HIDDEN);
        return copy;
    }

    private synchronized void append(String line) {
        var dir = directory();
        try {
            // The folder preference can change mid-session; the rest of the session goes to the new folder.
            if (writer != null && !dir.getAbsoluteFile().equals(file.getParentFile().getAbsoluteFile())) {
                writer.close();
                writer = null;
            }
            if (writer == null) {
                var name = file != null ? file.getName() : "mcp-" + FILE_STAMP.format(OffsetDateTime.now()) + ".jsonl";
                file = new File(dir, name);
                // The log holds the code the agent ran and what it read, so only the user can read it.
                if (POSIX) {
                    Files.createDirectories(dir.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    if (!file.exists())
                        Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } else {
                    Files.createDirectories(dir.toPath());
                }
                writer = new FileWriter(file, StandardCharsets.UTF_8, true);
                failed = null;
                logger.info("Agent actions logged to {}", file);
                tidy(dir);
            }
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException | RuntimeException e) {
            if (!dir.equals(failed))
                logger.error("Could not write the action log in {}", dir, e);
            failed = dir;
        }
    }

    /**
     * Keeps the logs under the size limit: deletes the oldest (never this session's) if the user has allowed it, else
     * warns the user and the agent. Names sort by date, so the oldest come first.
     */
    private void tidy(File dir) {
        var logs = dir.listFiles(f -> f.isFile() && LOG_NAME.matcher(f.getName()).matches());
        int limitMB = limit.get();
        if (logs == null || limitMB <= 0)
            return;
        long max = limitMB * 1024L * 1024L, bytes = 0;
        for (var log : logs)
            bytes += log.length();
        if (bytes <= max)
            return;
        if (deleteOldest.get()) {
            Arrays.sort(logs, Comparator.comparing(File::getName));
            int deleted = 0;
            long freed = 0;
            for (var log : logs) {
                if (bytes <= max)
                    break;
                long size = log.length();
                if (log.equals(file) || !log.delete())
                    continue;
                deleted++;
                freed += size;
                bytes -= size;
                logger.info("Deleted action log {} to stay under {} MB", log, limitMB);
            }
            if (deleted > 0) {
                var message = "Deleted the %d oldest agent action logs (%s) in %s to stay under the %d MB limit."
                        .formatted(deleted, size(freed), dir, limitMB);
                Platform.runLater(() -> Dialogs.showInfoNotification("qupath-mcp", message));
            }
            if (bytes <= max)
                return;
        }
        logger.warn("Action logs in {} total {}, over the {} MB limit", dir, size(bytes), limitMB);
        var message = "The agent action logs in %s total %s, over the %d MB limit. Delete old ones, or turn on "
                + "\"Delete oldest action logs over the limit\" in the MCP server preferences.";
        var text = message.formatted(dir, size(bytes), limitMB);
        Platform.runLater(() -> Dialogs.showWarningNotification("qupath-mcp", text));
        warning = ("Note: qupath-mcp's action logs in %s total %s, over the %d MB limit. Tell the user; don't delete "
                + "them yourself, they are the record of past sessions.").formatted(dir, size(bytes), limitMB);
    }

    private static String size(long bytes) {
        double mb = bytes / (1024.0 * 1024);
        return mb < 1024 ? "%.0f MB".formatted(mb) : "%.1f GB".formatted(mb / 1024);
    }
}
