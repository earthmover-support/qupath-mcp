# Action log

QuPath keeps no record of what is run in it, so qupath-mcp keeps one: every action the agent takes is written to a file, so you can check a session afterwards or redo it.

## What it records

Every MCP tool call, `/groovy` request and start-up script is appended as one JSON object per line to `mcp-<date>-<time>.jsonl`. Each QuPath session gets one file, created on its first action and written whatever **Show agent actions** is set to. A tool call is the smallest action the agent takes (it asks for a click on a named control rather than moving a pointer), so the log is complete at that grain.

One line, from a test session:

```json
{"time":"2026-10-19T13:59:31.507169-04:00","source":"mcp","client":"Claude Code","tool":"qupath_set","args":{"control":"Sigma","value":2,"window":"Cell detection"},"window":"Cell detection","target":"set “Sigma” to 2","ok":true,"ms":1013,"result":"#5 TextField \"Sigma\" = \"2\"\n\nQuPath - tumor_091.tif — 1176x767\nWelcome — 460x675\nCell detection — 417x600\nBrightness & contrast — 311x705\n\nNo new window opened.","image":"OpenslideImageServer: file:/Users/ian/camelyon-demo/tumor_091.tif:1664367385000"}
```

- `time`: when the call started, ISO 8601 with the local offset.
- `source`: `mcp` for a tool call, `http` for a `/groovy` request, `script` for a `-Dqupath.driver.script` file.
- `client`: the client's title from its MCP `initialize` request, or its name when it gives no title; the caller's address for a `/groovy` request; absent for a start-up script.
- `tool` and `args`: the tool and its arguments verbatim, so the Groovy source for `qupath_run_groovy`. A start-up script is logged as `script` with its `path` only, not its source.
- `window` and `target`: the title of the window the action landed in, and what the driver did there in the words of the on-screen marks: `click “Apply”`, `set “Size:” to 7`, `select “Nucleus”`, `tick “Image 1”`, `File ▸ Open URI...`, `key Enter`, `type “…”`, `close “Measurements”`, `open slide.svs`. A Groovy run that acts several times lists each, separated by `; `. Absent for a call that only reads.
- `ok` and `ms`: whether it succeeded, and how long it took in milliseconds.
- `result`: the text the tool returned, cut at 4000 characters; an image is replaced by a placeholder giving its type and encoded length. A failed call has `error` instead.
- `project` and `image`: the paths of the project and image that were open, when there were any.

The log holds the Groovy the agent ran and the text it read back, which can include file paths and anything shown in a dialog. Treat the folder like the project data it describes. Text `qupath_set` puts in a password field is written as `••••••`, here and everywhere else qupath-mcp shows it; Groovy that enters or reads a password is logged as written. On macOS and Linux the extension creates the folder and its files readable by you only.

The log is a record of what the agent did, not a tamper-proof audit trail: an agent that can run Groovy can also turn logging off or edit the files. The call that turns logging off is still recorded.

## Size and cleanup

Most entries are under 1 KB. An entry that returns a long dialog outline can reach about 5 KB, since the returned text is cut at 4000 characters; the Groovy source of a `qupath_run_groovy` call is kept in full. A session of a few hundred tool calls writes roughly 0.2 to 2 MB.

Logs are kept until they add up to more than **Action log limit (MB)**, 1024 by default, which holds several hundred busy sessions. Over the limit, qupath-mcp does one of two things:

- **By default, it warns and deletes nothing.** It shows a notification in QuPath, and the agent's next successful tool call carries a one-line note asking it to tell you. Delete old logs yourself, raise the limit, or turn on deletion.
- **With Delete oldest action logs over the limit on, it deletes the oldest logs** until the rest fit under the limit, then shows a notification in QuPath saying how many it deleted and how much space that freed. The current session's log is never deleted.

Deletion is off by default because the logs are the record of past sessions; turn it on if you only need recent ones. The check runs when a session writes its first entry, and again if the folder preference changes, so a single long session can pass the limit until the next one starts. Only qupath-mcp's own logs, named like `mcp-20261016-143723.jsonl`, are counted or deleted; other files in the folder are left alone.

## Preferences

Under **Edit ▸ Preferences ▸ MCP server**:

| Preference | Default | |
|---|---|---|
| **Log agent actions** | on | Turns the log off. |
| **Action log folder** | empty | Where the logs go; empty means `mcp-logs` in QuPath's user directory. A change applies from the next action. |
| **Action log limit (MB)** | 1024 | Total size of the logs before qupath-mcp warns or deletes; 0 means no limit. |
| **Delete oldest action logs over the limit** | off | Over the limit, delete the oldest logs instead of warning. |

## Redoing a session

There is no replay command. An entry has what you need to redo the call by hand, and the `code` of a `qupath_run_groovy` entry can be sent back through that tool as it is. A start-up script can be rerun only while its file is unchanged.
