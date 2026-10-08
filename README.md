# qupath-mcp

Drive QuPath's GUI from an AI agent or a test script.

This repository has two parts:

- **A QuPath 0.7 extension** (`qupath-gui-driver`). When QuPath starts with `-Dqupath.driver.port=<port>`, it serves an HTTP endpoint on 127.0.0.1 that runs Groovy against the GUI and returns window screenshots. With `-Dqupath.driver.script=<file>` it runs a Groovy file instead.
- **An MCP server** (`mcp/server.py`) that wraps the endpoint as tools for Claude Code or any other MCP client.

It is a general GUI driver and does not depend on any image or file format.

What the tools handle for you:

- `qupath_describe` returns a dialog's buttons, text fields, tables and labels as text, so the agent can read a dialog without a screenshot.
- `qupath_menu` and `qupath_click` return without waiting for the click handler. A handler that opens a modal dialog would otherwise block the call forever.
- `qupath_screenshot` returns the PNG inline.
- On macOS, `qupath_launch` starts QuPath in the background, so it does not take focus from your windows.

## Install

Build the extension jar. Gradle downloads the JDK it needs.

    ./gradlew shadowJar

Copy `build/libs/qupath-gui-driver-<version>-all.jar` into QuPath's extensions directory, then restart QuPath. The directory is `extensions/` inside QuPath's user directory. The user directory is the "QuPath user directory" setting in Preferences. *Extensions > Manage extensions* lists what is installed.

Register the MCP server with Claude Code. It needs [uv](https://docs.astral.sh/uv/).

    claude mcp add qupath -- uv run --directory /path/to/qupath-mcp/mcp server.py

Then ask the agent to call `qupath_launch`.

### Configuration

Set these as environment variables for the MCP server (`claude mcp add -e NAME=value ...`).

| Variable | Default | Meaning |
|---|---|---|
| `QUPATH_APP` | `/Applications/QuPath-0.7.0-arm64.app/Contents/MacOS/QuPath-0.7.0-arm64` | QuPath launcher to start. Set it for other platforms or installs. |
| `QUPATH_DRIVER_PORT` | `51515` | Port for the endpoint. |
| `QUPATH_DRIVER_LOG` | `/tmp/qupath-driver.log` | File that receives QuPath's stdout and stderr. |
| `QUPATH_FOREGROUND` | unset | Any value except `0` or `false` lets QuPath take focus on macOS. |

## Tools

| Tool | What it does | When to use it |
|---|---|---|
| `qupath_launch(timeout_s=60, foreground)` | Starts QuPath with the endpoint and waits until it answers. Does nothing if it is already running. | First call of a session. |
| `qupath_run_groovy(code, timeout_s=60)` | Runs Groovy and returns `ok`, `result`, captured `println` output and any error. | Anything the dedicated tools don't cover. |
| `qupath_menu(path)` | Fires a menu item such as `File>Open...`, waits about a second, returns the showing windows as JSON. | Opening a dialog from the menu bar. |
| `qupath_click(text, window)` | Clicks the button or checkbox whose text is exactly `text`, then returns the showing windows. | Driving a dialog. |
| `qupath_type(text, window, field=0)` | Sets the text of the `field`-th text field (0-based). | Filling in a dialog. |
| `qupath_describe(window)` | Text outline of a window's controls. With no `window`, it describes every non-main window, and for the main window gives the image path, downsample, centre, z/t and annotation count. | Reading a dialog or the viewer state. |
| `qupath_view(x, y, downsample, z, t)` | Centres the active viewer on a pixel at a zoom level, optionally at plane `z` and timepoint `t`. | Moving the viewer. |
| `qupath_windows()` | Lists showing windows as JSON (title, position, size, focused). | Finding a window title. |
| `qupath_screenshot(window)` | PNG of a window, returned inline. | Checking what the screen shows. The macOS menu bar is not captured. |
| `qupath_log(lines=100)` | Tail of `QUPATH_DRIVER_LOG`. | After a failed launch or an error. |
| `qupath_quit()` | Quits QuPath. | End of a session. |

`window` arguments are a substring of the window title. For `qupath_click` and `qupath_type`, omitting it targets the focused or topmost non-main window, or the main window if none is open. `qupath_screenshot` defaults to the main window.

## Use without MCP

Start QuPath with the port set. `-D` accepts `-Dkey=value` or `-D key=value`.

    QuPath-0.7.0-arm64 -q -Dqupath.driver.port=51515

| Request | Returns |
|---|---|
| `POST /groovy` with the code as the body; `?timeout=<seconds>`, default 60 | JSON `{ok, result, output, error}` |
| `GET /windows` | JSON array of showing windows |
| `GET /snapshot`; `?window=<title substring>`, default the main window | PNG, or 404 if no window matches |

    curl -X POST localhost:51515/groovy --data 'println qupath.getVersion(); 1+1'
    curl 'localhost:51515/snapshot?window=Licenses' -o window.png

QuPath's bundled runtime has no `jdk.httpserver`, so the extension serves HTTP with a small `ServerSocket` loop.

### Script mode

`-Dqupath.driver.script=/path/x.groovy` runs the file once the main window is showing, after a 3 second delay. Add `-Dqupath.driver.exit=true` to quit when the script ends, and `-Dqupath.driver.out=/path/out` to choose where `snap` and `snapMain` write PNGs (default `/tmp`). The port and script properties can be combined.

Scripts and `/groovy` code have the same bindings:

| Binding | Does |
|---|---|
| `qupath` | The `QuPathGUI` instance |
| `fx { ... }` | Runs a closure on the JavaFX thread and waits for it (60 s limit) |
| `snap(name)`, `snapMain(name)` | Write PNGs of every showing window, or of the main window, to the output directory |
| `waitFor(titleSubstring, seconds)` | Waits for a window and returns it, or throws on timeout |
| `window(titleSubstring)` | The matching window; `null` means the focused or topmost non-main one, else the main one |
| `lookup(window, text)` | The labeled node whose text equals `text` |
| `click(node)` | Fires a button; returns immediately |
| `findMenuItem("Menu>Item")`, `fire(menuItem)` | Finds a menu item by path and fires it; `fire` returns immediately |
| `typeInto(window, text[, fieldIndex])` | Sets a text field's text |
| `describe(window)` | Text outline of the window's controls (tables, lists and trees show their first 20 rows) |
| `view(x, y, downsample[, z, t])` | Centres the viewer |
| `quit()` | Exits QuPath |

In `/groovy`, `println` output is captured and returned in `output`.

## Traps

- **Modal dialogs.** A handler that calls `showAndWait` does not return until the dialog closes, so a script that fires it synchronously hangs. `click` and `fire` queue the action and return; follow them with `waitFor` or a short sleep. `qupath_menu` and `qupath_click` do this for you.
- **JavaFX thread.** Groovy runs on a background thread. Wrap your own access to the GUI in `fx { ... }`. The helpers above do this themselves.
- **Zoom before centre.** Changing the downsample factor moves the viewer's centre, so set the zoom first. `view` and `qupath_view` do this.

## Security

The endpoint runs arbitrary Groovy as your user.

- It is off unless `qupath.driver.port` (or `qupath.driver.script`) is set.
- It listens on 127.0.0.1 only.
- It rejects any request that carries an `Origin` header or a `Host` other than `127.0.0.1` or `localhost`, so a web page in your browser cannot reach it, including through DNS rebinding.
- Any local process can still call it.

Use it for testing only. Never ship the jar to end users.

## Limits

- Tested with QuPath 0.7.0 on macOS arm64 only. On other platforms `qupath_launch` starts `QUPATH_APP` directly, but that path has not been tried.
- The `mcp` Python package is pinned below 2 (`mcp>=1.2,<2`) because 2.x renamed `FastMCP`, which `server.py` imports. The server needs Python 3.10 or later.

## Building

The build uses the QuPath extension conventions Gradle plugin, which `settings.gradle.kts` pulls in. The target QuPath version is set there (`qupath { version = "0.7.0" }`), and the foojay resolver downloads a matching JDK automatically.

    ./gradlew shadowJar                                  # extension jar in build/libs
    uv run --directory mcp python -c "import server"     # checks the MCP server imports

The extension sources are in `src/main/java`. `GuiDriver` holds the helpers and `DriverServer` the HTTP handling.

## Licence

Apache-2.0. See [LICENSE](LICENSE).
