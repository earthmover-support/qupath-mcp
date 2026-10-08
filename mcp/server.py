"""MCP server for driving QuPath's GUI through the qupath-gui-driver extension's HTTP endpoint."""

import json
import os
import subprocess
import sys
import time
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


def _groovy(code: str, timeout_s: float = 60) -> dict | str:
    """The /groovy response, or a message if QuPath is unreachable."""
    try:
        return json.loads(_request(f"/groovy?timeout={timeout_s}", code.encode(), timeout=timeout_s + 10))
    except OSError as e:
        return f"Cannot reach QuPath on port {PORT} ({e}); call qupath_launch first."


def _error(trace: str) -> str:
    """A Groovy failure without the JVM's frames: the exception, its causes, and the script lines they came from."""
    lines = trace.splitlines()
    return "\n".join(l for i, l in enumerate(lines) if i == 0 or l.startswith("Caused by") or "Script1" in l)


def _act(code: str) -> str:
    """Run a non-blocking GUI action, give any dialog a second to appear, and return the showing windows."""
    r = _groovy(code + "\nThread.sleep(1000)", 30)
    if isinstance(r, str):
        return r
    return _request("/windows").decode() if r["ok"] else _error(r["error"])


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
    return f"QuPath did not answer within {timeout_s}s; log: {LOG}"


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
    if isinstance(r, str):
        return r
    parts = [f"ok: {r['ok']}", f"result: {r['result']}"]
    if r["output"]:
        parts.append(f"output:\n{r['output']}")
    if r["error"]:
        parts.append(f"error:\n{_error(r['error'])}")
    return "\n".join(parts)


@mcp.tool()
def qupath_menu(path: str) -> str:
    """Fire a menu item such as "File>Open..." or "Analyze>Cell detection>Cell detection" without blocking.

    Waits about a second and returns the showing windows as JSON, so a dialog the item opened is visible.
    Use qupath_describe next to read the dialog, then qupath_click / qupath_type to drive it."""
    return _act(f"fire(findMenuItem({_lit(path)}))")


@mcp.tool()
def qupath_click(text: str, window: str | None = None) -> str:
    """Click the button or checkbox whose text is exactly `text`, without blocking (safe for buttons that open modal dialogs).

    `window` is a title substring; default is the focused or topmost non-main window, else the main window.
    Waits about a second and returns the showing windows as JSON. Prefer this over qupath_run_groovy for dialogs."""
    return _act(f"click(lookup(window({_lit(window)}), {_lit(text)}))")


@mcp.tool()
def qupath_type(text: str, window: str | None = None, field: int = 0) -> str:
    """Set the text of the `field`-th (0-based) text field in a window; `window` defaults as in qupath_click.

    Use qupath_describe to see which field is which."""
    r = _groovy(f"typeInto(window({_lit(window)}), {_lit(text)}, {field})")
    return r if isinstance(r, str) else "typed" if r["ok"] else _error(r["error"])


@mcp.tool()
def qupath_describe(window: str | None = None) -> str:
    """Text outline of a window's controls: buttons, checkboxes, labels, text fields, combo boxes, tables, lists.

    Use this before qupath_screenshot to read a dialog's state as text. With no `window` (a title substring),
    describes every showing window except the main one, and for the main window gives only the title, the
    viewer's image server path, downsample, centre, z/t and annotation count."""
    if window:
        code = f"describe(window({_lit(window)}))"
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
    r = _groovy(code)
    if isinstance(r, str):
        return r
    return (r["output"] or r["result"]) if r["ok"] else _error(r["error"])


@mcp.tool()
def qupath_view(x: float, y: float, downsample: float, z: int | None = None, t: int | None = None) -> str:
    """Centre the active viewer on image pixel (x, y) at `downsample`, optionally at plane z / timepoint t.

    Use this instead of setting viewer properties by hand: it sets the zoom before the centre, which
    setDownsampleFactor would otherwise shift."""
    r = _groovy(f"view({x}, {y}, {downsample}, {'null' if z is None else z}, {'null' if t is None else t})")
    return r if isinstance(r, str) else "ok" if r["ok"] else _error(r["error"])


@mcp.tool()
def qupath_windows() -> str:
    """List showing QuPath windows as JSON (title, x, y, w, h, focused)."""
    try:
        return _request("/windows").decode()
    except OSError as e:
        return f"Cannot reach QuPath on port {PORT} ({e}); call qupath_launch first."


@mcp.tool()
def qupath_screenshot(window: str | None = None) -> Image:
    """Screenshot a QuPath window as a PNG. `window` is a title substring (see qupath_windows); default is the main window.

    The macOS menu bar is not captured."""
    path = "/snapshot" + (f"?window={urllib.parse.quote(window)}" if window else "")
    return Image(data=_request(path), format="png")


@mcp.tool()
def qupath_log(lines: int = 100) -> str:
    """Tail of the log of the QuPath process started by qupath_launch."""
    if not LOG.exists():
        return f"no log at {LOG}"
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
