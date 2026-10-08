"""MCP server for driving QuPath's GUI through the qupath-gui-driver extension's HTTP endpoint."""

import base64
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from mcp.server.fastmcp import FastMCP, Image

PORT = int(os.environ.get("QUPATH_DRIVER_PORT", "51515"))
BASE = f"http://127.0.0.1:{PORT}"
APP = Path(os.environ.get(
    "QUPATH_APP",
    "/Applications/QuPath-0.7.0-arm64.app/Contents/MacOS/QuPath-0.7.0-arm64",
))
LOG = Path(os.environ.get("QUPATH_DRIVER_LOG", "/tmp/qupath-driver.log"))
FOREGROUND = os.environ.get("QUPATH_FOREGROUND", "") not in ("", "0", "false")

mcp = FastMCP("qupath")


def _request(path: str, data: bytes | None = None, timeout: float = 70) -> bytes:
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data), timeout=timeout) as r:
        return r.read()


def _up() -> bool:
    try:
        _request("/windows", timeout=3)
        return True
    except OSError:
        return False


def _lit(s: str | None) -> str:
    """Groovy single-quoted literal (no interpolation), or null."""
    if s is None:
        return "null"
    return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"


def _groovy(code: str, timeout_s: float = 60) -> dict:
    """The /groovy response. Raising, here and below, is what makes the MCP client see the call as failed."""
    try:
        return json.loads(_request(f"/groovy?timeout={timeout_s}", code.encode(), timeout=timeout_s + 10))
    except OSError as e:
        raise _unreachable(e) from e


def _unreachable(e: OSError) -> RuntimeError:
    return RuntimeError(f"Cannot reach QuPath on port {PORT} ({e}); call qupath_launch first.")


def _windows() -> str:
    try:
        return _request("/windows").decode()
    except OSError as e:
        raise _unreachable(e) from e


def _error(trace: str) -> str:
    """A Groovy failure without the JVM's frames: the exception, its causes, and the script lines they came from."""
    lines = trace.splitlines()
    return "\n".join(l for i, l in enumerate(lines) if i == 0 or l.startswith("Caused by") or "Script1" in l)


def _ok(r: dict) -> dict:
    if not r["ok"]:
        raise RuntimeError(_error(r["error"]))
    return r


def _act(code: str) -> str:
    """Run a non-blocking GUI action, give any dialog a second to appear, and return the open windows
    followed by the description of each window the action opened."""
    r = _ok(_groovy(f"""
import javafx.stage.Window
def before = fx {{ Window.windows.toList() }}
{code}
Thread.sleep(1000)
def opened = fx {{ Window.windows.findAll {{ w -> w.showing && !before.any {{ it.is(w) }} }} }}
opened.each {{ println describe(it) }}""", 30))
    return _windows() + ("\n\nOpened by the action:\n" + r["output"] if r["output"] else "")


def _describe(window: str | None) -> str:
    """Outline of every open window whose title contains `window`, or of all windows and the main-window summary."""
    if window:
        code = f"""
import javafx.stage.Stage
import javafx.stage.Window
def ws = fx {{ Window.windows.findAll {{ it.showing && it instanceof Stage && it.title?.contains({_lit(window)}) }} }}
ws.eachWithIndex {{ w, i -> println "[${{i}}] " + describe(w) }}"""
    else:
        code = """
import javafx.stage.Stage
import javafx.stage.Window
def others = fx { Window.windows.findAll { it.showing && it instanceof Stage && it != qupath.stage } }
others.each { println describe(it) }
println fx {
    def v = qupath.viewer
    "${qupath.stage.title}\\nimage: ${v.imageData?.server?.path}\\ndownsample: ${v.downsampleFactor} " +
        "centre: ${v.centerPixelX}, ${v.centerPixelY} z: ${v.getZPosition()} t: ${v.getTPosition()}\\n" +
        "annotations: ${v.imageData?.hierarchy?.annotationObjects?.size()}"
}"""
    r = _ok(_groovy(code))
    return (r["output"] or ("" if window else r["result"])).strip()


@mcp.tool()
def qupath_launch(timeout_s: int = 60, foreground: bool = FOREGROUND) -> str:
    """Start QuPath (with the driver endpoint) if it is not already running; waits until it answers.

    Call this first. On macOS QuPath starts in the background, so it never takes focus from the user's windows;
    screenshots and every other tool work the same. Pass foreground=True (or set QUPATH_FOREGROUND=1) when the user
    wants to watch. QuPath's log goes to the path returned; see qupath_log."""
    if _up():
        return f"already running on port {PORT}; log: {LOG}"
    args = ["-q", f"-Dqupath.driver.port={PORT}"]
    bundle = next((p for p in APP.parents if p.suffix == ".app"), None)
    if sys.platform == "darwin" and bundle and not foreground:
        # Running the launcher directly activates the app, and macOS then raises each window it opens; `open -g`
        # starts it without activating.
        subprocess.run(["open", "-g", "-n", "-a", str(bundle), "--stdout", str(LOG), "--stderr", str(LOG),
                        "--args", *args], check=True)
    else:
        with LOG.open("ab") as log:
            subprocess.Popen([str(APP), *args], stdout=log, stderr=log, stdin=subprocess.DEVNULL,
                             start_new_session=True)
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        if _up():
            return f"started, listening on port {PORT}; log: {LOG}"
        time.sleep(1)
    raise RuntimeError(f"QuPath did not answer within {timeout_s}s; log: {LOG}")


@mcp.tool()
def qupath_run_groovy(code: str, timeout_s: int = 60) -> str:
    """Run Groovy in the running QuPath and return its output, result and any error.

    `qupath` is the QuPathGUI. Code runs on a background thread, so wrap GUI access in fx { ... }.
    Prefer the dedicated tools (qupath_menu, qupath_click, qupath_type, qupath_describe, qupath_view) for
    common GUI steps; use this for anything else.
    Helpers: fx{}, snap(name), snapMain(name), waitFor(titleSubstring, seconds), window(titleSubstring or null),
    lookup(window, text), click(node) and fire(menuItem) (both return immediately, so a modal dialog they open
    does not block), findMenuItem("Help>License"), typeInto(window, text[, fieldIndex]), describe(window),
    view(x, y, downsample[, z, t]), quit(). println is captured."""
    r = _groovy(code, timeout_s)
    output = f"output:\n{r['output']}\n" if r["output"] else ""
    if not r["ok"]:
        raise RuntimeError(f"{output}error:\n{_error(r['error'])}")
    return f"{output}result: {r['result']}"


@mcp.tool()
def qupath_menu(path: str) -> str:
    """Fire a menu item such as "File>Open..." or "Analyze>Cell detection>Cell detection" without blocking.

    Waits about a second and returns the open windows as JSON, then the description of each window the item
    opened. Use qupath_click / qupath_type to drive the dialog."""
    return _act(f"fire(findMenuItem({_lit(path)}))")


@mcp.tool()
def qupath_click(text: str, window: str | None = None) -> str:
    """Click the button or checkbox whose text is exactly `text`, without blocking (safe for buttons that open modal dialogs).

    `window` is a title substring; default is the focused or topmost non-main window, else the main window.
    Waits about a second and returns the open windows as JSON, then the description of each window the click
    opened (so an error popup's text is returned directly). Prefer this over qupath_run_groovy for dialogs."""
    return _act(f"click(lookup(window({_lit(window)}), {_lit(text)}))")


@mcp.tool()
def qupath_type(text: str, window: str | None = None, field: int = 0) -> str:
    """Set the text of the `field`-th (0-based) text field in a window; `window` defaults as in qupath_click.

    Use qupath_describe to see which field is which."""
    r = _groovy(f"typeInto(window({_lit(window)}), {_lit(text)}, {field})")
    _ok(r)
    return "typed"


@mcp.tool()
def qupath_describe(window: str | None = None) -> str:
    """Text outline of a window's controls: buttons, checkboxes, labels, text fields, combo boxes, tables, lists.

    Use this before qupath_screenshot to read a dialog's state as text. `window` is a title substring and
    describes every open window it matches, each headed by its index (a popup can share its dialog's title).
    With no `window`, describes every open window except the main one, and for the main window gives only the
    title, the viewer's image server path, downsample, centre, z/t and annotation count."""
    out = _describe(window)
    if window and not out:
        raise ValueError(f"No open window whose title contains {window!r}; see qupath_windows.")
    return out


@mcp.tool()
def qupath_wait(text: str, window: str | None = None, timeout_s: float = 30, gone: bool = False) -> str:
    """Wait until `text` appears in (or, with gone=True, disappears from) the qupath_describe output; return that output.

    `window` is a title substring, as in qupath_describe. With no `window` it watches all open windows and the
    main-window summary, so it also detects an image switch by the image path. Raises on timeout with the last output."""
    deadline = time.time() + timeout_s
    while True:
        out = _describe(window)
        if (text in out) != gone:
            return out
        if time.time() >= deadline:
            raise TimeoutError(f"Timed out after {timeout_s}s waiting for {text!r} to {'disappear' if gone else 'appear'}; last output:\n{out}")
        time.sleep(0.5)


@mcp.tool()
def qupath_view(x: float, y: float, downsample: float, z: int | None = None, t: int | None = None) -> str:
    """Centre the active viewer on image pixel (x, y) at `downsample`, optionally at plane z / timepoint t.

    Use this instead of setting viewer properties by hand: it sets the zoom before the centre, which
    setDownsampleFactor would otherwise shift."""
    r = _groovy(f"view({x}, {y}, {downsample}, {'null' if z is None else z}, {'null' if t is None else t})")
    _ok(r)
    return "ok"


@mcp.tool()
def qupath_windows() -> str:
    """List open QuPath windows as JSON (title, x, y, w, h, focused)."""
    return _windows()


@mcp.tool()
def qupath_screenshot(window: str | None = None, index: int = 0) -> Image:
    """Screenshot a QuPath window as a PNG. `window` is a title substring (see qupath_windows); default is the main window.

    `index` picks among windows with the same title (0 = first), as numbered by qupath_describe.
    The macOS menu bar is not captured."""
    if index:
        r = _ok(_groovy(f"""
import java.util.Base64
import javafx.embed.swing.SwingFXUtils
import javafx.stage.Stage
import javafx.stage.Window
import javax.imageio.ImageIO
def w = fx {{ Window.windows.findAll {{ it.showing && it instanceof Stage && it.title?.contains({_lit(window)}) }}[{index}] }}
if (w == null) return null
fx {{
    def out = new ByteArrayOutputStream()
    ImageIO.write(SwingFXUtils.fromFXImage(w.scene.snapshot(null), null), "png", out)
    Base64.encoder.encodeToString(out.toByteArray())
}}"""))
        if not r["result"] or r["result"] == "null":
            raise ValueError(f"No window {index} whose title contains {window!r}; see qupath_describe.")
        return Image(data=base64.b64decode(r["result"]), format="png")
    path = "/snapshot" + (f"?window={urllib.parse.quote(window)}" if window else "")
    try:
        return Image(data=_request(path), format="png")
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise ValueError(f"No open window whose title contains {window!r}; see qupath_windows.") from e
        raise


@mcp.tool()
def qupath_log(lines: int = 100) -> str:
    """Tail of the log of the QuPath process started by qupath_launch.

    QuPath's own log is not captured when QuPath was started any other way; then this finds no file."""
    if not LOG.exists():
        return (f"no log at {LOG}: QuPath's output is captured only when qupath_launch started it. "
                "QuPath's own log is under View > Show log; to capture it here, qupath_quit and qupath_launch again.")
    return "\n".join(LOG.read_text(errors="replace").splitlines()[-lines:])


@mcp.tool()
def qupath_quit() -> str:
    """Quit QuPath."""
    try:
        _request("/groovy?timeout=5", b"quit()", timeout=10)
    except OSError:
        pass
    return "quit requested"


if __name__ == "__main__":
    mcp.run()
