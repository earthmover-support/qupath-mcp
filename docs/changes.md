# Changes

## 0.2.0

### If you're upgrading from 0.1.4

- **Three tools are gone.** A prompt, skill or script that names them fails.
    - `qupath_type`: use `qupath_set` with the field's `#n` or label. It also types into an editable combo box.
    - `qupath_windows`: every action result and `qupath_wait` list the open windows; `qupath_describe` with no `window` outlines every dialog.
    - `qupath_quit`: quitting is the person's call. The Groovy helper `quit()` remains.
- **The menu items moved** into one submenu: **Extensions ▸ MCP server ▸ Enabled** turns the server off and on, and **Extensions ▸ MCP server ▸ Show agent actions** sets the on-screen marks. Firing the submenu itself, `qupath_menu("Extensions>MCP server")`, now fails and lists its items.
- **qupath-mcp keeps an action log, on by default.** Every tool call is appended to a JSON Lines file in `mcp-logs` in QuPath's user directory, with the Groovy it ran and the text it returned. qupath-mcp warns past 1 GB and deletes nothing unless you turn deletion on. See [Action log](action-log.md).
- **`qupath_describe` lines read differently**, so anything that parses them needs updating: `#3 TextField "Sigma" = "1.5"` rather than `text="…"`, `= value` rather than `value=`, and a checkbox shows `[x]`, `[ ]` or `[-]`.
- `qupath_screenshot` no longer takes `quality`; JPEG is saved at quality 0.85.

### Smaller results

- An action that opens a large window returns its first controls, up to about 1,500 characters, and a pointer to `qupath_describe`, not the whole outline. A window with at most five controls, such as an error popup, comes back whole.
- `qupath_set` and `qupath_select` return the control's line as it reads afterwards, so checking a value needs no second call.
- `qupath_describe` takes `control` and returns just that control.
- `qupath_wait` returns the matching lines and the window list, not the whole outline.
- The tool list an agent loads in every session is about 7,200 characters, down from about 13,300.

### Reaching more controls

- A label names the control beside it, also when a form wraps the field in a pane with its units, as QuPath's parameter dialogs do: `qupath_set control="Sigma"` works in Cell detection.
- `qupath_click` ticks a Preferences checkbox by the label beside it, clicks an icon-only button by its printed name, such as `[By Category]`, and expands or collapses a titled pane.
- A menu path's trailing `...` is optional, and a path that doesn't match lists the items at the level that failed.
- An error that finds no control names the collapsed panes it may be in.

### Knowing what happened

- A result starts with the windows that opened or closed since the agent's last call, so a dialog a background task opens late is not missed.
- A native file chooser, which no tool can see, makes each tool fail within 2 s with a message saying so, instead of timing out after 60 s.
- Text typed into a password field never appears in results, marks or logs.
