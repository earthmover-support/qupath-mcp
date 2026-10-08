# Contributing

## Building

### Build the extension

The build uses the QuPath extension conventions Gradle plugin, which `settings.gradle.kts` pulls in. The target QuPath version is set there (`qupath { version = "0.7.0" }`), and the foojay resolver downloads a matching JDK automatically.

```bash
./gradlew shadowJar    # extension jar in build/libs
```

### Build the docs

Install [pixi](https://pixi.sh), then:

```bash
pixi run -e docs docs          # serve locally
pixi run -e docs docs-build    # write site/
```

## Releasing

1. Set the version in `build.gradle.kts` and push.
2. Push a tag named `v<version>`, for example `v0.1.0`. The release workflow builds `./gradlew shadowJar` and attaches `qupath-gui-driver-<version>-all.jar` to a GitHub release for the tag.
3. Once the release has its jar, add it to `catalog.json`, newest first, and push. QuPath's extension manager offers it as an update from then on. Do this last: the catalog must never point at a jar that doesn't exist yet.

QuPath reads `catalog.json` from `raw.githubusercontent.com`, which caches it for a minute or more after a push, and keeps the catalog it fetched until QuPath restarts.

## Limits

- Tested with QuPath 0.7.0 on macOS arm64 only.

## Layout

- `src/main/java/io/earthmover/qupath/driver/`: the extension. `GuiDriver` holds the helpers, `Mcp` the tool definitions, `DriverServer` the HTTP server, `Indicator` the marks shown to the person watching, and `GuiDriverExtension` the preferences, the menu items and the `qupath.driver.*` properties.
- `docs/`: these pages, built with [Zensical](https://zensical.org) from `zensical.toml`.

## Design rules

1. **Context efficiency.** Every result is read by the agent, so return text that is short and complete: one line per window, paged rows with a `rows X–Y of N` line, images capped in size. Add a limit or a format option before returning more.
2. **Complete interaction.** Anything an agent needs in the GUI should be possible through a dedicated tool. `qupath_run_groovy` is the escape hatch, not the plan: when a task needs it repeatedly, add a small tool for it. Tools act through `fx` or `Platform.runLater` so a modal dialog never blocks them.

## Test interfaces

Besides `/mcp`, the server answers `POST /groovy` (run Groovy, returns `{ok, result, output, error}`), `GET /windows` and
`GET /snapshot?window=<title>`, and `-Dqupath.driver.script=<file>` runs a Groovy file once the main window shows. Automated
GUI tests and screenshot capture use these; agents should use the MCP tools.

## Implementation notes

The MCP server uses the [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk) 1.1.2 (its Streamable HTTP servlet transport and Jackson 3 JSON mapper) on embedded Jetty 11, bound to 127.0.0.1. The SDK's security validator checks `Origin` and `Host` for every request, not only `/mcp`.

The shadow jar bundles these libraries and relocates them under `io.earthmover.qupath.driver.shaded` so they cannot collide with QuPath or other extensions. SLF4J is not bundled; QuPath provides it. The SDK finds its JSON mapper through `ServiceLoader` on the thread context class loader, so `DriverServer` sets that to the extension's class loader while it starts.

`Indicator` draws into a pane it adds as the last child of each window's root (when the root is a `Pane`), unmanaged and mouse-transparent, with the id `qupath-mcp-overlay`. `GuiDriver` skips that pane when it describes a window or looks up a control, and hides it while taking a screenshot. Marks are scheduled on the FX thread after the action, so only paced mode delays a tool call. The badge and the log's header take the client's name from the MCP `initialize` request.

Tools return failures as `isError` results, not protocol errors. The SDK's streamable transport is session-based: a client must `initialize` first, and a `GET /mcp` without a session is answered with a 400, not a 405.

The SDK has no option for the detail of its error replies, which embed the exception text. A servlet filter on `/mcp` replaces any reply with status 400 or above by a short JSON-RPC error with the same status and logs the original at debug level; Jetty's own error pages have `showStacks` and `showServlet` off. `/groovy` is outside that filter, because its error is the script's own. The server stops gracefully (Jetty's `StatisticsHandler` and a 5 s stop timeout) so the request that turns the server off can still reply.
