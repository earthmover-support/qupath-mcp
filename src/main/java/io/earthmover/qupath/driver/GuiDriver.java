package io.earthmover.qupath.driver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Writer;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.beans.value.WritableValue;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import qupath.lib.gui.QuPathGUI;

/** Helpers exposed to driver scripts. All methods are meant to be called from a non-FX thread. */
public class GuiDriver {

    private static final Logger logger = LoggerFactory.getLogger(GuiDriver.class);

    private final QuPathGUI qupath;
    private final File outDir;
    final Indicator indicator;

    GuiDriver(QuPathGUI qupath, String outDir) {
        this.qupath = qupath;
        this.outDir = new File(outDir);
        this.indicator = new Indicator(qupath);
    }

    /** Evaluates Groovy source (a File or String) with the helpers bound; {@code println} goes to {@code out} if given. */
    Object evaluate(Object source, Writer out) throws Exception {
        var binding = new Binding();
        binding.setVariable("qupath", qupath);
        bind(binding);
        if (out != null)
            binding.setVariable("out", out);
        var shell = new GroovyShell(getClass().getClassLoader(), binding);
        return source instanceof File f ? shell.evaluate(f) : shell.evaluate((String) source);
    }

    /** An encoded screenshot and its pixel size. */
    record Shot(byte[] bytes, String mime, int width, int height) {}

    /** Full-resolution PNG bytes, for the plain HTTP endpoint. */
    byte[] png(String titleSubstring, int index) throws Exception {
        var shot = screenshot(titleSubstring, index, Integer.MAX_VALUE, "png", 1, false, null);
        return shot == null ? null : shot.bytes();
    }

    /**
     * Screenshot of the {@code index}-th showing stage whose title contains {@code titleSubstring}, or the main stage if
     * null. {@code viewer} crops to the active viewer of the main window; {@code region} is {@code [x, y, w, h]} in window
     * coordinates. The longest side is capped at {@code maxSize}; images are never enlarged.
     */
    Shot screenshot(String titleSubstring, int index, int maxSize, String format, double quality, boolean viewer,
            double[] region) throws Exception {
        record Capture(javafx.scene.image.Image image, double[] crop, double sceneWidth) {}
        var capture = fx(() -> {
            var matches = viewer || titleSubstring == null ? List.<Window>of(qupath.getStage()) : stages(titleSubstring);
            if (index >= matches.size())
                return null;
            var scene = matches.get(index).getScene();
            double[] crop = region;
            if (viewer) {
                var b = qupath.getViewer().getView().localToScene(qupath.getViewer().getView().getBoundsInLocal());
                crop = new double[] {b.getMinX(), b.getMinY(), b.getWidth(), b.getHeight()};
            }
            return new Capture(Indicator.without(scene, () -> scene.snapshot(null)), crop, scene.getWidth());
        });
        if (capture == null)
            return null;
        // The snapshot is in device pixels, which is a multiple of the scene's size on a HiDPI display.
        var image = SwingFXUtils.fromFXImage(capture.image(), null);
        double scale = image.getWidth() / capture.sceneWidth();
        if (capture.crop() != null) {
            int x = (int) Math.max(0, capture.crop()[0] * scale), y = (int) Math.max(0, capture.crop()[1] * scale);
            int w = (int) Math.min(image.getWidth() - x, capture.crop()[2] * scale);
            int h = (int) Math.min(image.getHeight() - y, capture.crop()[3] * scale);
            if (w <= 0 || h <= 0)
                throw new IllegalArgumentException("Crop region lies outside the window");
            image = image.getSubimage(x, y, w, h);
        }
        double shrink = Math.min(1, (double) maxSize / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * shrink)), h = Math.max(1, (int) Math.round(image.getHeight() * shrink));
        boolean jpeg = "jpeg".equals(format);
        var out = new BufferedImage(w, h, jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        var g = out.createGraphics();
        g.drawImage(shrink < 1 ? image.getScaledInstance(w, h, Image.SCALE_SMOOTH) : image, 0, 0, null);
        g.dispose();
        var bytes = new ByteArrayOutputStream();
        if (jpeg) {
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) quality);
            try (var ios = ImageIO.createImageOutputStream(bytes)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(out, null, null), param);
            } finally {
                writer.dispose();
            }
        } else {
            ImageIO.write(out, "png", bytes);
        }
        return new Shot(bytes.toByteArray(), jpeg ? "image/jpeg" : "image/png", w, h);
    }

    /** Showing stages whose title contains {@code titleSubstring}. Call on the FX thread. */
    private List<Window> stages(String titleSubstring) {
        return Window.getWindows().stream()
                .filter(w -> w.isShowing() && w instanceof Stage && titleOf(w).contains(titleSubstring)).toList();
    }

    private static final String NATIVE_DIALOG = "QuPath's UI thread is not responding, so a native dialog (usually a file "
            + "chooser, which is invisible to qupath_describe) is probably open. Close it in QuPath; to avoid it use "
            + "qupath_open(path_or_uri) or qupath_run_groovy.";

    /** Whether the FX thread picks up work within two seconds; a native file chooser blocks it. */
    private boolean responsive() throws Exception {
        var done = new CompletableFuture<Boolean>();
        Platform.runLater(() -> done.complete(true));
        try {
            done.get(2, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        }
    }

    /** Page of table, list and tree rows to print: {@code limit} rows from {@code offset}; {@code controls} collects what is indexed. */
    private record Page(int offset, int limit, List<Node> controls) {}

    /**
     * Outlines of every showing stage matching {@code titleSubstring}, each headed by its index; if null, of every stage
     * but the main one, followed by a summary of the main window and viewer. Rows of tables, lists and trees are paged.
     */
    String outline(String titleSubstring, int offset, int limit) throws Exception {
        if (!responsive())
            throw new IllegalStateException(NATIVE_DIALOG);
        return fx(() -> {
            var sb = new StringBuilder();
            if (titleSubstring != null) {
                var matches = stages(titleSubstring);
                for (int i = 0; i < matches.size(); i++)
                    sb.append("[%d] ".formatted(i)).append(describe(matches.get(i), offset, limit));
                return sb.toString().strip();
            }
            for (Window w : Window.getWindows())
                if (w.isShowing() && (w instanceof Stage && w != qupath.getStage() || w instanceof ContextMenu))
                    sb.append(describe(w, offset, limit));
            var viewer = qupath.getViewer();
            var data = viewer.getImageData();
            return sb.append("%s\nimage: %s\ndownsample: %s centre: %s, %s z: %d t: %d\nannotations: %s".formatted(
                    qupath.getStage().getTitle(), data == null ? null : data.getServer().getPath(),
                    viewer.getDownsampleFactor(), viewer.getCenterPixelX(), viewer.getCenterPixelY(),
                    viewer.getZPosition(), viewer.getTPosition(),
                    data == null ? null : data.getHierarchy().getAnnotationObjects().size())).toString().strip();
        });
    }

    /**
     * Runs a non-blocking GUI action, gives any dialog a second to appear, and returns the action's message (if it
     * returned one), the open windows, and the outline of each window the action opened.
     */
    String act(Callable<?> action) throws Exception {
        var before = windowsNow();
        var message = action.call();
        Thread.sleep(1000);
        if (!responsive())
            return NATIVE_DIALOG;
        var opened = openedSince(before);
        return (message == null ? "" : message + "\n\n") + windowsText()
                + "\n\n" + (opened.isEmpty() ? "No new window opened." : "Opened by the action:\n" + opened);
    }

    List<Window> windowsNow() throws Exception {
        return fx(() -> new ArrayList<>(Window.getWindows()));
    }

    /** The outline of each window showing now that was not in {@code before}, or an empty string. */
    String openedSince(List<Window> before) throws Exception {
        return fx(() -> {
            var sb = new StringBuilder();
            for (Window w : Window.getWindows())
                if (w.isShowing() && !before.contains(w))
                    sb.append(describe(w));
            return sb.toString();
        });
    }

    /** One line per showing window: title, size and whether it has focus. */
    String windowsText() throws Exception {
        return fx(() -> Window.getWindows().stream().filter(Window::isShowing)
                .map(w -> "%s — %.0fx%.0f%s".formatted(titleOf(w), w.getWidth(), w.getHeight(), w.isFocused() ? " (focused)" : ""))
                .collect(Collectors.joining("\n")));
    }

    /** JSON array describing the showing windows. */
    String windowsJson() throws Exception {
        return fx(() -> Window.getWindows().stream().filter(Window::isShowing)
                .map(w -> "{\"title\":%s,\"x\":%.0f,\"y\":%.0f,\"w\":%.0f,\"h\":%.0f,\"focused\":%b}".formatted(
                        DriverServer.quote(titleOf(w)), w.getX(), w.getY(), w.getWidth(), w.getHeight(), w.isFocused()))
                .collect(Collectors.joining(",", "[", "]")));
    }

    /** Puts each helper into the script binding as a closure so scripts can call {@code snap("x")} directly. */
    void bind(Binding b) {
        b.setVariable("fx", new Closure<Object>(this) {
            public Object doCall(Closure<?> c) throws Exception { return fx(c::call); }
        });
        b.setVariable("snap", new Closure<Object>(this) {
            public Object doCall(String name) throws Exception { return snap(name); }
        });
        b.setVariable("snapMain", new Closure<Object>(this) {
            public Object doCall(String name) throws Exception { return snapMain(name); }
        });
        b.setVariable("waitFor", new Closure<Object>(this) {
            public Object doCall(String title, Number seconds) throws Exception { return waitFor(title, seconds.doubleValue()); }
        });
        b.setVariable("lookup", new Closure<Object>(this) {
            public Object doCall(Window w, String text) throws Exception { return lookup(w, text); }
        });
        b.setVariable("click", new Closure<Object>(this) {
            public Object doCall(Node n) throws Exception { click(n); return null; }
        });
        b.setVariable("findMenuItem", new Closure<Object>(this) {
            public Object doCall(String path) throws Exception { return findMenuItem(path); }
        });
        b.setVariable("fire", new Closure<Object>(this) {
            public Object doCall(MenuItem m) throws Exception { fire(m); return null; }
        });
        b.setVariable("typeInto", new Closure<Object>(this) {
            public Object doCall(Window w, String text) throws Exception { typeInto(w, text, 0); return null; }
            public Object doCall(Window w, String text, Number field) throws Exception { typeInto(w, text, field.intValue()); return null; }
        });
        b.setVariable("window", new Closure<Object>(this) {
            public Object doCall(String title) throws Exception { return window(title); }
        });
        b.setVariable("describe", new Closure<Object>(this) {
            public Object doCall(Window w) throws Exception { return describe(w); }
        });
        b.setVariable("view", new Closure<Object>(this) {
            public Object doCall(Number x, Number y, Number downsample) throws Exception { view(x.doubleValue(), y.doubleValue(), downsample.doubleValue(), null, null); return null; }
            public Object doCall(Number x, Number y, Number downsample, Number z, Number t) throws Exception {
                view(x.doubleValue(), y.doubleValue(), downsample.doubleValue(), z == null ? null : z.intValue(), t == null ? null : t.intValue());
                return null;
            }
        });
        b.setVariable("quit", new Closure<Object>(this) {
            public Object doCall() { quit(); return null; }
        });
    }

    public <T> T fx(Callable<T> task) throws Exception {
        if (Platform.isFxApplicationThread())
            return task.call();
        var future = new CompletableFuture<T>();
        Platform.runLater(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get(60, TimeUnit.SECONDS);
    }

    /** Writes a PNG of every showing window as {@code <name>-<i>-<title>.png} and returns the files. */
    public List<File> snap(String name) throws Exception {
        outDir.mkdirs();
        var files = new ArrayList<File>();
        var windows = fx(() -> new ArrayList<>(Window.getWindows()));
        int i = 0;
        for (Window w : windows) {
            if (!w.isShowing() || w.getScene() == null)
                continue;
            var image = fx(() -> w.getScene().snapshot(null));
            var file = new File(outDir, "%s-%d-%s.png".formatted(name, i++, titleOf(w).replaceAll("[^A-Za-z0-9._-]+", "_")));
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
            files.add(file);
        }
        logger.info("Snapped {} windows for '{}': {}", files.size(), name, files);
        return files;
    }

    public File snapMain(String name) throws Exception {
        outDir.mkdirs();
        var image = fx(() -> qupath.getStage().getScene().snapshot(null));
        var file = new File(outDir, name + "-main.png");
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        logger.info("Snapped main window to {}", file);
        return file;
    }

    public Window waitFor(String titleSubstring, double seconds) throws Exception {
        logger.info("Waiting up to {}s for window '{}'", seconds, titleSubstring);
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (System.nanoTime() < deadline) {
            var found = fx(() -> Window.getWindows().stream()
                    .filter(w -> w.isShowing() && titleOf(w).contains(titleSubstring))
                    .findFirst().orElse(null));
            if (found != null)
                return found;
            Thread.sleep(200);
        }
        throw new TimeoutException("No window with title containing '" + titleSubstring + "'");
    }

    /**
     * Finds the node to click: a {@code #n} from describe, else the first labeled node whose text equals {@code text}, else
     * the one whose tooltip (whole, then first line) or accessible text equals it. Several tooltip or accessible-text
     * matches are an error that lists them, since icon-only buttons repeat one tooltip per row.
     */
    public Node lookup(Window window, String text) throws Exception {
        var found = fx(() -> {
            if (text.strip().matches("#\\d+")) {
                var n = controls(window, text);
                if (n.isEmpty())
                    throw new IllegalStateException("No control " + text + "; qupath_describe shows each as #n");
                return n.get(0);
            }
            var labeled = new ArrayList<Labeled>();
            findAll(window.getScene().getRoot(), Labeled.class, labeled);
            var tiers = List.<java.util.function.Predicate<Labeled>>of(l -> text.equals(l.getText()),
                    l -> text.equals(tooltipText(l)), l -> text.equals(firstLine(tooltipText(l))),
                    l -> text.equals(l.getAccessibleText()));
            var all = new ArrayList<Node>();
            describe(window.getScene().getRoot(), 0, new StringBuilder(), new Page(0, 0, all));
            for (int i = 0; i < tiers.size(); i++) {
                var tier = tiers.get(i);
                boolean first = i == 0;
                var matches = labeled.stream().filter(tier).filter(m -> first || all.contains(m)).toList();
                if (matches.size() == 1 || i == 0 && !matches.isEmpty())
                    return matches.get(0);
                if (matches.size() > 1) {
                    throw new IllegalStateException("%d controls match '%s'; click one by its #n from qupath_describe:\n%s".formatted(
                            matches.size(), text, matches.stream().map(m -> "#%d [near \"%s\"]".formatted(all.indexOf(m), nearestLabel(m)))
                                    .collect(Collectors.joining("\n"))));
                }
            }
            return null;
        });
        logger.info("lookup '{}' -> {}", text, found);
        if (found == null) {
            var buttons = fx(() -> {
                var all = new ArrayList<ButtonBase>();
                findAll(window.getScene().getRoot(), ButtonBase.class, all);
                return all.stream().map(GuiDriver::name).filter(t -> !t.isBlank()).distinct().limit(MAX_ROWS).toList();
            });
            throw new IllegalStateException("No button '%s' in '%s'. Buttons: %s".formatted(text, titleOf(window),
                    String.join(", ", buttons)));
        }
        return found;
    }

    private static String tooltipText(Node node) {
        var tip = node instanceof Labeled l && l.getTooltip() != null ? l.getTooltip()
                : node.getProperties().get("javafx.scene.control.Tooltip") instanceof Tooltip t ? t : null;
        return tip == null || tip.getText() == null ? "" : tip.getText();
    }

    private static String firstLine(String s) {
        return s.strip().lines().findFirst().orElse("");
    }

    /** What a control is called: its text, else its tooltip's first line in brackets, else its accessible text or graphic's id or style class. */
    private static String name(Labeled l) {
        if (l.getText() != null && !l.getText().isBlank())
            return l.getText();
        var tip = firstLine(tooltipText(l));
        if (!tip.isEmpty())
            return "[" + tip + "]";
        if (l.getAccessibleText() != null && !l.getAccessibleText().isBlank())
            return "[" + l.getAccessibleText() + "]";
        var g = l.getGraphic();
        if (g != null && g.getId() != null && !g.getId().isBlank())
            return "[" + g.getId() + "]";
        if (g != null && !g.getStyleClass().isEmpty())
            return "[" + g.getStyleClass().get(g.getStyleClass().size() - 1) + "]";
        return "(icon)";
    }

    /** The text of a table cell value; a node, such as a button, shows what it is called rather than its toString. */
    private static String cellText(Object value) {
        return value instanceof Labeled l ? name(l) : value instanceof Node ? "(icon)" : String.valueOf(value);
    }

    /** The nearest text before the node in document order: a plain label among the earlier siblings of it or of an ancestor. */
    private static String nearestLabel(Node node) {
        for (Node child = node, parent = node.getParent(); parent != null; child = parent, parent = parent.getParent()) {
            var siblings = ((Parent) parent).getChildrenUnmodifiable();
            for (int i = siblings.indexOf(child) - 1; i >= 0; i--) {
                var text = firstText(siblings.get(i));
                if (text != null)
                    return text;
            }
        }
        return "";
    }

    private static String firstText(Node node) {
        if (node instanceof Labeled l && !(l instanceof ButtonBase) && l.getText() != null && !l.getText().isBlank())
            return l.getText();
        if (node instanceof Parent p && !(node instanceof ButtonBase))
            for (Node child : p.getChildrenUnmodifiable()) {
                var t = firstText(child);
                if (t != null)
                    return t;
            }
        return null;
    }

    /** Returns without waiting: a handler that opens a modal dialog would otherwise block until the dialog closes. */
    public void click(Node node) {
        click(node, false, false);
    }

    /**
     * Buttons are fired; any other node, or a right or double click, gets synthetic mouse events (a context menu
     * request for the right button), because a cell or label has no action to fire.
     */
    public void click(Node node, boolean right, boolean doubleClick) {
        logger.info("click {} right={} double={}", node, right, doubleClick);
        var action = (right ? "right-click" : doubleClick ? "double-click" : "click")
                + (node instanceof Labeled l ? " “" + name(l) + "”" : "");
        indicator.before(node, action);
        Platform.runLater(() -> {
            if (node instanceof ButtonBase b && !right && !doubleClick) {
                b.fire();
                return;
            }
            var bounds = node.getBoundsInLocal();
            var scene = node.localToScene(bounds.getCenterX(), bounds.getCenterY());
            var screen = node.localToScreen(bounds.getCenterX(), bounds.getCenterY());
            double sx = screen == null ? scene.getX() : screen.getX(), sy = screen == null ? scene.getY() : screen.getY();
            if (right) {
                Event.fireEvent(node, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, scene.getX(),
                        scene.getY(), sx, sy, false, null));
                return;
            }
            for (int count = 1; count <= (doubleClick ? 2 : 1); count++)
                for (var type : List.of(MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED))
                    Event.fireEvent(node, new MouseEvent(type, scene.getX(), scene.getY(), sx, sy, MouseButton.PRIMARY,
                            count, false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false, true,
                            false, true, null));
        });
        indicator.mark(node, action);
    }

    /** Finds a menu item by a path such as {@code Help>About}. */
    public MenuItem findMenuItem(String path) throws Exception {
        var found = fx(() -> {
            var items = qupath.getMenuBar().getMenus().stream().map(m -> (MenuItem) m).toList();
            MenuItem current = null;
            for (String part : path.split(">")) {
                current = items.stream().filter(m -> part.trim().equals(m.getText())).findFirst().orElse(null);
                if (current == null)
                    return null;
                items = current instanceof Menu menu ? List.copyOf(menu.getItems()) : List.of();
            }
            return current;
        });
        logger.info("findMenuItem '{}' -> {}", path, found);
        if (found == null)
            throw new IllegalStateException("No menu item " + path);
        return found;
    }

    /**
     * Returns without waiting, for the same reason as {@link #click}. Firing alone does not change a check or radio
     * item's state; the menu does that first when it is clicked, so this does too.
     */
    public void fire(MenuItem item) {
        logger.info("fire {}", item.getText());
        var path = new ArrayList<String>();
        for (MenuItem m = item; m != null; m = m.getParentMenu())
            path.add(0, m.getText());
        var action = String.join(" ▸ ", path);
        indicator.before(action);
        indicator.note(action);
        Platform.runLater(() -> {
            if (item instanceof CheckMenuItem c)
                c.setSelected(!c.isSelected());
            else if (item instanceof RadioMenuItem r)
                r.setSelected(true);
            item.fire();
        });
    }

    /** Sets the text of the {@code field}-th text field and returns the text it then holds. */
    public String typeInto(Window window, String text, int field) throws Exception {
        var target = fx(() -> {
            var fields = new ArrayList<TextInputControl>();
            findAll(window.getScene().getRoot(), TextInputControl.class, fields);
            if (field >= fields.size())
                throw new IllegalStateException("No text field #" + field + " in window (found " + fields.size() + ")");
            return fields.get(field);
        });
        var action = "type “" + text + "”";
        indicator.before(target, action);
        var result = fx(() -> {
            target.setText(text);
            return target.getText();
        });
        indicator.mark(target, action);
        logger.info("typed '{}' into field {}", text, field);
        return result;
    }

    /** The showing stage whose title contains {@code titleSubstring}; if null, an open context menu, else the focused or topmost non-main stage, else the main one. */
    public Window window(String titleSubstring) throws Exception {
        Window found = fx(() -> {
            var stages = Window.getWindows().stream().filter(w -> w.isShowing() && w instanceof Stage).toList();
            if (titleSubstring != null)
                return stages.stream().filter(w -> titleOf(w).contains(titleSubstring)).findFirst().orElse(null);
            var menus = Window.getWindows().stream().filter(w -> w.isShowing() && w instanceof ContextMenu).toList();
            if (!menus.isEmpty())
                return menus.get(menus.size() - 1);
            var others = stages.stream().filter(w -> w != qupath.getStage()).toList();
            return others.stream().filter(Window::isFocused).findFirst()
                    .orElse(others.isEmpty() ? qupath.getStage() : others.get(others.size() - 1));
        });
        if (found == null)
            throw new IllegalStateException("No window with title containing '" + titleSubstring + "'");
        return found;
    }

    /**
     * Closes a window as its close button would: a close request first, which a window that prompts (for example about
     * unsaved changes) can consume, and then hides it if nothing did. Returns without waiting, like {@link #click}.
     */
    public String close(Window window) throws Exception {
        if (window == qupath.getStage())
            throw new IllegalArgumentException("Won't close QuPath's main window; use qupath_quit");
        var title = titleOf(window);
        logger.info("close {}", title);
        indicator.before("close “" + title + "”");
        indicator.note("close “" + title + "”");
        Platform.runLater(() -> {
            var request = new WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST);
            window.fireEvent(request);
            if (!request.isConsumed())
                window.hide();
        });
        return "closed " + title;
    }

    /** Sets the zoom before the centre, because changing the downsample afterwards moves the centre. */
    public void view(double x, double y, double downsample, Integer z, Integer t) throws Exception {
        var action = "view %,.0f, %,.0f at %s×".formatted(x, y, downsample);
        indicator.before(action);
        fx(() -> {
            var viewer = qupath.getViewer();
            viewer.setDownsampleFactor(downsample);
            viewer.setCenterPixelLocation(x, y);
            if (z != null)
                viewer.setZPosition(z);
            if (t != null)
                viewer.setTPosition(t);
            return null;
        });
        indicator.viewer(action);
    }

    /** Text outline of the window's controls, one per line, indented by nesting of the controls shown. */
    public String describe(Window window) throws Exception {
        return describe(window, 0, MAX_ROWS);
    }

    public String describe(Window window, int offset, int limit) throws Exception {
        return fx(() -> {
            var sb = new StringBuilder(titleOf(window)).append('\n');
            describe(window.getScene().getRoot(), 0, sb, new Page(offset, limit, new ArrayList<>()));
            return sb.toString();
        });
    }

    public void quit() {
        logger.info("Quitting");
        Platform.exit();
        System.exit(0);
    }

    void waitForShowing(Stage stage, double seconds) throws Exception {
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (!fx(stage::isShowing)) {
            if (System.nanoTime() > deadline)
                throw new TimeoutException("Main stage never showed");
            Thread.sleep(200);
        }
    }

    static String titleOf(Window w) {
        return w instanceof Stage s && s.getTitle() != null ? s.getTitle() : w instanceof ContextMenu ? "context menu" : "untitled";
    }

    private static Labeled find(Node node, String text) {
        if (!node.isVisible() || Indicator.isOverlay(node))
            return null;
        if (node instanceof Labeled l && text.equals(l.getText()))
            return l;
        if (node instanceof Parent p)
            for (Node child : p.getChildrenUnmodifiable()) {
                var found = find(child, text);
                if (found != null)
                    return found;
            }
        return null;
    }

    /** Visible nodes only, as {@code describe} lists them, so a hidden control can't be matched or shift field numbers. */
    private static <T extends Node> void findAll(Node node, Class<T> type, List<T> into) {
        if (!node.isVisible() || Indicator.isOverlay(node))
            return;
        if (type.isInstance(node))
            into.add(type.cast(node));
        if (node instanceof Parent p)
            for (Node child : p.getChildrenUnmodifiable())
                findAll(child, type, into);
    }

    private static final int MAX_ROWS = 20;

    /** Marks a control that qupath_select, qupath_set and their {@code control} argument can address. */
    private static String tag(Node node, Page page) {
        page.controls().add(node);
        return "#" + (page.controls().size() - 1) + " ";
    }

    /** The rows {@code offset} to {@code offset + limit} of {@code total}, then a line saying which rows those were. */
    private static void rows(StringBuilder sb, int depth, Page page, int total, IntFunction<String> row) {
        int from = Math.min(page.offset(), total), to = Math.min(total, from + page.limit());
        for (int i = from; i < to; i++)
            sb.append("  ".repeat(depth + 1)).append(row.apply(i)).append('\n');
        sb.append("  ".repeat(depth + 1)).append(total == 0 ? "rows 0 of 0" : "rows %d–%d of %d".formatted(from + 1, to, total)).append('\n');
    }

    private static void describe(Node node, int depth, StringBuilder sb, Page page) {
        if (!node.isVisible() || Indicator.isOverlay(node))
            return;
        String line = null;
        boolean recurse = false;
        if (node instanceof ButtonBase b) {
            var shown = b.getText() != null && !b.getText().isBlank() ? "\"" + b.getText() + "\"" : name(b);
            line = "%s%s %s%s".formatted(tag(b, page), b.getClass().getSimpleName(), shown,
                    (b instanceof ToggleButton t && t.isSelected() || b instanceof CheckBox c && c.isSelected() ? " [selected]" : "")
                            + (b.isDisabled() ? " [disabled]" : ""));
        } else if (node instanceof Labeled l) {
            if (l.getText() != null && !l.getText().isBlank() && !"Glyph".equals(l.getClass().getSimpleName()))
                line = "%s \"%s\"".formatted(l.getClass().getSimpleName(), l.getText());
            recurse = true;
        } else if (node instanceof TextInputControl t) {
            line = "%s%s prompt=\"%s\" text=\"%s\"%s".formatted(tag(t, page), t.getClass().getSimpleName(),
                    Objects.toString(t.getPromptText(), ""), t.getText(), t.isDisabled() ? " [disabled]" : "");
        } else if (node instanceof Spinner<?> sp) {
            line = "%sSpinner value=%s%s".formatted(tag(sp, page), sp.getValue(), sp.isDisabled() ? " [disabled]" : "");
        } else if (node instanceof Slider sl) {
            line = "%sSlider value=%s range=%s..%s".formatted(tag(sl, page), sl.getValue(), sl.getMin(), sl.getMax());
        } else if (node instanceof ComboBox<?> c) {
            line = "%sComboBox value=%s items=%s".formatted(tag(c, page), c.getValue(), first(c.getItems()));
        } else if (node instanceof ChoiceBox<?> c) {
            line = "%sChoiceBox value=%s items=%s".formatted(tag(c, page), c.getValue(), first(c.getItems()));
        } else if (node instanceof TabPane tp) {
            sb.append("  ".repeat(depth)).append("%sTabPane tabs=%s selected=%s".formatted(tag(tp, page),
                    tp.getTabs().stream().map(Tab::getText).toList(),
                    tp.getSelectionModel().getSelectedItem() == null ? null : tp.getSelectionModel().getSelectedItem().getText())).append('\n');
            var selected = tp.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getContent() != null)
                describe(selected.getContent(), depth + 1, sb, page);
            return;
        } else if (node instanceof TableView<?> t) {
            sb.append("  ".repeat(depth)).append("%sTableView columns=%s".formatted(tag(t, page),
                    t.getColumns().stream().map(c -> c.getText()).toList())).append('\n');
            rows(sb, depth, page, t.getItems().size(), row ->
                    t.getColumns().stream().map(c -> cellText(c.getCellData(row))).collect(Collectors.joining(" | ")));
            return;
        } else if (node instanceof ListView<?> v) {
            sb.append("  ".repeat(depth)).append(tag(v, page)).append("ListView\n");
            rows(sb, depth, page, v.getItems().size(), row -> String.valueOf(v.getItems().get(row)));
            return;
        } else if (node instanceof TreeView<?> v) {
            sb.append("  ".repeat(depth)).append(tag(v, page)).append("TreeView\n");
            rows(sb, depth, page, v.getExpandedItemCount(),
                    row -> "  ".repeat(v.getTreeItemLevel(v.getTreeItem(row))) + v.getTreeItem(row).getValue());
            return;
        } else {
            recurse = true;
        }
        if (line != null) {
            sb.append("  ".repeat(depth)).append(line).append('\n');
            depth++;
        }
        if (recurse && node instanceof Parent p)
            for (Node child : p.getChildrenUnmodifiable())
                describe(child, depth, sb, page);
    }

    private static Object first(List<?> items) {
        return items.size() <= MAX_ROWS ? items : items.subList(0, MAX_ROWS) + " (first %d of %d)".formatted(MAX_ROWS, items.size());
    }

    /** A GUI change worked out on the FX thread and applied later, so a handler that opens a modal dialog cannot block the caller. */
    /** {@code action} is the short form shown to the person watching; {@code message} is the reply to the agent. */
    private record Change(Runnable apply, String message, String action, Node target) {
        Change at(Node node) {
            return new Change(apply, message, action, target == null ? node : target);
        }
    }

    /** Dispatches a change worked out by {@link #select} or {@link #set}, marking its target for the person watching. */
    private String apply(Change change) {
        logger.info("{}", change.message());
        indicator.before(change.target(), change.action());
        Platform.runLater(change.apply());
        indicator.mark(change.target(), change.action());
        return change.message();
    }

    /** The controls of the window that carry a {@code #n} tag in {@code describe}, narrowed by a {@code #n} index or label text. */
    private List<Node> controls(Window window, String control) {
        var page = new Page(0, 0, new ArrayList<>());
        describe(window.getScene().getRoot(), 0, new StringBuilder(), page);
        var all = page.controls();
        if (control == null)
            return all;
        var index = control.strip().replaceFirst("^#", "");
        if (index.matches("\\d+"))
            return Integer.parseInt(index) < all.size() ? List.of(all.get(Integer.parseInt(index))) : List.of();
        return all.stream().filter(n -> (!(n instanceof ButtonBase) || n instanceof CheckBox) && label(n).contains(control)).toList();
    }

    /** The text of the label next to a control: its {@code labelFor} label, else the label just before it in its parent. */
    private static String label(Node node) {
        if (node instanceof Labeled l && l.getText() != null)
            return l.getText();
        if (node.getParent() == null)
            return "";
        Labeled previous = null;
        for (Node sibling : node.getParent().getChildrenUnmodifiable()) {
            if (sibling == node)
                break;
            if (sibling instanceof Labeled l && !(l instanceof ButtonBase))
                previous = l;
        }
        return previous == null || previous.getText() == null ? "" : previous.getText();
    }

    /** The first item whose name equals {@code text}, else the first that contains it. */
    private static <T> T pick(List<T> items, String text, Function<T, String> name) {
        return items.stream().filter(i -> text.equals(name.apply(i))).findFirst()
                .orElse(items.stream().filter(i -> name.apply(i).contains(text)).findFirst().orElse(null));
    }

    private static void collect(TreeItem<?> item, List<TreeItem<?>> into) {
        into.add(item);
        for (var child : item.getChildren())
            collect(child, into);
    }

    private static <T> Change choose(String text, String shown, List<T> items, Function<T, String> name,
            java.util.function.Consumer<T> select) {
        var item = pick(items, text, name);
        return item == null ? null : new Change(() -> select.accept(item), "selected \"%s\" in %s".formatted(name.apply(item), shown),
                "select “%s”".formatted(name.apply(item)), null);
    }

    private static <T> Change selectIn(ComboBox<T> c, String text) {
        return choose(text, "ComboBox", c.getItems(),
                i -> c.getConverter() == null ? String.valueOf(i) : c.getConverter().toString(i), c::setValue);
    }

    private static <T> Change selectIn(ChoiceBox<T> c, String text) {
        return choose(text, "ChoiceBox", c.getItems(),
                i -> c.getConverter() == null ? String.valueOf(i) : c.getConverter().toString(i), c::setValue);
    }

    private static <T> Change selectIn(ListView<T> v, String text) {
        return choose(text, "ListView", v.getItems(), String::valueOf, i -> {
            v.getSelectionModel().select(i);
            v.scrollTo(i);
        });
    }

    private static <T> Change selectIn(TreeView<T> v, String text) {
        var all = new ArrayList<TreeItem<?>>();
        if (v.getRoot() != null)
            collect(v.getRoot(), all);
        return choose(text, "TreeView", all, i -> String.valueOf(i.getValue()), item -> {
            for (var parent = item.getParent(); parent != null; parent = parent.getParent())
                parent.setExpanded(true);
            @SuppressWarnings("unchecked") var typed = (TreeItem<T>) item;
            v.getSelectionModel().select(typed);
            v.scrollTo(v.getRow(typed));
        });
    }

    /** Targets the tab's header, so the mark lands on what a person would click rather than the whole pane. */
    private static Change selectIn(TabPane tp, String text) {
        var change = choose(text, "TabPane", tp.getTabs(), t -> Objects.toString(t.getText(), ""),
                t -> tp.getSelectionModel().select(t));
        if (change == null)
            return null;
        var header = tp.lookupAll(".tab").stream()
                .filter(n -> n.lookup(".tab-label") instanceof Labeled l && change.message().contains("\"" + l.getText() + "\""))
                .findFirst().orElse(null);
        return change.at(header);
    }

    /** Rows with a cell whose text equals {@code text} (else contains it); with {@code check}, ticks the row's checkbox cell. */
    private static <T> Change selectIn(TableView<T> t, String text, Boolean check) {
        IntFunction<List<String>> cells = row -> t.getColumns().stream().map(c -> String.valueOf(c.getCellData(row))).toList();
        var exact = new ArrayList<Integer>();
        var partial = new ArrayList<Integer>();
        for (int row = 0; row < t.getItems().size(); row++) {
            if (cells.apply(row).contains(text))
                exact.add(row);
            else if (cells.apply(row).stream().anyMatch(c -> c.contains(text)))
                partial.add(row);
        }
        var matched = exact.isEmpty() ? partial : exact;
        if (matched.isEmpty())
            return null;
        if (check == null)
            return new Change(() -> {
                t.getSelectionModel().select(matched.get(0));
                t.scrollTo(matched.get(0));
            }, "selected row %d of TableView".formatted(matched.get(0)), "select “%s”".formatted(text), null);
        var boxes = new ArrayList<WritableValue<Object>>();
        for (int row : matched)
            for (var column : t.getColumns())
                if (column.getCellObservableValue(row) instanceof WritableValue<?> w && w.getValue() instanceof Boolean) {
                    @SuppressWarnings("unchecked") var box = (WritableValue<Object>) w;
                    boxes.add(box);
                    break;
                }
        if (boxes.size() != matched.size())
            throw new IllegalStateException("The TableView has no checkbox column on %d of the %d matching rows".formatted(
                    matched.size() - boxes.size(), matched.size()));
        return new Change(() -> boxes.forEach(b -> b.setValue(check)),
                "%s %d rows of TableView".formatted(check ? "checked" : "unchecked", boxes.size()),
                "%s “%s”".formatted(check ? "tick" : "untick", text), null);
    }

    /**
     * Selects the item named {@code text} in a combo box, choice box, list, tree (expanding its parents), tab pane or
     * table (a row with a cell of that text) of the window; {@code control} narrows the controls tried. With {@code check}, a table's
     * matching rows have their checkbox ticked or cleared instead. Returns without waiting for handlers.
     */
    public String select(Window window, String text, String control, Boolean check) throws Exception {
        var change = fx(() -> {
            for (Node node : controls(window, control)) {
                Change c = node instanceof ComboBox<?> n ? selectIn(n, text) : node instanceof ChoiceBox<?> n ? selectIn(n, text)
                        : node instanceof ListView<?> n ? selectIn(n, text) : node instanceof TreeView<?> n ? selectIn(n, text)
                        : node instanceof TabPane n ? selectIn(n, text) : node instanceof TableView<?> n ? selectIn(n, text, check) : null;
                if (c != null)
                    return c.at(node);
            }
            throw new IllegalStateException("No item '%s' in %s; qupath_describe lists the controls and their rows".formatted(
                    text, control == null ? "any combo box, list, tree, tab pane or table" : "control " + control));
        });
        return apply(change);
    }

    /** Sets a spinner, slider, checkbox or text field picked by {@code control} (a {@code #n} from describe, or its label's text). */
    public String set(Window window, String value, String control) throws Exception {
        var change = fx(() -> {
            var matches = controls(window, control);
            if (matches.isEmpty())
                throw new IllegalStateException("No control '%s'; qupath_describe shows each as #n".formatted(control));
            var node = matches.get(0);
            Runnable apply;
            if (node instanceof Spinner<?> sp) {
                @SuppressWarnings("unchecked") var factory = (javafx.scene.control.SpinnerValueFactory<Object>) sp.getValueFactory();
                var parsed = factory.getConverter().fromString(value);
                apply = () -> factory.setValue(parsed);
            } else if (node instanceof Slider sl) {
                double v = Double.parseDouble(value);
                apply = () -> sl.setValue(v);
            } else if (node instanceof CheckBox cb) {
                boolean v = Boolean.parseBoolean(value);
                apply = () -> cb.setSelected(v);
            } else if (node instanceof TextInputControl t) {
                apply = () -> t.setText(value);
            } else {
                throw new IllegalStateException("%s cannot be set; use qupath_select for lists, tabs and combo boxes".formatted(
                        node.getClass().getSimpleName()));
            }
            var named = label(node).isBlank() ? "" : " “" + label(node) + "”";
            return new Change(apply, "set %s %s to %s".formatted(node.getClass().getSimpleName(), label(node), value),
                    "set%s to %s".formatted(named, value), node);
        });
        return apply(change);
    }

    /**
     * Fires key presses, space-separated such as {@code Ctrl+A} or {@code Tab Enter}, at the window's focused node (else its
     * scene), where the scene's accelerators and default and cancel buttons see them as they would a keyboard's. Returns
     * without waiting.
     */
    public String key(Window window, String keys) throws Exception {
        var events = new ArrayList<KeyEvent>();
        boolean mac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        for (String spec : keys.trim().split("\\s+")) {
            var combo = KeyCombination.valueOf(keyNames(spec));
            if (!(combo instanceof KeyCodeCombination k))
                throw new IllegalArgumentException("Not a key: " + spec);
            boolean shortcut = k.getShortcut() == KeyCombination.ModifierValue.DOWN;
            boolean ctrl = k.getControl() == KeyCombination.ModifierValue.DOWN || shortcut && !mac;
            boolean meta = k.getMeta() == KeyCombination.ModifierValue.DOWN || shortcut && mac;
            for (var type : List.of(KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED))
                events.add(new KeyEvent(type, KeyEvent.CHAR_UNDEFINED, k.getCode().getName(), k.getCode(),
                        k.getShift() == KeyCombination.ModifierValue.DOWN, ctrl, k.getAlt() == KeyCombination.ModifierValue.DOWN, meta));
        }
        var target = fx(() -> window.getScene().getFocusOwner() != null ? window.getScene().getFocusOwner() : window.getScene().getRoot());
        logger.info("keys '{}' -> {}", keys, target);
        indicator.before("key " + keys);
        Platform.runLater(() -> events.forEach(e -> Event.fireEvent(target, e)));
        indicator.keys(window, keys);
        return "sent %s to %s".formatted(keys, target.getClass().getSimpleName());
    }

    /**
     * Opens an image by path or URI in the active viewer. QuPath may prompt (for example about unsaved changes), so this waits
     * only a few seconds for the result.
     */
    public String open(String pathOrUri) throws Exception {
        logger.info("open {}", pathOrUri);
        indicator.note("open " + pathOrUri.replaceFirst(".*[/\\\\]", ""));
        var result = new CompletableFuture<Boolean>();
        // openImage must run on the FX thread; a prompt it shows nests an event loop there, so the wait below still times out.
        Platform.runLater(() -> {
            try {
                result.complete(qupath.openImage(qupath.getViewer(), pathOrUri, false, false));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS) ? "opened " + pathOrUri : "QuPath did not open " + pathOrUri;
        } catch (TimeoutException e) {
            return "still opening " + pathOrUri + "; a prompt may be waiting, see the windows below";
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("Could not open %s: %s".formatted(pathOrUri, e.getCause()), e.getCause());
        }
    }

    /** JavaFX names some keys differently from their enum constants ("Esc" for ESCAPE); accept either spelling. */
    private static String keyNames(String spec) {
        var parts = spec.split("\\+");
        try {
            parts[parts.length - 1] = KeyCode.valueOf(parts[parts.length - 1].toUpperCase()).getName();
        } catch (IllegalArgumentException notAnEnumName) {
            return spec;
        }
        return String.join("+", parts);
    }
}
