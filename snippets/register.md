=== "Claude Code"

    ```bash
    claude mcp add -s user --transport http qupath http://127.0.0.1:51515/mcp
    ```

    `-s user` makes the server available in every project; without it, only in the current one.
    [Claude Code MCP docs](https://code.claude.com/docs/en/mcp)

=== "Codex"

    In `~/.codex/config.toml`:

    ```toml
    [mcp_servers.qupath]
    url = "http://127.0.0.1:51515/mcp"
    ```

    [Codex MCP docs](https://developers.openai.com/codex/mcp)

=== "Cursor"

    In `~/.cursor/mcp.json`, or `.cursor/mcp.json` in a project:

    ```json
    { "mcpServers": { "qupath": { "url": "http://127.0.0.1:51515/mcp" } } }
    ```

    [Cursor MCP docs](https://cursor.com/docs/context/mcp)

=== "VS Code"

    In `.vscode/mcp.json`, or your user configuration (**MCP: Open User Configuration**). The top-level key is
    `servers`:

    ```json
    { "servers": { "qupath": { "type": "http", "url": "http://127.0.0.1:51515/mcp" } } }
    ```

    [VS Code MCP docs](https://code.visualstudio.com/docs/copilot/customization/mcp-servers)

=== "Gemini CLI"

    In `~/.gemini/settings.json`. Use `httpUrl`; Gemini CLI reads `url` as the older SSE transport:

    ```json
    { "mcpServers": { "qupath": { "httpUrl": "http://127.0.0.1:51515/mcp" } } }
    ```

    [Gemini CLI MCP docs](https://github.com/google-gemini/gemini-cli/blob/main/docs/tools/mcp-server.md)

=== "Claude Desktop"

    Claude Desktop can't connect to this server directly: its config file takes only local commands, and its
    custom connectors need a server reachable from the internet. A bridge such as the third-party
    [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) package can run as a local command and forward to the
    URL; this is untested.

    [Claude Desktop local servers](https://modelcontextprotocol.io/docs/develop/connect-local-servers)
