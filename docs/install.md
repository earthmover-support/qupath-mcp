# Install

You need QuPath 0.7 and an MCP client such as Claude Code.

!!! warning
    The MCP server lets the connected agent run any code in QuPath, as you, with access to your files. Give it only as much trust as you give your coding agent. It is on whenever the jar is installed. See [Security](security.md).

## Platforms

Tested on macOS arm64 with QuPath 0.7.0 only.

## Install the extension

1. Download `qupath-gui-driver-<version>-all.jar` from the [releases page](https://github.com/earthmover-support/qupath-mcp/releases). To build it instead, run `./gradlew shadowJar` in a clone of the repository; the jar is in `build/libs/` and Gradle downloads the JDK it needs.
2. Drag the jar onto QuPath, or copy it into the `extensions/` directory inside QuPath's user directory (Preferences ▸ QuPath user directory).
3. Restart QuPath. **Extensions ▸ Manage extensions** lists what is installed.

The MCP server starts with QuPath and logs `MCP server on http://127.0.0.1:51515/mcp`. Untick **Extensions ▸ MCP server** to stop it; the choice persists across restarts. The port is the *MCP server port* preference (default 51515) and applies the next time the server starts.

## Register the server

Every client connects to the same URL, `http://127.0.0.1:51515/mcp`, over MCP's Streamable HTTP transport.

--8<-- "register.md"

## Start QuPath before the client

The client connects to the running QuPath, so QuPath must be open first. Open it yourself, or an agent with a shell can start it on macOS without taking focus from your windows:

```bash
open -g -a QuPath-0.7.0-arm64
```

## Check it works

Ask the agent to call `qupath_windows`, or check the server directly:

```bash
curl localhost:51515/windows
```

## Uninstall

Delete the jar from the `extensions/` directory, and remove the `qupath` entry from your client (`claude mcp remove -s user qupath` in Claude Code).
