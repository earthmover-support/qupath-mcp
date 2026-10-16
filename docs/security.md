# Security

The MCP server lets the connected agent run any code in QuPath, as you, with access to your files. Give it only as much trust as you give your coding agent.

- **It is on whenever the jar is installed.** Installing the jar is the opt-in; qupath-mcp starts the server when QuPath launches.
- **Any program on this computer can reach it.** It listens on the loopback address 127.0.0.1 only, but any local process can send it code to run. Don't install the jar on a shared machine.
- **Requests from web pages are rejected.** The server returns an error for any request whose `Origin` is not `http://127.0.0.1:<port>` or `http://localhost:<port>`, or whose `Host` is not `127.0.0.1` or `localhost` with the port. A page in your browser cannot reach it, even through DNS rebinding, where a page points its own hostname at 127.0.0.1 to look local. The check applies to `/mcp` and to the plain HTTP endpoints. MCP clients and `curl` send no `Origin` header and pass.
- **Turn it off when you're not using it.** Untick **Extensions ▸ MCP server ▸ Enabled**, or remove the jar.
- **It keeps a log.** Every action the agent takes is appended to a file, by default in `mcp-logs` in QuPath's user directory, with the Groovy it ran, the text it got back (up to 4000 characters), and the project and image that were open. Treat that folder like the project data it describes. It is a record, not an audit trail: an agent that can run Groovy can turn it off or edit it. The **Log agent actions** preference controls it; see [Action log](action-log.md).
