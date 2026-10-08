package io.earthmover.qupath.driver;

import java.util.function.Supplier;

import javafx.animation.PauseTransition;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ScaleTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.Window;
import javafx.util.Duration;
import qupath.lib.gui.QuPathGUI;
import qupath.lib.gui.prefs.PathPrefs;

/**
 * Shows the person watching QuPath what the agent does: a ring on each control it acts on, a log of recent actions
 * in the corner of the window, and a badge while a tool call runs. In {@link Mode#MARK} the marks are drawn after the
 * action is dispatched, so the agent never waits for them; {@link Mode#PACED} first glides a pointer to the target
 * and holds for {@link #delayMs}, for recordings and for people following along.
 * <p>
 * The marks live in a mouse-transparent pane added on top of each window's root, which {@link GuiDriver} leaves out
 * of descriptions, lookups and screenshots. Every method may be called from any thread and never throws.
 */
class Indicator {

    enum Mode { OFF, MARK, PACED }

    static final String OVERLAY_ID = "qupath-mcp-overlay";

    static final ObjectProperty<Mode> mode = PathPrefs.createPersistentPreference("mcpShowActions", Mode.MARK, Mode.class);
    static final IntegerProperty delayMs = PathPrefs.createPersistentPreference("mcpPacedDelayMs", 1000);

    private static final Logger logger = LoggerFactory.getLogger(Indicator.class);
    private static final Color ACCENT = Color.web("#D97757");
    private static final String PILL = "-fx-background-color: #D97757; -fx-text-fill: white; -fx-font-size: 12px; "
            + "-fx-font-weight: bold; -fx-padding: 3 8 3 8; -fx-background-radius: 10;";
    private static final String PANEL = "-fx-background-color: rgba(30,30,30,0.88); -fx-background-radius: 8; "
            + "-fx-border-color: #D97757; -fx-border-radius: 8;";
    private static final int LOG_LINES = 6;
    private static final Duration MARK_HOLD = Duration.millis(700), FADE = Duration.millis(400),
            LOG_HOLD = Duration.seconds(4), BADGE_HOLD = Duration.millis(1500), POINTER_HOLD = Duration.millis(1500),
            SETTLE = Duration.millis(100);

    private final QuPathGUI qupath;
    private int busy;
    private String client = "Agent";
    private Label badge;

    Indicator(QuPathGUI qupath) {
        this.qupath = qupath;
    }

    static boolean isOverlay(Node node) {
        return OVERLAY_ID.equals(node.getId());
    }

    private static boolean on() {
        return mode.get() != Mode.OFF;
    }

    /** In paced mode, glides the pointer to {@code target} and waits {@link #delayMs}; otherwise returns at once. */
    void before(Node target, String action) {
        if (mode.get() != Mode.PACED || target == null)
            return;
        pace(() -> glide(target));
    }

    /** Moves the pointer to {@code target} over most of the paced delay. Call on the FX thread. */
    private static void glide(Node target) {
        var o = overlay(target.getScene());
        if (o == null)
            return;
        var c = centre(o, target);
        var pointer = pointer(o);
        var t = new TranslateTransition(Duration.millis(Math.max(1, delayMs.get() * 0.8)), pointer);
        t.setToX(c.getX());
        t.setToY(c.getY());
        t.setInterpolator(Interpolator.EASE_BOTH);
        show(pointer);
        t.play();
        hideLater(pointer, Duration.millis(delayMs.get()).add(POINTER_HOLD));
    }

    static boolean paced() {
        return mode.get() == Mode.PACED;
    }

    /** In paced mode, shows {@code action} (a menu path, say) at the top of the main window and waits {@link #delayMs}. */
    void before(String action) {
        if (mode.get() != Mode.PACED)
            return;
        pace(() -> showAction(action));
    }

    /** In paced mode, shows {@code action} at the top of the main window for {@link #delayMs}, without waiting. */
    void announce(String action) {
        if (mode.get() == Mode.PACED)
            run(() -> showAction(action));
    }

    private void showAction(String action) {
        var o = overlay(qupath.getStage().getScene());
        if (o == null)
            return;
        var pill = pill(action);
        pill.relocate(12, 6);
        o.getChildren().add(pill);
        fadeOut(pill, Duration.millis(delayMs.get()));
    }

    /** How long a mark stays before fading: in paced mode at least the delay, so it lasts until the next action starts. */
    private static Duration hold() {
        return mode.get() == Mode.PACED ? Duration.millis(Math.max(MARK_HOLD.toMillis(), delayMs.get())) : MARK_HOLD;
    }

    private void pace(Runnable show) {
        run(show);
        if (!Platform.isFxApplicationThread())
            try {
                Thread.sleep(delayMs.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
    }

    /**
     * Rings the node {@code target} supplies once the action has had a moment to lay out, such as a list row scrolled
     * into view by the selection; {@code fallback} if it supplies none. Such a target doesn't exist before the action,
     * so paced mode moves the pointer to it afterwards, rings it when the pointer arrives, and waits {@link #delayMs}.
     */
    void markLater(Supplier<Node> target, Node fallback, String action) {
        if (!on())
            return;
        boolean paced = mode.get() == Mode.PACED;
        run(() -> {
            var settle = new PauseTransition(SETTLE);
            settle.setOnFinished(e -> {
                var supplied = target.get();
                var node = supplied != null ? supplied : fallback;
                if (!paced || node == null) {
                    ring(node, action);
                    return;
                }
                glide(node);
                var arrive = new PauseTransition(Duration.millis(delayMs.get() * 0.8));
                arrive.setOnFinished(f -> ring(node, action));
                arrive.play();
            });
            settle.play();
        });
        if (paced && !Platform.isFxApplicationThread())
            try {
                Thread.sleep((long) (SETTLE.toMillis() + delayMs.get()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
    }

    /** Rings {@code target} and logs {@code action} in its window. */
    void mark(Node target, String action) {
        if (!on() || target == null)
            return;
        run(() -> ring(target, action));
    }

    private void ring(Node target, String action) {
        if (target == null)
            return;
        var o = overlay(target.getScene());
        if (o == null)
            return;
        var b = o.sceneToLocal(target.localToScene(target.getBoundsInLocal()));
        var ring = new Rectangle(b.getMinX() - 3, b.getMinY() - 3, b.getWidth() + 6, b.getHeight() + 6);
        ring.setArcWidth(10);
        ring.setArcHeight(10);
        ring.setFill(ACCENT.deriveColor(0, 1, 1, 0.18));
        ring.setStroke(ACCENT);
        ring.setStrokeWidth(2.5);
        o.getChildren().add(ring);
        var pulse = new ScaleTransition(Duration.millis(220), ring);
        pulse.setFromX(1.15);
        pulse.setFromY(1.4);
        pulse.setToX(1);
        pulse.setToY(1);
        pulse.play();
        fadeOut(ring, hold());
        log(o, action);
    }

    /** Logs {@code action} in the main window, for actions with no control to ring. */
    void note(String action) {
        note(qupath.getStage(), action);
    }

    void note(Window window, String action) {
        if (!on())
            return;
        run(() -> {
            var o = overlay(window.getScene());
            if (o != null)
                log(o, action);
        });
    }

    /** Shows the keys as a keycap near the bottom of the window, and logs them. */
    void keys(Window window, String keys) {
        if (!on())
            return;
        run(() -> {
            var o = overlay(window.getScene());
            if (o == null)
                return;
            var cap = pill(keys);
            o.getChildren().add(cap);
            cap.applyCss();
            cap.autosize();
            cap.relocate((o.getWidth() - cap.getWidth()) / 2, o.getHeight() - cap.getHeight() - 48);
            fadeOut(cap, hold());
            log(o, "key " + keys);
        });
    }

    /** Marks the centre of the main viewer after it moves, and logs the move. */
    void viewer(String action) {
        if (!on())
            return;
        run(() -> {
            var view = qupath.getViewer().getView();
            var o = overlay(view.getScene());
            if (o == null)
                return;
            var c = centre(o, view);
            double x = c.getX(), y = c.getY();
            var circle = new Circle(x, y, 26, Color.TRANSPARENT);
            circle.setStroke(ACCENT);
            circle.setStrokeWidth(2.5);
            var h = new Line(x - 10, y, x + 10, y);
            var v = new Line(x, y - 10, x, y + 10);
            for (var l : new Line[] {h, v}) {
                l.setStroke(ACCENT);
                l.setStrokeWidth(2);
            }
            var cross = new Group(circle, h, v);
            o.getChildren().add(cross);
            var zoom = new ScaleTransition(Duration.millis(300), cross);
            zoom.setFromX(1.8);
            zoom.setFromY(1.8);
            zoom.setToX(1);
            zoom.setToY(1);
            zoom.play();
            fadeOut(cross, hold());
            log(o, action);
        });
    }

    /** Called around every tool call: the badge shows while any call runs and fades shortly after the last one ends. */
    void busy(boolean start, String clientName) {
        run(() -> {
            busy = Math.max(0, busy + (start ? 1 : -1));
            if (clientName != null && !clientName.isBlank())
                client = clientName;
            if (!on() || !start) {
                if (busy == 0 && badge != null)
                    hideLater(badge, BADGE_HOLD);
                return;
            }
            var o = overlay(qupath.getStage().getScene());
            if (o == null)
                return;
            if (badge == null || badge.getParent() != o) {
                badge = new Label();
                badge.setStyle(PANEL + "-fx-text-fill: #D97757; -fx-font-size: 12px; -fx-font-weight: bold; "
                        + "-fx-padding: 4 10 4 10; -fx-background-radius: 12; -fx-border-radius: 12;");
                o.getChildren().add(badge);
            }
            badge.setText("● " + client + " is driving");
            show(badge);
            badge.applyCss();
            badge.autosize();
            badge.relocate(o.getWidth() - badge.getWidth() - 12, o.getHeight() - badge.getHeight() - 12);
        });
    }

    /** Runs {@code snapshot} with the window's marks hidden, so screenshots show QuPath as it is. Call on the FX thread. */
    static <T> T without(Scene scene, Supplier<T> snapshot) {
        var marks = scene.getRoot().getChildrenUnmodifiable().stream().filter(Indicator::isOverlay).findFirst().orElse(null);
        if (marks == null)
            return snapshot.get();
        marks.setVisible(false);
        try {
            return snapshot.get();
        } finally {
            marks.setVisible(true);
        }
    }

    private static void run(Runnable r) {
        Runnable safe = () -> {
            try {
                r.run();
            } catch (Throwable t) {
                logger.debug("Indicator failed", t);
            }
        };
        if (Platform.isFxApplicationThread())
            safe.run();
        else
            Platform.runLater(safe);
    }

    /** The window's overlay, created on first use and kept on top and the size of the scene; null if the root has no children list. */
    private static Pane overlay(Scene scene) {
        if (scene == null || !(scene.getRoot() instanceof Pane root))
            return null;
        var o = (Pane) root.getChildren().stream().filter(Indicator::isOverlay).findFirst().orElse(null);
        if (o == null) {
            o = new Pane();
            o.setId(OVERLAY_ID);
            o.setManaged(false);
            o.setMouseTransparent(true);
            root.getChildren().add(o);
        } else if (root.getChildren().get(root.getChildren().size() - 1) != o) {
            o.toFront();
        }
        var origin = root.sceneToLocal(0, 0);
        o.resizeRelocate(origin.getX(), origin.getY(), scene.getWidth(), scene.getHeight());
        return o;
    }

    private static Point2D centre(Pane overlay, Node node) {
        Bounds b = overlay.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        return new Point2D(b.getCenterX(), b.getCenterY());
    }

    private static SVGPath pointer(Pane overlay) {
        var existing = overlay.getChildren().stream().filter(n -> "pointer".equals(n.getUserData())).findFirst();
        if (existing.isPresent())
            return (SVGPath) existing.get();
        var p = new SVGPath();
        p.setUserData("pointer");
        p.setContent("M0,0 L0,18 L5,13.5 L8.5,21 L11.5,19.8 L8,12.5 L14,12.5 Z");
        p.setFill(ACCENT);
        p.setStroke(Color.WHITE);
        p.setStrokeWidth(1.2);
        p.setTranslateX(overlay.getWidth() / 2);
        p.setTranslateY(overlay.getHeight() / 2);
        overlay.getChildren().add(p);
        return p;
    }

    private static Label pill(String text) {
        var l = new Label(text);
        l.setStyle(PILL);
        return l;
    }

    /** Adds a line to the window's action log, which fades out once the agent has been idle for a few seconds. */
    private void log(Pane o, String action) {
        var panel = (VBox) o.getChildren().stream().filter(n -> "log".equals(n.getUserData())).findFirst().orElse(null);
        if (panel == null) {
            panel = new VBox(3);
            panel.setUserData("log");
            panel.setPadding(new Insets(8));
            panel.setStyle(PANEL);
            var head = new Label(client);
            head.setStyle("-fx-text-fill: #D97757; -fx-font-weight: bold; -fx-font-size: 12px;");
            panel.getChildren().add(head);
            o.getChildren().add(panel);
        }
        if (panel.getOpacity() == 0)
            panel.getChildren().remove(1, panel.getChildren().size());
        ((Label) panel.getChildren().get(0)).setText(client);
        var line = new Label(action);
        line.setStyle("-fx-text-fill: white; -fx-font-size: 12px;");
        line.setMaxWidth(260);
        panel.getChildren().add(line);
        while (panel.getChildren().size() > LOG_LINES + 1)
            panel.getChildren().remove(1);
        int n = panel.getChildren().size() - 1;
        for (int i = 1; i <= n; i++)
            panel.getChildren().get(i).setOpacity(0.45 + 0.55 * i / n);
        show(panel);
        panel.applyCss();
        panel.autosize();
        double badgeRoom = badge != null && badge.getParent() == o ? badge.getHeight() + 8 : 0;
        panel.relocate(o.getWidth() - panel.getWidth() - 12, o.getHeight() - panel.getHeight() - 12 - badgeRoom);
        hideLater(panel, LOG_HOLD);
    }

    /** Fades a one-off mark out after {@code hold} and removes it. */
    private static void fadeOut(Node node, Duration hold) {
        var fade = new FadeTransition(FADE, node);
        fade.setDelay(hold);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            if (node.getParent() instanceof Pane p)
                p.getChildren().remove(node);
        });
        fade.play();
    }

    /** Fades a reusable mark (the badge, the log) to transparent after {@code hold}; {@link #show} cancels the fade. */
    private static void hideLater(Node node, Duration hold) {
        var fade = (FadeTransition) node.getProperties().computeIfAbsent("fade", k -> new FadeTransition(FADE, node));
        fade.stop();
        fade.setDelay(hold);
        fade.setFromValue(node.getOpacity());
        fade.setToValue(0);
        fade.playFromStart();
    }

    private static void show(Node node) {
        if (node.getProperties().get("fade") instanceof FadeTransition f)
            f.stop();
        node.setOpacity(1);
    }
}
