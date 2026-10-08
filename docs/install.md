# Install

You need a terminal, [uv](https://docs.astral.sh/uv/), Git and QuPath 0.7.

!!! warning
    The endpoint runs arbitrary Groovy as your user. Use it for testing only. See [Security](security.md).

## Platforms

Tested on macOS arm64 only. On macOS, `qupath_launch` opens the app bundle in the background with `open -g -n`. That starts a new instance, even if QuPath is already open without the endpoint. On other platforms it runs `QUPATH_APP` directly, which has not been tried. The `/tmp` defaults below assume macOS or Linux.

## Build and install the extension

No prebuilt jar is published, so you build it. Gradle downloads the JDK it needs.

```bash
git clone https://github.com/earthmover-support/qupath-mcp
cd qupath-mcp
./gradlew shadowJar
```

Copy `build/libs/qupath-gui-driver-<version>-all.jar` into the `extensions/` directory inside QuPath's user directory (Preferences ▸ QuPath user directory), then restart QuPath. *Extensions > Manage extensions* lists what is installed.

## Register the MCP server

### Claude Code

```bash
claude mcp add qupath -- uv run --directory /path/to/qupath-mcp/mcp server.py
```

To set [environment variables](#configuration), add `-e NAME=value` before the `--`.

### Other MCP clients

Run `uv run --directory /path/to/qupath-mcp/mcp server.py` as a stdio server. In clients that use an `mcpServers` JSON file:

```json
{
  "mcpServers": {
    "qupath": {
      "command": "uv",
      "args": ["run", "--directory", "/path/to/qupath-mcp/mcp", "server.py"],
      "env": { "QUPATH_DRIVER_PORT": "51515" }
    }
  }
}
```

## Check it works

Ask the agent to call `qupath_launch`. It returns `started, listening on port 51515; log: …`. You can also check the endpoint directly:

```bash
curl localhost:51515/windows
```

If the launch fails, call `qupath_log` or read `QUPATH_DRIVER_LOG`.

## Configuration

Set these as environment variables for the MCP server.

| Variable | Default | Meaning |
|---|---|---|
| `QUPATH_APP` | `/Applications/QuPath-0.7.0-arm64.app/Contents/MacOS/QuPath-0.7.0-arm64` | QuPath launcher to start. Set it for other platforms or installs. |
| `QUPATH_DRIVER_PORT` | `51515` | Port for the endpoint. |
| `QUPATH_DRIVER_LOG` | `/tmp/qupath-driver.log` | File that receives QuPath's stdout and stderr. |
| `QUPATH_FOREGROUND` | unset | Any value except `0` or `false` lets QuPath take focus on macOS. |

## Uninstall

Delete the jar from the `extensions/` directory and run `claude mcp remove qupath`.
