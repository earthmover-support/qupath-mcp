# Install

You need QuPath 0.7 and an MCP client such as Claude Code.

!!! warning
    The MCP server lets the connected agent run any code in QuPath, as you, with access to your files. Give it only as much trust as you give your coding agent. It is on whenever the jar is installed. See [Security](security.md).

## Install the extension

Install it from QuPath's extension manager, which also offers updates when a new version is released:

1. In QuPath, choose **Extensions ▸ Manage extensions**, then **Manage extension catalogs**.
2. Paste `https://github.com/earthmover-support/qupath-mcp` into **Catalog URL** and click **Add**. Close the catalog
   window.
3. In the extension manager, find **QuPath MCP** and click its install button (the tooltip says *Install extension*),
   then **Install**.

To build it yourself instead, run `./gradlew shadowJar` in a clone of the repository and drag
`build/libs/qupath-gui-driver-<version>-all.jar` onto QuPath.

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

In **Extensions ▸ Manage extensions**, remove **QuPath MCP** (its tooltip says *Remove extension*), and remove the `qupath` entry from your client (`claude mcp remove -s user qupath` in Claude Code).
