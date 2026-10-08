package io.earthmover.qupath.driver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;
import qupath.lib.gui.QuPathGUI;

/** Helpers exposed to driver scripts. All methods are meant to be called from a non-FX thread. */
public class GuiDriver {

    private static final Logger logger = LoggerFactory.getLogger(GuiDriver.class);

    private final QuPathGUI qupath;
    private final File outDir;

    GuiDriver(QuPathGUI qupath, String outDir) {
        this.qupath = qupath;
        this.outDir = new File(outDir);
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

    /** PNG bytes of the first showing window whose title contains {@code titleSubstring}, or the main stage if null. */
    byte[] png(String titleSubstring) throws Exception {
        var image = fx(() -> {
            Window w = titleSubstring == null ? qupath.getStage() : Window.getWindows().stream()
                    .filter(x -> x.isShowing() && titleOf(x).contains(titleSubstring)).findFirst().orElse(null);
            return w == null ? null : w.getScene().snapshot(null);
        });
        if (image == null)
            return null;
        var bytes = new ByteArrayOutputStream();
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", bytes);
        return bytes.toByteArray();
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

    /** Finds a {@link Labeled} node in the window whose text equals {@code text}. */
    public Labeled lookup(Window window, String text) throws Exception {
        var found = fx(() -> find(window.getScene().getRoot(), text));
        logger.info("lookup '{}' -> {}", text, found);
        if (found == null)
            throw new IllegalStateException("No node with text '" + text + "'");
        return found;
    }

    /** Returns without waiting: a handler that opens a modal dialog would otherwise block until the dialog closes. */
    public void click(Node node) {
        logger.info("click {}", node);
        var button = (ButtonBase) node;
        Platform.runLater(button::fire);
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

    /** Returns without waiting, for the same reason as {@link #click}. */
    public void fire(MenuItem item) {
        logger.info("fire {}", item.getText());
        Platform.runLater(item::fire);
    }

    public void typeInto(Window window, String text, int field) throws Exception {
        fx(() -> {
            var fields = new ArrayList<TextInputControl>();
            findAll(window.getScene().getRoot(), TextInputControl.class, fields);
            if (field >= fields.size())
                throw new IllegalStateException("No text field #" + field + " in window (found " + fields.size() + ")");
            fields.get(field).setText(text);
            return null;
        });
        logger.info("typed '{}' into field {}", text, field);
    }

    /** The showing stage whose title contains {@code titleSubstring}; if null, the focused or topmost non-main stage, else the main one. */
    public Window window(String titleSubstring) throws Exception {
        Window found = fx(() -> {
            var stages = Window.getWindows().stream().filter(w -> w.isShowing() && w instanceof Stage).toList();
            if (titleSubstring != null)
                return stages.stream().filter(w -> titleOf(w).contains(titleSubstring)).findFirst().orElse(null);
            var others = stages.stream().filter(w -> w != qupath.getStage()).toList();
            return others.stream().filter(Window::isFocused).findFirst()
                    .orElse(others.isEmpty() ? qupath.getStage() : others.get(others.size() - 1));
        });
        if (found == null)
            throw new IllegalStateException("No window with title containing '" + titleSubstring + "'");
        return found;
    }

    /** Sets the zoom before the centre, because changing the downsample afterwards moves the centre. */
    public void view(double x, double y, double downsample, Integer z, Integer t) throws Exception {
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
    }

    /** Text outline of the window's controls, one per line, indented by nesting of the controls shown. */
    public String describe(Window window) throws Exception {
        return fx(() -> {
            var sb = new StringBuilder(titleOf(window)).append('\n');
            describe(window.getScene().getRoot(), 0, sb);
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

    private static String titleOf(Window w) {
        return w instanceof Stage s && s.getTitle() != null ? s.getTitle() : "untitled";
    }

    private static Labeled find(Node node, String text) {
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

    private static <T extends Node> void findAll(Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node))
            into.add(type.cast(node));
        if (node instanceof Parent p)
            for (Node child : p.getChildrenUnmodifiable())
                findAll(child, type, into);
    }

    private static final int MAX_ROWS = 20;

    private static void describe(Node node, int depth, StringBuilder sb) {
        if (!node.isVisible())
            return;
        String line = null;
        boolean recurse = false;
        if (node instanceof ButtonBase b) {
            line = "%s \"%s\"%s".formatted(b.getClass().getSimpleName(), b.getText(),
                    (b instanceof ToggleButton t && t.isSelected() || b instanceof CheckBox c && c.isSelected() ? " [selected]" : "")
                            + (b.isDisabled() ? " [disabled]" : ""));
        } else if (node instanceof Labeled l) {
            if (l.getText() != null && !l.getText().isBlank())
                line = "%s \"%s\"".formatted(l.getClass().getSimpleName(), l.getText());
            recurse = true;
        } else if (node instanceof TextInputControl t) {
            line = "%s prompt=\"%s\" text=\"%s\"%s".formatted(t.getClass().getSimpleName(),
                    Objects.toString(t.getPromptText(), ""), t.getText(), t.isDisabled() ? " [disabled]" : "");
        } else if (node instanceof ComboBox<?> c) {
            line = "ComboBox value=%s items=%s".formatted(c.getValue(), first(c.getItems()));
        } else if (node instanceof ChoiceBox<?> c) {
            line = "ChoiceBox value=%s items=%s".formatted(c.getValue(), first(c.getItems()));
        } else if (node instanceof TableView<?> t) {
            line = "TableView columns=%s rows=%d".formatted(
                    t.getColumns().stream().map(c -> c.getText()).toList(), t.getItems().size());
            sb.append("  ".repeat(depth)).append(line).append('\n');
            for (int i = 0; i < Math.min(MAX_ROWS, t.getItems().size()); i++) {
                int row = i;
                sb.append("  ".repeat(depth + 1))
                        .append(t.getColumns().stream().map(c -> String.valueOf(c.getCellData(row))).collect(Collectors.joining(" | ")))
                        .append('\n');
            }
            return;
        } else if (node instanceof ListView<?> v) {
            line = "ListView items=%d %s".formatted(v.getItems().size(), first(v.getItems()));
        } else if (node instanceof TreeView<?> v) {
            line = "TreeView";
            sb.append("  ".repeat(depth)).append(line).append('\n');
            for (int i = 0; i < Math.min(MAX_ROWS, v.getExpandedItemCount()); i++)
                sb.append("  ".repeat(depth + 1 + v.getTreeItemLevel(v.getTreeItem(i)))).append(v.getTreeItem(i).getValue()).append('\n');
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
                describe(child, depth, sb);
    }

    private static List<?> first(List<?> items) {
        return items.size() <= MAX_ROWS ? items : items.subList(0, MAX_ROWS);
    }
}
