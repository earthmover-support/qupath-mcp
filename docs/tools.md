# Tools

A failing tool returns an error result (`isError`) with the message. Groovy failures come back as the exception, its causes and the script lines they came from. `qupath_screenshot` fails when no window matches the title and index.

Tools that act on the GUI (`qupath_menu`, `qupath_click`, `qupath_select`, `qupath_set`, `qupath_key`, `qupath_close`, `qupath_open`) return without waiting for the handler, so a modal dialog never blocks the call. They wait about a second, then return what they did, the open windows one per line (`title — WxH (focused)`), and the outline of each window the action opened, or `No new window opened.`, which tells the agent to check with `qupath_describe` or `qupath_wait`. Call that the *action result*. An opened window's outline is whole when it has at most five controls, as an error popup does (its text fields cut only after 20,000 characters), or is at most 1,500 characters long. Otherwise it stops at the last whole control within 1,500 characters, and after 25 controls at most, with a line such as `+82 more controls; qupath_describe window="Preferences"` (or `+ more lines; …` when only text is left).

| Tool | Use it to… | Returns |
|---|---|---|
| `qupath_run_groovy(code, timeout_s=60)` | do anything the dedicated tools don't cover | Captured `println` output, then `result: …` |
| `qupath_menu(path)` | fire a menu item such as `File>Open URI...`; a check item such as `Extensions>MCP server>Enabled` is toggled | The action result |
| `qupath_click(text, window=None, button="left", double=False)` | press a button, tick a checkbox or click a label or cell; `button="right"` opens a context menu and returns its items, to click next | The action result |
| `qupath_select(text, window=None, control=None, check=None)` | pick an item in a combo box, choice box, list, tree, tab pane or table row; with `check=True` or `False`, tick or clear the checkbox of every matching table row | The control's `qupath_describe` line, as `#7 ComboBox "Detection" = Cell`, then the action result; with `check`, `checked 3 rows of TableView` |
| `qupath_set(value, control, window=None)` | set a spinner, slider, checkbox (`true`/`false`) or text field | The control's `qupath_describe` line with the value it holds after the change, as `#3 NumericField "Number of parallel threads" = "8"`, then the action result |
| `qupath_type(text, window=None, field=0)` | fill in a text field; `field` is its 0-based index | `typed into <window> field 0: "<text>"` |
| `qupath_key(keys, window=None)` | press keys, space-separated for several | `sent Enter to <node>`, then the action result |
| `qupath_close(window=None)` | close a window, such as a measurement table, as its close button would; a window that prompts first shows its prompt | `closed <title>`, then the action result |
| `qupath_open(path_or_uri)` | open an image without a file chooser | `opened <path>`, then the action result |
| `qupath_describe(window=None, control=None, offset=0, limit=20)` | read a dialog or the viewer state as text; `control` reads one control, as `qupath_set` finds it | The controls of every window matching `window`, each headed by its index; with `control`, the lines of each control it matches, in the window `window` picks as `qupath_set` does |
| `qupath_wait(text, window=None, timeout_s=30, gone=False)` | wait for an image to load or a dialog to fill, instead of polling in Groovy | Once `text` appears in the `qupath_describe` output, up to 20 lines that contain it (then `+N more lines`; `found 'X'` if it spans lines; with `gone=True`, `'X' is gone`), then the open windows; on timeout an error with the last output |
| `qupath_view(x, y, downsample, z=None, t=None)` | move the viewer to a pixel, zoom, plane `z` and timepoint `t` | `ok` |
| `qupath_windows()` | find the window title to pass as `window` | One line per window: `title — WxH (focused)` |
| `qupath_screenshot(window=None, index=0, max_size=1024, format="png", quality=0.85, viewer=False, region=None)` | see what a window shows | The window as an image, plus a text item with its size |
| `qupath_show_actions(mode=None, delay_ms=None)` | change what the person watching sees: `off`, `mark` or `paced`, and the delay before each paced action | `mode: mark, delay_ms: 1000` |
| `qupath_quit()` | end a session | `quit requested` |

## The action log

qupath-mcp writes every action the agent takes to a log file; see [Action log](action-log.md).

## Watching the agent

qupath-mcp shows the person at the screen what the agent does, in one of three modes:

| Mode | What it shows | Effect on the agent |
|---|---|---|
| **Mark** (default) | A ring on each control the agent clicks, types into, selects or sets, or on the row a selection picks in a list, tree or table; a crosshair where the viewer moves to; a keycap for key presses. A panel in the window's lower right lists the last six actions, including menu items, opened images and Groovy runs, and fades after 4 s without activity. A badge names the client, as *Claude Code is driving*, while a tool call runs. | None. The marks are drawn after the action is dispatched. |
| **Paced** | The same, and before each action a pointer moves to the control, or the menu path, key or viewer position shows at the top of the window. For a row in a list, tree or table, the pointer moves once the selection has scrolled the row into view. `qupath_view` pans and zooms to the new view over the delay instead of jumping. | Waits the paced delay (default 1000 ms) before each action. |
| **Off** | Nothing. | None. |

Paced suits screen recordings and anyone following along. The person sets the mode under **Extensions ▸ MCP server ▸ Show agent actions** and the delay with the *Paced delay (ms)* preference; the agent sets both with `qupath_show_actions`. Both persist across restarts.

The marks are drawn only in QuPath's windows: a menu in the macOS menu bar can't be opened from code, so paced mode shows its path instead. `qupath_screenshot`, `qupath_describe` and click lookups leave the marks out, so the agent sees QuPath as it is.

## Notes

- **Window matching.** A `window` argument is a case-sensitive substring of the window title, and `qupath_describe` covers every match. A popup often has the same title as its dialog, so `qupath_screenshot` takes an `index` (0 is the first match) numbered as `qupath_describe` numbers them; the other tools use the first match. With no `window`, they target an open context menu, else the focused non-main window, else the most recently opened one, else the main window. `qupath_screenshot` defaults to the main window.
- **Keys.** `qupath_key` takes JavaFX key names, for example `Enter`, `Escape` (or `Esc`), `Tab`, `Ctrl+A`, `Shortcut+S` (`Shortcut` is Cmd on macOS). Enter presses a dialog's default button and Escape its cancel button.
- **Windows opened while waiting.** `qupath_wait` notes the open windows when it starts. Its result, on success and on timeout, ends with `Opened while waiting:` and the outline of each window that opened since, cut short as in an action result, so a warning dialog is not missed.
- **Paging.** Tables, lists and trees in `qupath_describe` print `limit` rows from `offset` and end with `rows X–Y of N`; the next page is `offset=Y`. Controls are not paged: every one is listed.
- **Waiting on long tables.** `qupath_wait` searches every row of every table, list and tree, not only the page `qupath_describe` prints, so it can wait for a row far down a table, and it matches a text field's whole text, not only the 200 characters `qupath_describe` shows. A line longer than 200 characters comes back as the 80 characters either side of `text`. On a timeout it says so, and prints the first page.
- **Sorting.** Click a column header with `qupath_click` to sort a table by it, and again to reverse. `qupath_describe` marks the sort column with ↑ or ↓, as `Area µm^2 ↓`. Selecting a row of QuPath's measurement tables also selects that object in the viewer.
- **Controls.** `qupath_describe` tags buttons, checkboxes and the controls that `qupath_select` and `qupath_set` can address with `#n`. A line reads `#n Type "label" = value`: a label just before the control it names is shown on the control's line instead of its own, as `#3 NumericField "Number of parallel threads" = "8"`. A checkbox shows `[x]`, `[ ]`, or `[-]` when indeterminate, a text field's text is quoted and cut after 200 characters (`… (512 chars)`), and a password field's text is `••••••`. An icon-only button shows its tooltip's first line, as `Button [Install extension]`, else its accessible text or graphic, else just `Button`. Their `control` argument is that tag (`#3` or `3`) or the text of the label next to the control (`Size:`); `qupath_select` without `control` tries every list-like control in turn.
- **Click targets.** `qupath_click` takes `text` as a `#n` from `qupath_describe`, else finds the first labeled node whose text is exactly `text`, else the one whose tooltip (whole, then first line) or accessible text is, else the one whose bracketed name `qupath_describe` shows is (`[Install extension]`): a button, checkbox, hyperlink, label, or a visible table or tree cell. If several of these later matches exist (one "Install extension" button per row), the error lists them as `#n [near "label"]`; click one by its `#n`. A button is fired; anything else, and any right or double click, gets synthetic mouse events. A missing target lists the buttons in the window it searched.
- **Screenshot crops.** `viewer=True` crops to the active viewer of the main window, `region=[x, y, w, h]` to window coordinates. The macOS menu bar is not included.
- **Screenshot size.** Image cost grows with pixel size, so the longest side is capped at `max_size` and a smaller image is never enlarged. Use `format="jpeg"` for slides and viewer content, where PNG is large and sharp text does not matter; PNG suits dialogs.
- **`qupath_open`.** It waits up to 5 s for QuPath to open the image. If a prompt (for example about unsaved changes) is holding it, the reply says `still opening` and the action result shows the prompt.
- **`qupath_describe` with no window.** It describes every open window except the main one, then gives the main window's title, the viewer's image path, downsample, centre, z/t and annotation count. The downsample is image pixels per screen pixel; 1 is full resolution.
- **File choosers are native.** QuPath's open and save dialogs are macOS dialogs that the scene graph does not contain, so no tool can read or drive them. When an action leaves QuPath's UI thread unresponsive for two seconds, the tool says a native dialog is probably open instead of a window list; close it in QuPath. Avoid them: `qupath_open` opens an image by path or URI, `File>Open URI...` is a normal dialog, and `qupath_run_groovy` can set any path.
- **Turning the server off.** `qupath_menu("Extensions>MCP server>Enabled")` toggles the preference like a click, so an agent can switch the server off. The call replies first and the server then stops within a few seconds, ending the session; it has to be turned back on from QuPath's Extensions menu.
- **`qupath_quit`.** It returns `quit requested` and then exits QuPath after a short delay.
