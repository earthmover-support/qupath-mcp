# qupath-mcp

Drive QuPath's GUI from an AI agent. QuPath's own scripting reaches the data model; an agent also needs to open menus, read and answer dialogs and see the screen. This QuPath 0.7 extension runs an MCP server inside QuPath that exposes those as tools to Claude Code or any other MCP client, without deadlocking on modal dialogs.

## Quick start

1. In QuPath, open **Extensions ▸ Manage extensions ▸ Manage extension catalogs**, add
   `https://github.com/earthmover-support/qupath-mcp`, then install **QuPath MCP** from the extension manager.
2. Register the server with your client. For Claude Code:

```bash
claude mcp add -s user --transport http qupath http://127.0.0.1:51515/mcp
```

`-s user` makes it available in every project; without it the server is registered for the current project only. Other clients take the URL `http://127.0.0.1:51515/mcp`; [Install](https://earthmover-support.github.io/qupath-mcp/install/) has the config for Codex, Cursor, VS Code and Gemini CLI. QuPath must be running before the client connects. QuPath logs `MCP server on http://127.0.0.1:51515/mcp` when the server starts, and **Extensions ▸ MCP server** turns it off and on.

Tested on macOS arm64 with QuPath 0.7.0 only.

**The MCP server lets the connected agent run any code in QuPath, as you, with access to your files.** Give it only as much trust as you give your coding agent. It is on whenever the jar is installed and any program on this computer can reach it (web pages cannot), so don't install it on a shared machine, and turn it off with Extensions ▸ MCP server when you're not using it. See [Security](https://earthmover-support.github.io/qupath-mcp/security/).

Documentation: <https://earthmover-support.github.io/qupath-mcp/>

## Licence

Apache-2.0. See [LICENSE](LICENSE).
