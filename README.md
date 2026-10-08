# qupath-mcp

Drive QuPath's GUI from an AI agent or a test script. QuPath's own scripting reaches the data model; an agent also needs to open menus, read and answer dialogs and see the screen. This project adds a QuPath 0.7 extension that serves a local HTTP endpoint for that, and an MCP server that exposes it as tools for Claude Code or any other MCP client, without deadlocking on modal dialogs.

## Quick start

No prebuilt jar is published, so build it. You need [uv](https://docs.astral.sh/uv/) and Git.

```bash
git clone https://github.com/earthmover-support/qupath-mcp
cd qupath-mcp
./gradlew shadowJar
cp build/libs/qupath-gui-driver-*-all.jar /path/to/QuPath-user-directory/extensions/
claude mcp add qupath -- uv run --directory "$PWD/mcp" server.py
```

Restart QuPath, then ask the agent to call `qupath_launch`. Other MCP clients and environment variables are in [Install](https://earthmover-support.github.io/qupath-mcp/install/).

Tested on macOS arm64 with QuPath 0.7.0 only.

The endpoint runs arbitrary Groovy as your user. Use it for testing only; see [Security](https://earthmover-support.github.io/qupath-mcp/security/).

Documentation: <https://earthmover-support.github.io/qupath-mcp/>

## Licence

Apache-2.0. See [LICENSE](LICENSE).
