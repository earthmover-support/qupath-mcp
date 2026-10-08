package io.earthmover.qupath.driver;

import java.util.Base64;
import java.util.List;
import java.util.Map;
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

/**
 * The QuPath tools, served through the MCP Java SDK. Tool results are text or a PNG image; a failing tool returns
 * {@code isError} rather than a protocol error so the model can read the message.
 */
class Mcp {

    private static final String INSTRUCTIONS = "Drives the QuPath GUI and runs Groovy in it. QuPath must be running. "
            + "Call qupath_describe to read a window as text before taking screenshots.";

    private interface Handler {
        List<Content> call(Map<String, Object> args) throws Exception;
    }

    private final GuiDriver driver;
    private final DriverServer server;
    private final List<SyncToolSpecification> tools;

    Mcp(GuiDriver driver, DriverServer server) {
        this.driver = driver;
        this.server = server;
        this.tools = List.of(
                tool("qupath_run_groovy", """
                        Run Groovy in the running QuPath and return its output, result and any error.

                        `qupath` is the QuPathGUI. Code runs on a background thread, so wrap GUI access in fx { ... }. \
                        Prefer the dedicated tools (qupath_menu, qupath_click, qupath_type, qupath_describe, qupath_view) for \
                        common GUI steps; use this for anything else. \
                        Helpers: fx{}, snap(name), snapMain(name), waitFor(titleSubstring, seconds), window(titleSubstring or null), \
                        lookup(window, text), click(node) and fire(menuItem) (both return immediately, so a modal dialog they open \
                        does not block), findMenuItem("Help>License"), typeInto(window, text[, fieldIndex]), describe(window), \
                        view(x, y, downsample[, z, t]), quit(). println is captured.""",
                        """
                        {"code":{"type":"string"},"timeout_s":{"type":"number","default":60}}""", "code", a -> {
                            var r = server.evaluate(str(a, "code"), num(a, "timeout_s", 60));
                            var output = r.output().isEmpty() ? "" : "output:\n" + r.output() + "\n";
                            if (!r.ok())
                                throw new Exception(output + "error:\n" + trim(r.error()));
                            return text(output + "result: " + r.result());
                        }),
                tool("qupath_menu", """
                        Fire a menu item such as "File>Open..." or "Analyze>Cell detection>Cell detection" without blocking.

                        A check menu item is toggled, as a click would (so "Extensions>MCP server" turns this server off, after \
                        replying; turn it back on from QuPath's menu). Waits about a second and returns the open windows, one per \
                        line, then the description of each window the item opened, or "No new window opened." File choosers are \
                        native dialogs the tools cannot see: if the UI stops responding, that is reported. \
                        Use qupath_click / qupath_type to drive the dialog.""",
                        """
                        {"path":{"type":"string"}}""", "path",
                        a -> text(driver.act(() -> {
                            driver.fire(driver.findMenuItem(str(a, "path")));
                            return null;
                        }))),
                tool("qupath_click", """
                        Click the button, checkbox or other labeled node (label, table or tree cell) whose text is exactly `text`, \
                        without blocking (safe for buttons that open modal dialogs).

                        `window` is a title substring; default is an open context menu, else the focused or topmost non-main \
                        window, else the main window. `button` "right" opens the node's context menu and returns its items; \
                        `double` double-clicks. Waits about a second and returns the open windows, one per line, then the \
                        description of each window the click opened (so an error popup's text is returned directly), or \
                        "No new window opened." Prefer this over qupath_run_groovy for dialogs.""",
                        """
                        {"text":{"type":"string"},"window":{"type":"string"},\
                        "button":{"type":"string","enum":["left","right"],"default":"left"},"double":{"type":"boolean","default":false}}""",
                        "text",
                        a -> text(driver.act(() -> {
                            driver.click(driver.lookup(driver.window(str(a, "window")), str(a, "text")),
                                    "right".equals(a.get("button")), Boolean.TRUE.equals(a.get("double")));
                            return null;
                        }))),
                tool("qupath_type", """
                        Set the text of the `field`-th (0-based) text field in a window; `window` defaults as in qupath_click.

                        Use qupath_describe to see which field is which. Returns the field's text after setting it.""",
                        """
                        {"text":{"type":"string"},"window":{"type":"string"},"field":{"type":"integer","default":0}}""", "text", a -> {
                            var window = driver.window(str(a, "window"));
                            int field = (int) num(a, "field", 0);
                            return text("typed into %s field %d: \"%s\"".formatted(GuiDriver.titleOf(window), field,
                                    driver.typeInto(window, str(a, "text"), field)));
                        }),
                tool("qupath_select", """
                        Select the item named `text` in a combo box, choice box, list, tree (parents are expanded; QuPath's \
                        project image list is one), tab pane or table row (any cell with that text).

                        `control` narrows the search to one control: its `#n` from qupath_describe, or the text of its label. \
                        With `check` true or false, a table's matching rows (all of them) get their checkbox ticked or cleared \
                        instead. Returns what was selected, the open windows and any window it opened.""",
                        """
                        {"text":{"type":"string"},"window":{"type":"string"},"control":{"type":"string"},"check":{"type":"boolean"}}""",
                        "text", a -> text(driver.act(() -> driver.select(driver.window(str(a, "window")), str(a, "text"),
                                str(a, "control"), (Boolean) a.get("check"))))),
                tool("qupath_set", """
                        Set a spinner, slider, checkbox (true/false) or text field to `value`.

                        `control` is its `#n` from qupath_describe, or the text of its label. Returns what was set, the open \
                        windows and any window it opened.""",
                        """
                        {"value":{"type":["string","number","boolean"]},"control":{"type":"string"},"window":{"type":"string"}}""",
                        "value,control", a -> text(driver.act(() -> driver.set(driver.window(str(a, "window")),
                                valueText(a.get("value")), str(a, "control"))))),
                tool("qupath_key", """
                        Send key presses to the focused control of a window: "Enter", "Escape", "Tab", "Ctrl+A", "Shortcut+S" \
                        (Shortcut is Cmd on macOS), space-separated for several.

                        Enter triggers a dialog's default button and Escape its cancel button. `window` defaults as in \
                        qupath_click. Returns what was sent, the open windows and any window it opened.""",
                        """
                        {"keys":{"type":"string"},"window":{"type":"string"}}""", "keys",
                        a -> text(driver.act(() -> driver.key(driver.window(str(a, "window")), str(a, "keys"))))),
                tool("qupath_open", """
                        Open an image by file path or URI in the active viewer without any file chooser.

                        Use this instead of File>Open..., whose native chooser the tools cannot drive. Returns the result, the \
                        open windows and any window it opened (for example a prompt about unsaved changes); follow with \
                        qupath_wait on the image path.""",
                        """
                        {"path_or_uri":{"type":"string"}}""", "path_or_uri",
                        a -> text(driver.act(() -> driver.open(str(a, "path_or_uri"))))),
                tool("qupath_describe", DESCRIBE, """
                        {"window":{"type":"string"},"offset":{"type":"integer","default":0},"limit":{"type":"integer","default":20}}""",
                        null, a -> text(describe(str(a, "window"), (int) num(a, "offset", 0), (int) num(a, "limit", 20)))),
                tool("qupath_wait", """
                        Wait until `text` appears in (or, with gone=true, disappears from) the qupath_describe output; return that output.

                        `window` is a title substring, as in qupath_describe. With no `window` it watches all open windows and the \
                        main-window summary, so it also detects an image switch by the image path. Fails on timeout with the last output.""",
                        """
                        {"text":{"type":"string"},"window":{"type":"string"},"timeout_s":{"type":"number","default":30},\
                        "gone":{"type":"boolean","default":false}}""", "text", a -> {
                            var text = str(a, "text");
                            double timeout = num(a, "timeout_s", 30);
                            boolean gone = Boolean.TRUE.equals(a.get("gone"));
                            long deadline = System.nanoTime() + (long) (timeout * 1e9);
                            while (true) {
                                var out = driver.outline(str(a, "window"), 0, 20);
                                if (out.contains(text) != gone)
                                    return text(out);
                                if (System.nanoTime() >= deadline)
                                    throw new Exception("Timed out after %ss waiting for '%s' to %s; last output:\n%s".formatted(
                                            timeout, text, gone ? "disappear" : "appear", out));
                                Thread.sleep(500);
                            }
                        }),
                tool("qupath_view", """
                        Centre the active viewer on image pixel (x, y) at `downsample`, optionally at plane z / timepoint t.

                        Use this instead of setting viewer properties by hand: it sets the zoom before the centre, which \
                        setDownsampleFactor would otherwise shift.""",
                        """
                        {"x":{"type":"number"},"y":{"type":"number"},"downsample":{"type":"number"},\
                        "z":{"type":"integer"},"t":{"type":"integer"}}""", "x,y,downsample", a -> {
                            driver.view(num(a, "x", 0), num(a, "y", 0), num(a, "downsample", 1),
                                    a.get("z") instanceof Number z ? z.intValue() : null, a.get("t") instanceof Number t ? t.intValue() : null);
                            return text("ok");
                        }),
                tool("qupath_windows", "List open QuPath windows, one per line: title — WxH, with (focused) on the focused one.",
                        "{}", null, a -> text(driver.windowsText())),
                tool("qupath_screenshot", """
                        Screenshot a QuPath window. `window` is a title substring (see qupath_windows); default is the main window.

                        Image cost grows with pixel size, so the longest side is capped at `max_size` (default 1024; never \
                        enlarged) and the size is reported in a text item. `format` is "png" (default, sharp text for dialogs) or \
                        "jpeg" (smaller; use it for slides and viewer content; `quality` 0-1, default 0.85). `viewer` true crops \
                        to the active viewer of the main window; `region` [x, y, w, h] crops in window coordinates. `index` picks \
                        among windows with the same title (0 = first), as numbered by qupath_describe. \
                        The macOS menu bar is not captured.""",
                        """
                        {"window":{"type":"string"},"index":{"type":"integer","default":0},"max_size":{"type":"integer","default":1024},\
                        "format":{"type":"string","enum":["png","jpeg"],"default":"png"},"quality":{"type":"number","default":0.85},\
                        "viewer":{"type":"boolean","default":false},\
                        "region":{"type":"array","items":{"type":"number"},"minItems":4,"maxItems":4}}""", null, a -> {
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

            Use this before qupath_screenshot to read a dialog's state as text. Controls that qupath_select and qupath_set \
            can address carry a `#n` tag. Tables, lists and trees print `limit` rows (default 20) from `offset` and end with \
            "rows X–Y of N"; ask for the next page with offset=Y. `window` is a title substring and \
            describes every open window it matches, each headed by its index (a popup can share its dialog's title). \
            With no `window`, describes every open window except the main one (and any context menu), and for the main window gives only the \
            title, the viewer's image server path, downsample, centre, z/t and annotation count.""";

    private String describe(String window, int offset, int limit) throws Exception {
        var out = driver.outline(window, offset, limit);
        if (window != null && out.isEmpty())
            throw new Exception("No open window whose title contains '%s'; see qupath_windows.".formatted(window));
        return out;
    }

    void build(McpStreamableServerTransportProvider transport) {
        McpServer.sync(transport).serverInfo("qupath", Mcp.class.getPackage().getImplementationVersion())
                .instructions(INSTRUCTIONS).tools(tools).build();
    }

    private static SyncToolSpecification tool(String name, String description, String properties, String required, Handler handler) {
        var schema = """
                {"type":"object","properties":%s,"required":[%s]}""".formatted(properties,
                required == null ? "" : Stream.of(required.split(",")).map(r -> "\"" + r + "\"").collect(Collectors.joining(",")));
        return SyncToolSpecification.builder()
                .tool(McpSchema.Tool.builder().name(name).description(description)
                        .inputSchema(McpJsonDefaults.getMapper(), schema).build())
                .callHandler((exchange, request) -> {
                    try {
                        return CallToolResult.builder().content(handler.call(request.arguments())).isError(false).build();
                    } catch (Throwable t) {
                        var cause = t instanceof ExecutionException && t.getCause() != null ? t.getCause() : t;
                        return CallToolResult.builder().addTextContent(
                                cause.getMessage() != null ? cause.getMessage() : cause.toString()).isError(true).build();
                    }
                }).build();
    }

    private static List<Content> text(String s) {
        return List.of(new TextContent(s));
    }

    private static String str(Map<String, Object> a, String key) {
        return (String) a.get(key);
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
