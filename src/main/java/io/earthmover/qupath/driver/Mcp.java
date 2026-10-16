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
            + "qupath_describe before taking screenshots. The GUI actions (qupath_menu, qupath_click, qupath_select, "
            + "qupath_set, qupath_key, qupath_close, qupath_open) return without waiting for event handlers, so a modal "
            + "dialog cannot block them. About a second later each returns its action result: the open windows and the "
            + "outline of each window the action opened, a large one cut short. A result can start with windows that "
            + "opened or closed since your last call, such as a late dialog.";

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
                        Run Groovy in QuPath and return its println output, result and any error.

                        `qupath` is the QuPathGUI. Code runs on a background thread, so wrap GUI access in fx { ... }. \
                        Prefer qupath_menu, qupath_click, qupath_describe and the other tools for GUI steps; use this for \
                        anything else. Helpers: fx{}, window(titleSubstring or null), waitFor(titleSubstring, seconds), \
                        lookup(window, text), click(node), findMenuItem("Help>License"), fire(menuItem), \
                        typeInto(window, text[, field]), describe(window), view(x, y, downsample[, z, t]). click and fire \
                        return at once, so a modal dialog they open does not block.""",
                        """
                        {"code":{"description":"Groovy source","type":"string"},"timeout_s":{"description":"Seconds before the call gives up","type":"number","default":60}}""", "code", a -> {
                            driver.indicator.note("running script…");
                            var r = server.evaluate(str(a, "code"), num(a, "timeout_s", 60));
                            var output = r.output().isEmpty() ? "" : "output:\n" + r.output() + "\n";
                            if (!r.ok())
                                throw new Exception(output + "error:\n" + trim(r.error()));
                            return text(output + "result: " + r.result());
                        }),
                tool("qupath_menu", """
                        Fire a menu item such as "Analyze>Cell detection>Cell detection" and return the action result. \
                        Drive the dialog it opens with qupath_click, qupath_set and qupath_type.

                        A check item is toggled: "Extensions>MCP server>Enabled" turns this server off after replying, until \
                        the person turns it back on. File>Open... shows a native chooser no tool can drive; use qupath_open.""",
                        """
                        {"path":{"description":"Menu path with > between levels, e.g. File>Open URI...","type":"string"}}""", "path",
                        a -> text(driver.act(() -> {
                            driver.fire(driver.findMenuItem(str(a, "path")));
                            return null;
                        }))),
                tool("qupath_click", """
                        Click the button, checkbox, label, or table or tree cell whose text is exactly `text`, else whose \
                        tooltip (or its first line) or accessible text is, which names icon-only buttons; a titled pane is \
                        expanded or collapsed. `text` may instead be a control's #n from qupath_describe; when several controls \
                        match, the error lists them by #n. Returns the action result (the open windows and any window the click \
                        opened), so an error popup's text comes back directly.""",
                        """
                        {"text":{"description":"Exact text, tooltip or #n from qupath_describe","type":"string"},"window":{"description":"Title substring; default: context menu, else focused or latest dialog, else main window","type":"string"},\
                        "button":{"description":"right opens the context menu, whose items the result lists","type":"string","enum":["left","right"],"default":"left"},"double":{"description":"Double-click","type":"boolean","default":false}}""",
                        "text",
                        a -> text(driver.act(() -> driver.click(driver.lookup(driver.window(str(a, "window")), str(a, "text")),
                                "right".equals(a.get("button")), Boolean.TRUE.equals(a.get("double")))))),
                tool("qupath_type", """
                        Set the text of the `field`-th (0-based) text field in a window, in the order qupath_describe lists \
                        them, and return the field's text.""",
                        """
                        {"text":{"description":"Replaces the field's content","type":"string"},"window":{"description":"Title substring; default: context menu, else focused or latest dialog, else main window","type":"string"},"field":{"description":"Which text field, 0 = first in the window","type":"integer","default":0}}""", "text", a -> {
                            var window = driver.window(str(a, "window"));
                            int field = (int) num(a, "field", 0);
                            return text("typed into %s field %d: \"%s\"".formatted(GuiDriver.titleOf(window), field,
                                    driver.typeInto(window, str(a, "text"), field)));
                        }),
                tool("qupath_select", """
                        Select the item named `text` in a combo box, choice box, list, tree (parents are expanded; QuPath's \
                        project image list is one), tab pane or table row (any cell with that text).

                        `control` narrows the search to one control. With `check` true or false, a table's matching rows (all of them) get their checkbox ticked or cleared \
                        instead. Returns the control's qupath_describe line, then the action result.""",
                        """
                        {"text":{"description":"Text of the item, tab or table row to select","type":"string"},"window":{"description":"Title substring; default: context menu, else focused or latest dialog, else main window","type":"string"},"control":{"description":"Its #n from qupath_describe, or the label next to it","type":"string"},"check":{"description":"For table rows with a checkbox column: true ticks, false clears every matching row","type":"boolean"}}""",
                        "text", a -> text(driver.act(() -> driver.select(driver.window(str(a, "window")), str(a, "text"),
                                str(a, "control"), (Boolean) a.get("check"))))),
                tool("qupath_set", """
                        Set a spinner, slider, checkbox (true/false) or text field to `value`.

                        Returns the control's qupath_describe line with the value it now holds, then the action result.""",
                        """
                        {"value":{"description":"New value: a number for spinners and sliders, true/false for checkboxes, text for fields","type":["string","number","boolean"]},"control":{"description":"Its #n from qupath_describe, or the label next to it","type":"string"},"window":{"description":"Title substring; default: context menu, else focused or latest dialog, else main window","type":"string"}}""",
                        "value,control", a -> text(driver.act(() -> driver.set(driver.window(str(a, "window")),
                                valueText(a.get("value")), str(a, "control"))))),
                tool("qupath_key", """
                        Send key presses to the focused control of a window: "Enter", "Escape", "Tab", "Ctrl+A", "Shortcut+S" \
                        (Shortcut is Cmd on macOS), space-separated for several.

                        Enter triggers a dialog's default button and Escape its cancel button. Returns what was sent, then the \
                        action result.""",
                        """
                        {"keys":{"description":"Space-separated key presses","type":"string"},"window":{"description":"Title substring; default: context menu, else focused or latest dialog, else main window","type":"string"}}""", "keys",
                        a -> text(driver.act(() -> driver.key(driver.window(str(a, "window")), str(a, "keys"))))),
                tool("qupath_close", """
                        Close a window as its close button would; one that asks first (such as about unsaved changes) shows \
                        its prompt, which the action result lists. Use qupath_quit to close QuPath itself.""",
                        """
                        {"window":{"description":"Title substring; default: context menu, else focused or latest dialog","type":"string"}}""", null,
                        a -> text(driver.act(() -> driver.close(driver.window(str(a, "window")))))),
                tool("qupath_open", """
                        Open an image by file path or URI in the active viewer, without the native chooser of File>Open.... \
                        Returns what happened, then the action result (which shows any prompt, such as about unsaved \
                        changes); follow with qupath_wait on the image path.""",
                        """
                        {"path_or_uri":{"description":"Image file path or URI to open in the viewer","type":"string"}}""", "path_or_uri",
                        a -> text(driver.act(() -> driver.open(str(a, "path_or_uri"))))),
                tool("qupath_describe", DESCRIBE, """
                        {"window":{"description":"Title substring; omit to describe every open window plus a main-window summary","type":"string"},"control":{"description":"Only this control: its #n or the label next to it, found as qupath_set finds it (window defaults to the focused dialog)","type":"string"},"offset":{"description":"First row to list in tables, lists and trees","type":"integer","default":0},"limit":{"description":"Rows to list per table, list or tree; every control is always listed","type":"integer","default":20}}""",
                        null, a -> text(a.get("control") != null
                                ? driver.describe(driver.window(str(a, "window")), str(a, "control"), (int) num(a, "offset", 0), (int) num(a, "limit", 20))
                                : describe(str(a, "window"), (int) num(a, "offset", 0), (int) num(a, "limit", 20)))),
                tool("qupath_wait", """
                        Wait until `text` appears in (or, with gone=true, disappears from) the qupath_describe output for \
                        `window`; return the lines that contain it and the open windows.

                        With no `window` it watches all windows and the main-window summary, whose image path shows an image \
                        switch. Every row of tables, lists and trees is searched, not only the printed page. Fails on timeout \
                        with the last output. Either result ends with any window that opened meanwhile, such as a warning.""",
                        """
                        {"text":{"description":"Text to wait for in the qupath_describe output","type":"string"},"window":{"description":"Title substring; omit to watch all windows and the main-window summary","type":"string"},"timeout_s":{"description":"Seconds to wait before failing","type":"number","default":30},\
                        "gone":{"description":"Wait for the text to disappear instead","type":"boolean","default":false}}""", "text", a -> {
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
                        Centre the active viewer on image pixel (x, y) at `downsample`, optionally at plane z / timepoint t.

                        It sets the zoom before the centre, so the centre does not shift as it does after setDownsampleFactor \
                        in Groovy.""",
                        """
                        {"x":{"description":"Image x of the new centre, in full-resolution pixels","type":"number"},"y":{"description":"Image y of the new centre, in full-resolution pixels","type":"number"},"downsample":{"description":"Image pixels per screen pixel; 1 is full resolution","type":"number"},\
                        "z":{"description":"z-slice, or omit to keep","type":"integer"},"t":{"description":"Timepoint, or omit to keep","type":"integer"}}""", "x,y,downsample", a -> {
                            driver.view(num(a, "x", 0), num(a, "y", 0), num(a, "downsample", 1),
                                    a.get("z") instanceof Number z ? z.intValue() : null, a.get("t") instanceof Number t ? t.intValue() : null);
                            return text("ok");
                        }),
                tool("qupath_windows", "List open QuPath windows, one per line: title — WxH, with (focused) on the focused one.",
                        "{}", null, a -> text(driver.windowsText())),
                tool("qupath_screenshot", """
                        Screenshot a QuPath window, default the main window, and report its size. Read dialogs with \
                        qupath_describe instead. The macOS menu bar is not captured.""",
                        """
                        {"window":{"description":"Title substring (see qupath_windows); omit for the main window","type":"string"},"index":{"description":"Which match when several windows share the title, numbered as by qupath_describe","type":"integer","default":0},"max_size":{"description":"Longest side in pixels; larger images are downscaled, never enlarged","type":"integer","default":1024},\
                        "format":{"description":"png for dialogs, jpeg for slide or viewer content","type":"string","enum":["png","jpeg"],"default":"png"},"quality":{"description":"JPEG quality, 0 to 1","type":"number","default":0.85},\
                        "viewer":{"description":"Crop to the main window's active viewer","type":"boolean","default":false},\
                        "region":{"description":"Crop to [x, y, width, height] in window coordinates","type":"array","items":{"type":"number"},"minItems":4,"maxItems":4}}""", null, a -> {
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
                        Set how qupath-mcp shows the person watching what you do, and return the setting. "mark" (default) \
                        rings each control you act on, without slowing you down; "paced" also moves a pointer to it and \
                        waits `delay_ms` first, for when the person wants to follow along; "off" shows nothing.""",
                        """
                        {"mode":{"description":"Omit to keep","type":"string","enum":["off","mark","paced"]},"delay_ms":{"description":"Pause before each paced action; omit to keep","type":"integer","minimum":0,"maximum":5000}}""",
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
                        }),
                tool("qupath_quit", "Quit QuPath.", "{}", null, a -> {
                    // Exiting at once would drop the connection before the reply is written.
                    var t = new Thread(() -> {
                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException e) {
                            return;
                        }
                        driver.quit();
                    });
                    t.setDaemon(true);
                    t.start();
                    return text("quit requested");
                }));
    }

    private static final String DESCRIBE = """
            Text outline of a window's controls: buttons, checkboxes, labels, text fields, spinners, sliders, combo boxes, \
            tabs, tables, lists, trees.

            Read a dialog's state with this before qupath_screenshot. Controls that qupath_click, qupath_select and \
            qupath_set can address carry a `#n` tag, and a label is shown on the control it names; `control` returns \
            only that control's lines. A TitledPane marked [collapsed] hides its controls until qupath_click expands \
            it. Every control is listed; `limit` pages only the rows of tables, lists and trees, from `offset`, ending \
            with "rows X–Y of N"; the next page is offset=Y. `window` describes every window whose title contains it, \
            each headed by its index. With no `window`, describes every window but the main one, then gives the main \
            window's title, image path, downsample, centre, z/t and annotation count.""";

    private String describe(String window, int offset, int limit) throws Exception {
        var out = driver.outline(window, offset, limit);
        if (window != null && out.isEmpty())
            throw new Exception("No open window whose title contains '%s'; see qupath_windows.".formatted(window));
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
