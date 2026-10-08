# qupath-mcp

Drive QuPath's GUI from an AI agent. A QuPath 0.7 extension runs an MCP server inside QuPath, which Claude Code or any other MCP client connects to.

## Install

1. Download `qupath-gui-driver-<version>-all.jar` from [Releases](https://github.com/earthmover-support/qupath-mcp/releases), drag it onto QuPath and restart QuPath.
2. Register the server with your agent:

    --8<-- "register.md"

3. Have QuPath open when the client connects.

Details, including building from source, are in [Install](install.md).

!!! warning
    The MCP server lets the connected agent run any code in QuPath, as you, with access to your files. Give it only as much trust as you give your coding agent. It is on whenever the jar is installed and any program on this computer can reach it; turn it off with **Extensions ▸ MCP server**. See [Security](security.md).

## Why

QuPath's own scripting reaches the data model: images, objects, measurements. An agent also needs the GUI: open menus, read and answer dialogs, see the screen. These tools do that without deadlocking on modal dialogs, which block the window behind them until closed.

The jar is the `qupath-gui-driver` extension. The server speaks MCP over HTTP on 127.0.0.1 and also exposes a plain HTTP endpoint that runs Groovy (QuPath's scripting language) against the GUI, for [scripts that are not MCP clients](without-mcp.md).

## What the tools handle for you

- `qupath_describe` returns a dialog's buttons, text fields, tables and labels as text, so the agent can read a dialog without a screenshot.
- Tools that act on the GUI, such as `qupath_menu` and `qupath_click`, return without waiting for the handler. A handler that opens a modal dialog would otherwise block the call until the dialog closes or the 60 s timeout expires.

## Example session

The loop an agent runs against a QuPath dialog, here the file-path prompt:

1. `qupath_menu("File>Open URI...")` fires the menu item and returns the open windows, one per line, then the outline of the dialog it opened.
2. `qupath_describe("Choose path")` returns the dialog's controls as a text outline.
3. `qupath_type("/path/to/image.tif", "Choose path")` fills in the text field.
4. `qupath_click("OK", "Choose path")` presses a button named in that outline and returns the open windows again.
5. `qupath_screenshot("Choose path")` returns a screenshot of that window as an image when the text outline is not enough.

To open an image without a dialog, `qupath_open` does it in one call. A failing call returns an error result with the message, so the agent sees an error instead of a silent no-op.

## Where to go next

- [Tools](tools.md): every tool and when to use it.
- [Use without MCP](without-mcp.md): the HTTP endpoints, script mode and the Groovy bindings, for test scripts.
- [Traps](traps.md): modal dialogs, the JavaFX thread and zoom order.
- [Security](security.md): what the server exposes.
- [Contributing](contributing.md): building, releasing and source layout.
