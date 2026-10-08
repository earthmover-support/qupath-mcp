# qupath-mcp

Drive QuPath's GUI from an AI agent or a test script.

QuPath's own scripting reaches the data model: images, objects, measurements. An agent also needs the GUI: open menus, read and answer dialogs, see the screen. These tools do that without deadlocking on modal dialogs, which block the window behind them until closed.

!!! warning
    The endpoint runs arbitrary Groovy as your user. Use it for testing only. See [Security](security.md).

The project has two parts:

- **A QuPath 0.7 extension** (`qupath-gui-driver`). It adds the *endpoint*: a local HTTP server that runs Groovy (QuPath's scripting language) against the GUI and returns window screenshots. The endpoint is off unless QuPath starts with `-Dqupath.driver.port=<port>`. With `-Dqupath.driver.script=<file>` the extension runs a Groovy file instead.
- **An MCP server** (`mcp/server.py`) that wraps the endpoint as tools for Claude Code or any other MCP client. The client starts the server and talks MCP over its stdin/stdout (stdio).

## What the tools handle for you

- `qupath_describe` returns a dialog's buttons, text fields, tables and labels as text, so the agent can read a dialog without a screenshot.
- `qupath_menu` and `qupath_click` return without waiting for the click handler. A handler that opens a modal dialog would otherwise block the call until the dialog closes or the 60 s timeout expires.
- `qupath_screenshot` returns the PNG inline.
- On macOS, `qupath_launch` starts QuPath in the background, so it does not take focus from your windows.

## Example session

The loop an agent runs against a QuPath dialog, here the license window:

1. `qupath_launch()` starts QuPath with the endpoint and returns once it answers.
2. `qupath_menu("Help>License")` fires the menu item and returns the open windows as JSON, which now include the dialog.
3. `qupath_describe("Licenses")` returns the dialog's controls as a text outline.
4. `qupath_click("<button text>", "Licenses")` presses a button named in that outline and returns the open windows again.
5. `qupath_screenshot("Licenses")` returns the window as a PNG when the text outline is not enough.

Every call raises if QuPath is unreachable or the GUI action fails, so the agent sees an error instead of a silent no-op.

## Where to go next

- [Install](install.md): build the extension, register the MCP server, set environment variables.
- [Tools](tools.md): every MCP tool and when to use it.
- [Use without MCP](without-mcp.md): the endpoint, script mode and the Groovy bindings, for test scripts.
- [Traps](traps.md): modal dialogs, the JavaFX thread and zoom order.
- [Security](security.md): what the endpoint exposes.
- [Contributing](contributing.md): building, limits and source layout.
