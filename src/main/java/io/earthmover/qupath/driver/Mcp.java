package io.earthmover.qupath.driver;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The QuPath tools, served through the MCP Java SDK. Tool results are text or a PNG image; a failing tool returns
 * {@code isError} rather than a protocol error so the model can read the message.
 */
class Mcp {

    private static final String INSTRUCTIONS = "Drives the QuPath GUI and runs Groovy in it. Read windows as text with "
            + "qupath_describe before taking screenshots. `window` is a title substring; omitted, it means an open context "
            + "menu, else the focused or latest dialog, else the main window. GUI actions (menu, click, select, set, key, "
            + "close, open) return without waiting for event handlers, so a modal dialog cannot block them; about a second "
            + "later (open waits for the image first) they return the action result: the open windows and an outline of each window the action opened. A "
            + "result can start with windows that opened or closed since your last call.";

    private interface Handler {
        List<Content> call(Map<String, Object> args) throws Exception;
    }

    private final GuiDriver driver;
    private final DriverServer server;
    private final List<SyncToolSpecification> tools;
    /** The most recent sessions' windows; an older session that calls again starts afresh, as on its first call. */
    private final Map<String, Seen> sessions = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Seen> eldest) {
            return size() > 32;
        }
    });

    Mcp(GuiDriver driver, DriverServer server) {
        this.driver = driver;
        this.server = server;
        this.tools = List.of(
                tool("qupath_run_groovy", """
                        Run Groovy in QuPath; returns println output, the result and any error. `qupath` is the QuPathGUI. \
                        Code runs off the FX thread, so wrap GUI access in fx { }. Use the GUI tools for GUI steps. Helpers: \
                        fx{}, window(title), waitFor(title, seconds), lookup(window, text), click(node), findMenuItem("A>B"), \
                        fire(menuItem), describe(window), view(x, y, downsample); click and fire return at once.""",
                        """
                        {"code":{"type":"string"},"timeout_s":{"description":"Seconds before giving up","type":"number","default":60}}""", "code", a -> {
                            driver.indicator.note("running script…");
                            var r = server.evaluate(str(a, "code"), num(a, "timeout_s", 60));
                            var output = r.output().isEmpty() ? "" : "output:\n" + r.output() + "\n";
                            if (!r.ok())
                                throw new Exception(output + "error:\n" + trim(r.error()));
                            return text(output + "result: " + r.result());
                        }),
                tool("qupath_menu", """
                        Fire a menu item such as "Analyze>Cell detection>Cell detection"; returns the action result. A check \
                        item is toggled ("Extensions>MCP server>Enabled" turns this server off). File>Open... opens a native \
                        chooser no tool can drive; use qupath_open.""",
                        """
                        {"path":{"description":"Levels separated by >","type":"string"}}""", "path",
                        a -> text(driver.act(() -> {
                            driver.fire(driver.findMenuItem(str(a, "path")));
                            return null;
                        }))),
                tool("qupath_click", """
                        Click the button, checkbox, label or cell whose text, tooltip or accessible text is exactly `text`, or \
                        the control qupath_describe tags #n; a titled pane is expanded or collapsed. If several tooltips match, \
                        the error lists their #n; click repeated text by #n. Returns the action result, so an error popup's text comes back directly.""",
                        """
                        {"text":{"description":"Text, tooltip or #n","type":"string"},"window":{"description":"Title substring","type":"string"},\
                        "button":{"description":"right opens the context menu and lists its items","type":"string","enum":["left","right"],"default":"left"},"double":{"type":"boolean","default":false}}""",
                        "text",
                        a -> text(driver.act(() -> driver.click(driver.lookup(driver.window(str(a, "window")), str(a, "text")),
                                "right".equals(a.get("button")), Boolean.TRUE.equals(a.get("double")))))),
                tool("qupath_select", """
                        Select the item `text` in a combo box, choice box, list, tree, tab pane or table row (any cell). With \
                        `check`, tick (true) or clear (false) the checkbox of every matching table row instead. Returns the \
                        control's qupath_describe line, then the action result.""",
                        """
                        {"text":{"type":"string"},"window":{"description":"Title substring","type":"string"},"control":{"description":"#n or label; omit to try every list-like control","type":"string"},"check":{"type":"boolean"}}""",
                        "text", a -> text(driver.act(() -> driver.select(driver.window(str(a, "window")), str(a, "text"),
                                str(a, "control"), (Boolean) a.get("check"))))),
                tool("qupath_set", """
                        Set a spinner, slider, checkbox or text field to `value`. Returns the control's qupath_describe line \
                        with the value it now holds, then the action result.""",
                        """
                        {"value":{"type":["string","number","boolean"]},"control":{"description":"#n or label","type":"string"},"window":{"description":"Title substring","type":"string"}}""",
                        "value,control", a -> text(driver.act(() -> driver.set(driver.window(str(a, "window")),
                                valueText(a.get("value")), str(a, "control"))))),
                tool("qupath_key", """
                        Send space-separated key presses to a window's focused control: Enter (default button), Escape \
                        (cancel), Tab, Ctrl+A, Shortcut+S (Cmd on macOS). Returns the action result.""",
                        """
                        {"keys":{"type":"string"},"window":{"description":"Title substring","type":"string"}}""", "keys",
                        a -> text(driver.act(() -> driver.key(driver.window(str(a, "window")), str(a, "keys"))))),
                tool("qupath_close", """
                        Close a window as its close button would; a prompt it raises shows in the action result.""",
                        """
                        {"window":{"description":"Title substring","type":"string"}}""", null,
                        a -> text(driver.act(() -> driver.close(driver.window(str(a, "window")))))),
                tool("qupath_open", """
                        Open an image file path or URI in the viewer, without File>Open's native chooser. Returns the action \
                        result; then qupath_wait for the image path.""",
                        """
                        {"path_or_uri":{"type":"string"}}""", "path_or_uri",
                        a -> text(driver.act(() -> driver.open(str(a, "path_or_uri"))))),
                tool("qupath_describe", DESCRIBE, """
                        {"window":{"description":"Title substring; omit for all, or with control the default window","type":"string"},"control":{"description":"#n or label","type":"string"},"offset":{"type":"integer","default":0},"limit":{"type":"integer","default":20}}""",
                        null, a -> text(a.get("control") != null
                                ? driver.describe(driver.window(str(a, "window")), str(a, "control"), (int) num(a, "offset", 0), (int) num(a, "limit", 20))
                                : describe(str(a, "window"), (int) num(a, "offset", 0), (int) num(a, "limit", 20)))),
                tool("qupath_wait", """
                        Wait until `text` appears in the qupath_describe output, or with `gone` disappears. Returns the \
                        matching lines, the open windows and any window that opened meanwhile. Searches every table row. \
                        Without `window` it also watches the main-window summary, so an image path shows an image switch.""",
                        """
                        {"text":{"type":"string"},"window":{"description":"Title substring; omit for all","type":"string"},"timeout_s":{"type":"number","default":30},"gone":{"type":"boolean","default":false}}""", "text", a -> {
                            var text = str(a, "text");
                            double timeout = num(a, "timeout_s", 30);
                            boolean gone = Boolean.TRUE.equals(a.get("gone"));
                            long deadline = System.nanoTime() + (long) (timeout * 1e9);
                            var before = driver.windowsNow();
                            while (true) {
                                // Matched against every row, so an item far down a long table counts once it is there.
                                var all = driver.outline(str(a, "window"), 0, GuiDriver.ALL, false);
                                boolean done = all.contains(text) != gone;
                                if (done || System.nanoTime() >= deadline) {
                                    var opened = driver.openedSince(before);
                                    opened = opened.isEmpty() ? "" : "\n\nOpened while waiting:\n" + opened;
                                    if (done)
                                        return text((gone ? "'%s' is gone".formatted(text) : matching(all, text))
                                                + "\n\n" + driver.windowsText() + opened);
                                    var out = driver.outline(str(a, "window"), 0, 20) + opened;
                                    throw new Exception(("Timed out after %ss waiting for '%s' to %s; every row of every table, "
                                            + "list and tree was searched. Last output, first 20 rows of each:\n%s").formatted(
                                            timeout, text, gone ? "disappear" : "appear", out));
                                }
                                Thread.sleep(500);
                            }
                        }),
                tool("qupath_view", """
                        Centre the viewer on image pixel (x, y) at `downsample` (1 is full resolution), optionally at z / t.""",
                        """
                        {"x":{"type":"number"},"y":{"type":"number"},"downsample":{"type":"number"},"z":{"type":"integer"},"t":{"type":"integer"}}""", "x,y,downsample", a -> {
                            driver.view(num(a, "x", 0), num(a, "y", 0), num(a, "downsample", 1),
                                    a.get("z") instanceof Number z ? z.intValue() : null, a.get("t") instanceof Number t ? t.intValue() : null);
                            return text("ok");
                        }),
                tool("qupath_screenshot", """
                        Screenshot a window; qupath_describe is better for dialogs.""",
                        """
                        {"window":{"description":"Title substring; omit for the main window","type":"string"},"index":{"description":"Which match, as qupath_describe numbers them","type":"integer","default":0},"max_size":{"description":"Longest side in pixels","type":"integer","default":1024},\
                        "format":{"type":"string","enum":["png","jpeg"],"default":"png"},"viewer":{"description":"Crop to the image viewer","type":"boolean","default":false},\
                        "region":{"description":"Crop to [x, y, width, height]","type":"array","items":{"type":"number"},"minItems":4,"maxItems":4}}""", null, a -> {
                            double[] region = a.get("region") instanceof List<?> r
                                    ? r.stream().mapToDouble(n -> ((Number) n).doubleValue()).toArray() : null;
                            var shot = driver.screenshot(str(a, "window"), (int) num(a, "index", 0), (int) num(a, "max_size", 1024),
                                    "jpeg".equals(a.get("format")) ? "jpeg" : "png", num(a, "quality", 0.85),
                                    Boolean.TRUE.equals(a.get("viewer")), region);
                            if (shot == null)
                                throw new Exception("No open window %d whose title contains '%s'; see qupath_describe.".formatted(
                                        (int) num(a, "index", 0), str(a, "window")));
                            return List.of(new ImageContent(null, Base64.getEncoder().encodeToString(shot.bytes()), shot.mime()),
                                    new TextContent("%dx%d %s".formatted(shot.width(), shot.height(), shot.mime().substring(6))));
                        }),
                tool("qupath_show_actions", """
                        Set how qupath-mcp shows the person what you do: "mark" rings each control you act on, "paced" also \
                        moves a pointer there and waits `delay_ms` first, "off" shows nothing. Returns the setting, which persists across \
                        restarts: tell the person if you change it.""",
                        """
                        {"mode":{"type":"string","enum":["off","mark","paced"]},"delay_ms":{"type":"integer","minimum":0,"maximum":5000}}""",
                        null, a -> {
                            driver.fx(() -> {
                                if (a.get("mode") instanceof String m)
                                    Indicator.mode.set(Indicator.Mode.valueOf(m.toUpperCase()));
                                if (a.get("delay_ms") instanceof Number d)
                                    Indicator.delayMs.set(Math.max(0, Math.min(5000, d.intValue())));
                                return null;
                            });
                            return text("mode: %s, delay_ms: %d".formatted(Indicator.mode.get().name().toLowerCase(),
                                    Indicator.delayMs.get()));
                        }));
    }

    private static final String DESCRIBE = """
            Text outline of a window: one control per line, tagged #n where qupath_click, qupath_select and qupath_set \
            accept it. `control` returns just that control. A [collapsed] titled pane hides its controls until clicked. \
            Tables, lists and trees show `limit` rows from `offset`. Without `window`: every dialog, then a summary of the \
            main window (image, zoom, centre, annotations).""";

    private String describe(String window, int offset, int limit) throws Exception {
        var out = driver.outline(window, offset, limit);
        if (window != null && out.isEmpty())
            throw new Exception("No open window whose title contains '%s'; see qupath_describe.".formatted(window));
        return out;
    }

    /** The outline lines that contain {@code text}, a long one shortened to the part around it. */
    private static String matching(String outline, String text) {
        var lines = outline.lines().filter(l -> l.contains(text)).map(String::strip).toList();
        if (lines.isEmpty())
            return "found '%s'".formatted(text);
        var shown = lines.stream().limit(20).map(l -> {
            if (l.length() <= 200)
                return l;
            int at = l.indexOf(text), from = Math.max(0, at - 80), to = Math.min(l.length(), at + text.length() + 80);
            return (from > 0 ? "…" : "") + l.substring(from, to) + (to < l.length() ? "…" : "");
        }).collect(Collectors.joining("\n"));
        return lines.size() <= 20 ? shown : shown + "\n+%d more lines".formatted(lines.size() - 20);
    }

    void build(McpStreamableServerTransportProvider transport) {
        McpServer.sync(transport).serverInfo("qupath", Mcp.class.getPackage().getImplementationVersion())
                .instructions(INSTRUCTIONS).tools(tools).build();
    }

    private SyncToolSpecification tool(String name, String description, String properties, String required, Handler handler) {
        var schema = """
                {"type":"object","properties":%s,"required":[%s]}""".formatted(properties,
                required == null ? "" : Stream.of(required.split(",")).map(r -> "\"" + r + "\"").collect(Collectors.joining(",")));
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name(name).description(description)
                        .inputSchema(McpJsonDefaults.getMapper(), schema).build())
                .callHandler((exchange, request) -> {
                    var client = exchange.getClientInfo();
                    var who = client == null ? null : client.title() != null ? client.title() : client.name();
                    driver.indicator.busy(true, who);
                    var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                    var entry = driver.actions.begin("mcp", who, name, args);
                    var seen = sessions.computeIfAbsent(Objects.requireNonNullElse(exchange.sessionId(), ""), k -> new Seen());
                    var changes = changes(seen);
                    var outlined = new HashSet<Window>();
                    var listed = new boolean[1];
                    driver.outlined.set(outlined);
                    driver.listed.set(listed);
                    try {
                        // Clients that load tool schemas lazily may call without arguments; say what's needed.
                        if (required != null)
                            for (var r : required.split(","))
                                if (args.get(r) == null)
                                    throw new IllegalArgumentException("%s needs `%s`; see its input schema.".formatted(name, r));
                        // The notice leaves out what the result outlines, so it is built once the handler has run.
                        var result = handler.call(args);
                        var content = prepend(notice(changes, outlined), result);
                        entry.done(summary(content));
                        var warning = driver.actions.takeWarning();
                        if (warning != null)
                            content = Stream.concat(content.stream(), Stream.of((Content) new TextContent(warning))).toList();
                        return CallToolResult.builder().content(content).isError(false).build();
                    } catch (Throwable t) {
                        var cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
                        var message = notice(changes, outlined)
                                + (cause.getMessage() != null ? cause.getMessage() : cause.toString());
                        entry.failed(message);
                        return CallToolResult.builder().addTextContent(message).isError(true).build();
                    } finally {
                        driver.outlined.remove();
                        driver.listed.remove();
                        settle(seen, outlined, listed[0]);
                        driver.indicator.busy(false, null);
                    }
                }).build();
    }

    /**
     * The stages one MCP session's agent knows are open: those showing when its previous call ended, and any a result
     * has outlined since. Weak, so a closed dialog is not kept alive by a session that has gone. Guarded by itself.
     */
    private static final class Seen {
        final Set<Window> windows = Collections.newSetFromMap(new WeakHashMap<>());
        boolean primed;
    }

    /** The lines reporting stages that opened since the session's previous call, and the titles of those that closed. */
    private record Changes(Map<Window, String> opened, List<String> closed) {}

    /**
     * What changed between the session's previous call and this one, which no result has reported: a dialog that a
     * background task opens a few seconds after an action returned. Null on the session's first call or when the FX
     * thread is busy, so this never holds up a call; the change is then reported by a later one.
     */
    private Changes changes(Seen seen) {
        // Held across the FX round trip so that two calls made at once do not both report one change.
        synchronized (seen) {
            return changesLocked(seen);
        }
    }

    private Changes changesLocked(Seen seen) {
        var known = new ArrayList<>(seen.windows);
        boolean primed = seen.primed;
        try {
            var now = driver.fxWithin(300, () -> {
                var showing = GuiDriver.showingStages();
                var opened = new LinkedHashMap<Window, String>();
                for (var w : showing)
                    if (primed && !known.contains(w))
                        opened.put(w, driver.openedLine(w));
                var closed = known.stream().filter(w -> !w.isShowing()).map(GuiDriver::titleOf).toList();
                return Map.entry(showing, new Changes(opened, closed));
            });
            if (now == null)
                return null;
            seen.windows.clear();
            seen.windows.addAll(now.getKey());
            seen.primed = true;
            return primed ? now.getValue() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Records what the agent now knows: the windows this call outlined and, when its result listed the open windows,
     * that the others in that list are all that is open. Without a list, a window that closed during the call is kept,
     * so the next call reports it.
     */
    private void settle(Seen seen, Set<Window> outlined, boolean listed) {
        List<Window> showing = null;
        if (listed) {
            try {
                showing = driver.fxWithin(300, GuiDriver::showingStages);
            } catch (Exception e) {
                showing = null;
            }
        }
        synchronized (seen) {
            if (!seen.primed)
                return;
            // Only stages: a context menu or tooltip the result outlined is not tracked, so it is not reported closed.
            outlined.stream().filter(Stage.class::isInstance).forEach(seen.windows::add);
            if (showing != null)
                seen.windows.retainAll(showing);
        }
    }

    /** One line per change the result does not already show, each ending in a newline; empty if nothing changed. */
    private static String notice(Changes changes, Set<Window> outlined) {
        if (changes == null)
            return "";
        var sb = new StringBuilder();
        changes.opened().forEach((w, line) -> {
            if (!outlined.contains(w))
                sb.append(line).append('\n');
        });
        if (!changes.closed().isEmpty())
            sb.append(changes.closed().stream().map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(", ", "Closed since your last call: ", "\n")));
        return sb.isEmpty() ? "" : sb.append('\n').toString();
    }

    private static List<Content> prepend(String notice, List<Content> content) {
        if (notice.isEmpty())
            return content;
        if (!content.isEmpty() && content.get(0) instanceof TextContent t)
            return Stream.concat(Stream.of((Content) new TextContent(notice + t.text())), content.stream().skip(1)).toList();
        return Stream.concat(Stream.of((Content) new TextContent(notice.strip())), content.stream()).toList();
    }

    /** The text a tool returned, for the action log; an image is noted by its size rather than kept. */
    private static String summary(List<Content> content) {
        return content.stream().map(c -> c instanceof TextContent t ? t.text()
                : c instanceof ImageContent i ? "[image " + i.mimeType() + ", " + i.data().length() + " base64 chars]"
                : "[" + c.type() + "]").collect(Collectors.joining("\n"));
    }

    private static List<Content> text(String s) {
        return List.of(new TextContent(s));
    }

    private static String str(Map<String, Object> a, String key) {
        var v = a.get(key);
        return v == null ? null : v instanceof String t ? t : valueText(v);
    }

    /** A JSON number such as 5.0 as 5, so an integer spinner can parse it. */
    private static String valueText(Object v) {
        return v instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : String.valueOf(v);
    }

    private static double num(Map<String, Object> a, String key, double fallback) {
        return a.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    /** A Groovy failure without the JVM's frames: the exception, its causes, and the script lines they came from. */
    private static String trim(String trace) {
        var lines = trace.lines().toList();
        return Stream.concat(Stream.of(lines.get(0)),
                lines.stream().skip(1).filter(l -> l.startsWith("Caused by") || l.contains("Script1")))
                .collect(Collectors.joining("\n"));
    }
}
