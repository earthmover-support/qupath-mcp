# Use without MCP

Using the MCP tools? See [Tools](tools.md). This page is for test scripts, CI and any client that is not an MCP client.

## Start QuPath

The extension reads these Java system properties. Pass them as `-D` options on the QuPath command line.

| Property | Meaning |
|---|---|
| `qupath.driver.port=<port>` | Starts the endpoint on 127.0.0.1 at this port. |
| `qupath.driver.script=<file>` | Runs this Groovy file once ([script mode](#script-mode)). |
| `qupath.driver.exit=true` | Quits QuPath when the script ends, or fails. Needs `qupath.driver.script`. |
| `qupath.driver.out=<dir>` | Directory for the PNGs that `snap` and `snapMain` write. Default `/tmp`. |

The endpoint and the script start about 3 s after the main window shows. The port and script properties can be combined.

```bash
/Applications/QuPath-0.7.0-arm64.app/Contents/MacOS/QuPath-0.7.0-arm64 -Dqupath.driver.port=51515
```

QuPath accepts `-q` (`--quiet`) to skip setup dialogs, update checks and messages.

## HTTP endpoint

| Request | Returns |
|---|---|
| `POST /groovy` with the code as the body; `?timeout=<seconds>`, default 60 | JSON `{ok, result, output, error}` |
| `GET /windows` | JSON array of open windows |
| `GET /snapshot`; `?window=<title substring>`, default the main window | PNG, or 404 if no window matches |

```bash
curl -X POST localhost:51515/groovy --data 'println qupath.getVersion(); 1+1'
curl 'localhost:51515/snapshot?window=Licenses' -o window.png
```

Requests that carry an `Origin` header or a foreign `Host` get a 403; see [Security](security.md).

## Script mode

With `qupath.driver.script` set, the file runs once the main window is showing. Its `println` goes to QuPath's standard output, not to a response. A failing script is only logged; QuPath keeps running unless `qupath.driver.exit=true` is set.

## Bindings

Scripts and `/groovy` code have the same bindings:

| Binding | Does |
|---|---|
| `qupath` | The `QuPathGUI` instance |
| `fx { ... }` | Runs a closure on the JavaFX thread and waits for it (60 s limit) |
| `snap(name)`, `snapMain(name)` | Write PNGs of every open window, or of the main window, to the output directory |
| `waitFor(titleSubstring, seconds)` | Waits for a window and returns it, or throws on timeout |
| `window(titleSubstring)` | The matching window; `null` means the focused non-main one, else the most recently opened one, else the main one |
| `lookup(window, text)` | The labeled node whose text equals `text` |
| `click(node)` | Fires a button; returns immediately. See [Traps](traps.md). |
| `findMenuItem("Menu>Item")`, `fire(menuItem)` | Finds a menu item by path and fires it; `fire` returns immediately. See [Traps](traps.md). |
| `typeInto(window, text[, fieldIndex])` | Sets a text field's text |
| `describe(window)` | Text outline of the window's controls (tables, lists and trees show their first 20 rows) |
| `view(x, y, downsample)` or `view(x, y, downsample, z, t)` | Centres the viewer; pass `null` for `z` or `t` to leave it unchanged |
| `quit()` | Exits QuPath |

In `/groovy`, `println` output is captured and returned in `output`.
