# Tools

Each tool wraps a [Groovy binding](without-mcp.md#bindings), so the same operations work from a script without MCP. A failing tool raises, which MCP clients show as an error. Groovy failures come back as the exception, its causes and the script lines they came from. `qupath_screenshot` raises when no window matches the title and index.

| Tool | Wraps | Returns | When to use it |
|---|---|---|---|
| `qupath_launch(timeout_s=60, foreground=$QUPATH_FOREGROUND)` | none (starts the process) | `started, listening on port <port>; log: <path>`, or `already running …` | First call of a session. |
| `qupath_run_groovy(code, timeout_s=60)` | `/groovy` endpoint | Captured `println` output, then `result: …`, as text | Anything the dedicated tools don't cover. |
| `qupath_menu(path)` | `findMenuItem`, `fire` | Open windows as JSON, then the outline of each window the item opened | Opening a dialog from the menu bar, e.g. `File>Open...`. |
| `qupath_click(text, window=None)` | `lookup`, `click` | Open windows as JSON, then the outline of each window the click opened | Driving a dialog; an error popup's text comes back directly. |
| `qupath_type(text, window=None, field=0)` | `typeInto` | `typed` | Filling in a dialog. `field` is the 0-based index of the text field. |
| `qupath_describe(window=None)` | `describe` | Text outline of the controls of every window matching `window`, each headed by its index | Reading a dialog or the viewer state. |
| `qupath_wait(text, window=None, timeout_s=30, gone=False)` | `describe` | The describe output once `text` appears in it (or, with `gone=True`, disappears); raises on timeout with the last output | Waiting for an image to load or a dialog to fill, instead of polling in Groovy. |
| `qupath_view(x, y, downsample, z=None, t=None)` | `view` | `ok` | Moving the viewer to a pixel, zoom, plane `z` and timepoint `t`. |
| `qupath_windows()` | `/windows` endpoint | JSON array: title, x, y, w, h, focused | Finding a window title to pass as `window`. |
| `qupath_screenshot(window=None, index=0)` | `/snapshot` endpoint, or `Scene.snapshot` for `index` above 0 | PNG image | Seeing what a window shows. |
| `qupath_log(lines=100)` | none (reads `QUPATH_DRIVER_LOG`, written only by `qupath_launch`) | Last `lines` lines of the log | After a failed launch or an error, when `qupath_launch` started QuPath. |
| `qupath_quit()` | `quit` | `quit requested` | End of a session. |

`qupath_menu` and `qupath_click` wait about a second after the action, so a dialog or popup it opened is in the returned windows and its outline follows the window list.

## Notes

- **Window matching.** A `window` argument is a case-sensitive substring of the window title, and `qupath_describe` covers every match. A popup often has the same title as its dialog, so `qupath_screenshot` takes an `index` (0 is the first match) numbered as `qupath_describe` numbers them; `qupath_click` and `qupath_type` use the first match. With no `window`, `qupath_click`, `qupath_type` and `qupath_describe` target the focused non-main window, else the most recently opened one, else the main window. `qupath_screenshot` defaults to the main window.
- **Click targets.** `qupath_click` finds the first labeled node whose text is exactly `text`. It must be a button or checkbox.
- **`qupath_describe` with no window.** It describes every open window except the main one, then gives the main window's title, the viewer's image path, downsample, centre, z/t and annotation count. The downsample is image pixels per screen pixel; 1 is full resolution. The image path includes the repository version, so `qupath_wait` on a path detects an image switch even when the window title stays the same.
- **Menu bar.** The macOS menu bar is not captured in screenshots.
- **`qupath_log`.** It reads only the log of a QuPath started by `qupath_launch`. For a QuPath started another way there is no file; the call returns a message instead of raising, pointing to View > Show log in QuPath, or to `qupath_quit` then `qupath_launch` to capture the log.
- **`qupath_quit`.** It is best-effort: it returns `quit requested` even if QuPath was not running.
