# Tools

A failing tool returns an error result (`isError`) with the message. Groovy failures come back as the exception, its causes and the script lines they came from. `qupath_screenshot` fails when no window matches the title and index.

Tools that act on the GUI (`qupath_menu`, `qupath_click`, `qupath_select`, `qupath_set`, `qupath_key`, `qupath_open`) return without waiting for the handler, so a modal dialog never blocks the call. They wait about a second, then return what they did, the open windows one per line (`title — WxH (focused)`), and the outline of each window the action opened, or `No new window opened.`, which tells the agent to check with `qupath_describe` or `qupath_wait`. Call that the *action result*.

| Tool | Use it to… | Returns |
|---|---|---|
| `qupath_run_groovy(code, timeout_s=60)` | do anything the dedicated tools don't cover | Captured `println` output, then `result: …` |
| `qupath_menu(path)` | fire a menu item such as `File>Open URI...`; a check item such as `Extensions>MCP server` is toggled | The action result |
| `qupath_click(text, window=None, button="left", double=False)` | press a button, tick a checkbox or click a label or cell; `button="right"` opens a context menu and returns its items, to click next | The action result |
| `qupath_select(text, window=None, control=None, check=None)` | pick an item in a combo box, choice box, list, tree, tab pane or table row; with `check=True` or `False`, tick or clear the checkbox of every matching table row | `selected "X" in ComboBox`, then the action result |
| `qupath_set(value, control, window=None)` | set a spinner, slider, checkbox (`true`/`false`) or text field | `set Spinner Size: to 7`, then the action result |
| `qupath_type(text, window=None, field=0)` | fill in a text field; `field` is its 0-based index | `typed into <window> field 0: "<text>"` |
| `qupath_key(keys, window=None)` | press keys, space-separated for several | `sent Enter to <node>`, then the action result |
| `qupath_open(path_or_uri)` | open an image without a file chooser | `opened <path>`, then the action result |
| `qupath_describe(window=None, offset=0, limit=20)` | read a dialog or the viewer state as text | The controls of every window matching `window`, each headed by its index |
| `qupath_wait(text, window=None, timeout_s=30, gone=False)` | wait for an image to load or a dialog to fill, instead of polling in Groovy | The `qupath_describe` output once `text` appears in it (with `gone=True`, disappears); on timeout an error with the last output |
| `qupath_view(x, y, downsample, z=None, t=None)` | move the viewer to a pixel, zoom, plane `z` and timepoint `t` | `ok` |
| `qupath_windows()` | find the window title to pass as `window` | One line per window: `title — WxH (focused)` |
| `qupath_screenshot(window=None, index=0, max_size=1024, format="png", quality=0.85, viewer=False, region=None)` | see what a window shows | The window as an image, plus a text item with its size |
| `qupath_quit()` | end a session | `quit requested` |

## Notes

- **Window matching.** A `window` argument is a case-sensitive substring of the window title, and `qupath_describe` covers every match. A popup often has the same title as its dialog, so `qupath_screenshot` takes an `index` (0 is the first match) numbered as `qupath_describe` numbers them; the other tools use the first match. With no `window`, they target an open context menu, else the focused non-main window, else the most recently opened one, else the main window. `qupath_screenshot` defaults to the main window.
- **Keys.** `qupath_key` takes JavaFX key names, for example `Enter`, `Escape` (or `Esc`), `Tab`, `Ctrl+A`, `Shortcut+S` (`Shortcut` is Cmd on macOS). Enter presses a dialog's default button and Escape its cancel button.
- **Paging.** Tables, lists and trees in `qupath_describe` print `limit` rows from `offset` and end with `rows X–Y of N`; the next page is `offset=Y`.
- **Controls.** `qupath_describe` tags the controls that `qupath_select` and `qupath_set` can address with `#n`. Their `control` argument is that tag (`#3` or `3`) or the text of the label next to the control (`Size:`); `qupath_select` without `control` tries every list-like control in turn.
- **Click targets.** `qupath_click` finds the first labeled node whose text is exactly `text`: a button, checkbox, hyperlink, label, or a visible table or tree cell. A button is fired; anything else, and any right or double click, gets synthetic mouse events. A missing target lists the buttons in the window it searched.
- **Screenshot crops.** `viewer=True` crops to the active viewer of the main window, `region=[x, y, w, h]` to window coordinates. The macOS menu bar is not included.
- **Screenshot size.** Image cost grows with pixel size, so the longest side is capped at `max_size` and a smaller image is never enlarged. Use `format="jpeg"` for slides and viewer content, where PNG is large and sharp text does not matter; PNG suits dialogs.
- **`qupath_open`.** It waits up to 5 s for QuPath to open the image. If a prompt (for example about unsaved changes) is holding it, the reply says `still opening` and the action result shows the prompt.
- **`qupath_describe` with no window.** It describes every open window except the main one, then gives the main window's title, the viewer's image path, downsample, centre, z/t and annotation count. The downsample is image pixels per screen pixel; 1 is full resolution.
- **File choosers are native.** QuPath's open and save dialogs are macOS dialogs that the scene graph does not contain, so no tool can read or drive them. When an action leaves QuPath's UI thread unresponsive for two seconds, the tool says a native dialog is probably open instead of a window list; close it in QuPath. Avoid them: `qupath_open` opens an image by path or URI, `File>Open URI...` is a normal dialog, and `qupath_run_groovy` can set any path.
- **Turning the server off.** `qupath_menu("Extensions>MCP server")` toggles the preference like a click, so an agent can switch the server off. The call replies first and the server then stops within a few seconds, ending the session; it has to be turned back on from QuPath's Extensions menu.
- **`qupath_quit`.** It returns `quit requested` and then exits QuPath after a short delay.
