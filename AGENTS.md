# Agent instructions

## Name the software that acts

Three pieces of software appear in this project, and docs, comments, tool descriptions and messages must say which one does each thing:

- **QuPath** is the host application. It opens images and dialogs, runs Groovy, and shows notifications.
- **qupath-mcp** is this extension. It runs the MCP server, marks the controls the agent acts on, and keeps, warns about and deletes the action log.
- **The agent** is the MCP client that calls the tools.

Write "qupath-mcp shows a notification in QuPath", not "QuPath warns you", when the extension decides to warn. QuPath is the subject only of what QuPath itself does.
