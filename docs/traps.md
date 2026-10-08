# Traps

Three behaviours hang or misplace GUI scripts. The MCP tools and script helpers already avoid them, so this matters when you write your own Groovy.

- **Modal dialogs.** A modal dialog blocks the window behind it until it closes. A handler that calls `showAndWait` does not return until then, so a script that fires it synchronously hangs until the dialog closes or the 60 s timeout expires. `click` and `fire` queue the action and return; follow them with `waitFor` or a short sleep. `qupath_menu` and `qupath_click` do this for you.
- **JavaFX thread.** QuPath's GUI may only be touched from its UI thread (the JavaFX thread), and Groovy runs on another. Wrap your own access to the GUI in `fx { ... }`. The helpers in [the bindings table](without-mcp.md#bindings) do this themselves.
- **Zoom before centre.** Changing the downsample factor moves the viewer's centre, so set the zoom first. The downsample is image pixels per screen pixel; 1 is full resolution. `view` and `qupath_view` set the zoom first.

For the helpers these traps apply to, see [Use without MCP](without-mcp.md#bindings).
