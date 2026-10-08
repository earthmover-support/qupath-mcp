# Contributing

## Building

### Build the extension

The build uses the QuPath extension conventions Gradle plugin, which `settings.gradle.kts` pulls in. The target QuPath version is set there (`qupath { version = "0.7.0" }`), and the foojay resolver downloads a matching JDK automatically.

```bash
./gradlew shadowJar    # extension jar in build/libs
```

### Check the MCP server

```bash
uv run --directory mcp python -c "import server"
```

### Build the docs

Install [pixi](https://pixi.sh), then:

```bash
pixi run -e docs docs          # serve locally
pixi run -e docs docs-build    # write site/
```

## Limits

- Tested with QuPath 0.7.0 on macOS arm64 only. On other platforms `qupath_launch` starts `QUPATH_APP` directly, but that path has not been tried.
- The `mcp` Python package is pinned below 2 (`mcp>=1.2,<2`). mcp 2.x moved FastMCP out of `mcp.server.fastmcp` (importing it raises `ModuleNotFoundError`), so the pin stays until `server.py` is ported. The server needs Python 3.10 or later.

## Layout

- `src/main/java/io/earthmover/qupath/driver/`: the extension. `GuiDriver` holds the helpers, `DriverServer` the HTTP handling, and `GuiDriverExtension` reads the `qupath.driver.*` properties and starts both.
- `mcp/server.py`: the MCP server.
- `docs/`: these pages, built with [Zensical](https://zensical.org) from `zensical.toml`.

## Implementation notes

QuPath's bundled runtime has no `jdk.httpserver`, so the extension serves HTTP with a small `ServerSocket` loop.
