# Cursor for Eclipse

Eclipse plugin that runs [Cursor Agent](https://cursor.com/docs/cli/using) inside the IDE over the [Agent Client Protocol](https://agentclientprotocol.com/) (ACP).

## What ACP is

ACP is a small JSON-RPC protocol that sits between an **editor** and a **coding agent**, the same idea as LSP but for agents instead of language servers.

- The **client** is Eclipse (this plugin): chat UI, permission dialogs, workspace files.
- The **agent** is Cursor CLI, started as `agent acp`. It talks over stdin/stdout with one JSON object per line.
- A session looks like: `initialize` → `authenticate` → `session/new` → `session/prompt`, while the agent streams `session/update` chunks and may ask `session/request_permission` before running a tool.

Cursor, OpenCode, Gemini CLI, and others all speak ACP. This plugin does **not** depend on an OpenCode Eclipse plugin; it talks to Cursor CLI directly.

## Features

- Streaming Cursor Agent chat with new sessions in the same CLI process
- Agent, Plan, and Ask modes when advertised by Cursor
- Tool progress and explicit permission choices
- Workspace-confined ACP reads/writes through Eclipse resources
- Dirty editor contents included in reads; writes preserve local history and run builders
- Active editor, text selection, and selected Project Explorer files attached to prompts
- Cursor questions, plan approval, todos, subagent, and generated-image notifications
- Project/user `.cursor/mcp.json` discovery through Cursor CLI

## Prerequisites

- Eclipse 2025-09 (4.37) or later, Java 21
- [Cursor CLI](https://cursor.com/docs/cli/using) on `PATH` (or `~/.local/bin/agent`)
- `agent login` (or an API key in Preferences → Cursor)
- Linux only: WebKitGTK (for example `libwebkit2gtk-4.1-0`) for the SWT Browser
  used by the conversation view

## Architecture

- `com.cursor.eclipse.acp` is a plain Java ACP client: newline-delimited
  JSON-RPC over the agent's stdio. Inbound agent requests are dispatched off the
  reader thread so a permission dialog never stalls the stream.
- `ChatView` owns widgets only. `ChatController` owns the conversation and runs
  every agent call on a named worker thread, posting updates back to the SWT
  thread and coalescing streamed text on a timer.
- The transcript is one SWT `Browser` updated block by block, which keeps long
  conversations cheap on GTK.
- Workbench state (active editor, selection, project) is read on the SWT thread
  before any worker starts.

## Build

```bash
mvn -f pom.xml verify
```

Requires Maven 3.9+. The ACP client is tested without Eclipse. The plugin/feature/p2 site is built with Tycho.

Install from the generated p2 repository:

`releng/com.cursor.eclipse.repository/target/repository`

In Eclipse: **Help → Install New Software… → Add…** and choose that folder.

## Use

1. **Window → Show View → Other… → Cursor → Cursor**
2. Type a prompt and press Enter; the agent starts automatically. Shift+Enter
   inserts a newline and Alt+Up/Down walks prompt history.
3. Choose Agent, Plan, or Ask mode in the dropdown once connected.
4. Chat. If the agent wants to run a tool, Eclipse presents the choices supplied
   by Cursor. **Send** becomes **Stop** while a turn is running.
5. The view toolbar starts a new session; the view menu has Connect, Disconnect,
   and Preferences.

File access requested over ACP is restricted to the current Eclipse workspace.
The agent may still use its own filesystem tools according to Cursor's permission
system, so review permission prompts before approving them.

## Manual testing without a Cursor subscription

`test/fake-acp-agent.py` implements a small ACP agent that exercises streaming
markdown, thinking, tool status, permission prompts, plans, todos, a workspace
write, and cancellation.

Point **Window → Preferences → Cursor → Agent binary** at that file with
arguments `acp`, then send `Run the integration demo`. Sending a prompt
containing `slow` streams until you press **Stop**.

## Distribution

`mvn verify` creates both a directory and ZIP p2 repository under
`releng/com.cursor.eclipse.repository/target`. Tags matching `v*` publish that
repository to GitHub Pages through `.github/workflows/release.yml`.
