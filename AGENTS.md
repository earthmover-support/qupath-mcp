# Agent instructions

qupath-mcp lets an AI agent drive QuPath and run any code in it, as the person using it, so a change here decides what an agent can do to someone's computer and data. Read [docs/contributing.md](docs/contributing.md) and [docs/security.md](docs/security.md) first. No CI job compiles the Java: run `./gradlew shadowJar`, and check behaviour in a running QuPath (`POST /groovy`) before calling it done.

## Name the software that acts

- **QuPath** is the host application: it opens images and dialogs, runs Groovy, shows notifications.
- **qupath-mcp** is this extension: it runs the MCP server, marks the controls the agent acts on, keeps the action log.
- **The agent** is the MCP client calling the tools.

Docs, comments, tool descriptions and messages say which one acts: "qupath-mcp shows a notification in QuPath", not "QuPath warns you".

## Keep the user and their computer safe

The agent already has everything `qupath_run_groovy` gives it. Don't widen that, leak what passes through it, or let the agent do lasting harm without the person knowing.

- Every endpoint binds 127.0.0.1 and passes the `Origin`/`Host` check. A new endpoint, or a new file qupath-mcp writes, gets a line in `docs/security.md`.
- Password-field text never appears in results, on-screen marks or logs.
- Files qupath-mcp writes go in a documented folder, readable by the user only.
- Delete nothing by default; delete only files qupath-mcp named, only when the user turned it on, and say so.
- Use notifications, never modal dialogs: a modal dialog stalls the agent and the person.
- Agent-facing text never asks the agent to delete, overwrite or switch off a safeguard; it asks it to tell the user.
- Switching a safeguard off is itself recorded, and a failure in a supporting feature (the log, the marks) never fails the agent's action.

## Few, composable tools; every token counts

The server instructions, each tool's description and schema (about 200 tokens a tool), and every result are read in every session.

- Tools are generic primitives that act on any window or control by name, so together they reach every dialog, other extensions' included. Don't add a tool for one dialog or workflow; when the primitives can't do something, or a task keeps needing Groovy, prefer a parameter on an existing tool. Measure with `tools/list` before and after.
- Make them discoverable and composable: a description names the tool to use with it, results hand back what the next call takes (`qupath_describe`'s `#n` is what `qupath_set` and `qupath_select` accept), an action result says what it opened, and an error lists what exists (`No button 'X' in 'Y'. Buttons: …`).
- GUI tools return without waiting for event handlers, so a modal dialog cannot block them.
- Text before images; cap and page results (contributing rule 1).
- A description holds what the agent needs to call the tool; explanation for people goes in `docs/`. Tell the agent about a condition once, when it arises, not in every description.

## Write the docs for people

- Each setting is documented with its menu path, default and off value, and its preference tooltip agrees.
- Defaults are the safe choice; say why when it isn't obvious.
- When behaviour or a menu path changes, update every mention: docs, README, tooltips, tool descriptions.
- State only what you checked in a running QuPath; numbers come from the code or a measurement.
