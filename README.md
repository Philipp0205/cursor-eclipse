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
- Agent, Plan, and Ask modes plus model selection when advertised by Cursor
- Tool progress and explicit permission choices
- Workspace-confined ACP reads/writes and IDE-managed terminal commands
- Dirty editor contents included in reads; writes preserve local history and run builders
- Active editor, text selection, problem markers, selected resources, and explicit file attachments
- Cursor questions, plan approval, todos, subagent, and generated-image notifications
- Project/user `.cursor/mcp.json` discovery and ACP session configuration
- Resumable sessions and multiple parallel agent views, with optional isolated Git worktrees
- An agents view over open chats, existing Cursor CLI chats, and the account's cloud agents
- Eclipse Git Staging review, embedded app preview, Cursor cloud dashboard, and `&` cloud handoff

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

1. Open **Window → Perspective → Open Perspective → Other… → Cursor**. The
   Cursor Agents view is placed on the left and the chat on the right.
2. Type a prompt and press Enter; the agent starts automatically. Shift+Enter
   inserts a newline and Alt+Up/Down walks prompt history.
3. Choose Agent, Plan, or Ask mode in the dropdown once connected.
4. Chat. If the agent wants to run a tool, Eclipse presents the choices supplied
   by Cursor. **Send** becomes **Stop** while a turn is running.
5. The view toolbar starts a new session; the view menu has Connect, Disconnect,
   Resume, Review changes, Open app preview, Cloud Agents, and Preferences.

The **Cursor Agents** view lists three groups and reloads them every minute
while it is visible, or on demand with its refresh button:

- **Open in Eclipse** — the chat views of this workbench, grouped by working
  folder, with each session's live state. Double-click one to bring it forward.
- **Local chats** — the chats the Cursor CLI already stored under
  `~/.cursor/chats`, grouped by the folder they ran in. Double-click one to open
  a chat view on that folder and replay the conversation through ACP
  `session/load`. Chats started in the Cursor desktop app are kept elsewhere and
  do not appear here.
- **Cloud agents** — the Cloud Agents of the signed-in account, newest first.
  Double-click one to open it on cursor.com. This list needs an API key from
  [Cursor Dashboard → API Keys](https://cursor.com/dashboard/api) in
  **Preferences → Cursor** or in `CURSOR_API_KEY`, because `agent login` alone
  is not a REST credential.

Use the view's toolbar button (or **New Parallel Cursor Agent** in the chat
toolbar) to open another independent agent. Eclipse asks whether its edits
should be isolated in a Git worktree. Each agent runs in its own normal Eclipse
view instance, so views can be tabbed or split using standard workbench
controls.

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

## Install

### From the hosted update site

```
https://philipp0205.github.io/cursor-eclipse/p2/
```

**Help → Install New Software…**, paste that URL, select **Cursor**, finish, and
restart.

### From a local build

```sh
mvn verify
```

Then **Help → Install New Software… → Add… → Local…** and choose
`releng/com.cursor.eclipse.repository/target/repository`.

`test/serve-update-site.sh [port]` builds the site and serves it at
`http://localhost:8080/p2/` instead. Eclipse redirects `http://` update sites to
`https://` since CVE-2021-41033, so add `-Dp2.httpRule=allow` to `eclipse.ini`
before using the URL form. Installing from a local folder needs no such flag.

### From a CI build

Every workflow run uploads a `p2-update-site` artifact. Download it, unzip, and
install from the folder.

## Publishing

GitHub Pages serves this repository's default branch from the root, so the
update site is the committed `p2/` directory rather than a Pages artifact. No
Pages setting needs changing; the site appears as soon as `p2/` reaches `main`.

`.github/workflows/p2-site.yml` builds and verifies every push and pull request,
including a p2 director run that proves the feature actually resolves against a
real Eclipse release. On pushes to `main` and on `v*` tags it then rebuilds with
the `publish-site` profile, which mirrors the already published `p2/` together
with the new build into `target/merged-site`, and commits the result back to
`p2/`.

Merging rather than replacing matters: p2 clients cache repository metadata and
keep requesting the exact version they first resolved, so removing an old
version breaks their install instead of upgrading it. The trade-off is that
`p2/` grows by one feature and one bundle jar per published build; publish only
on tags if that becomes unwelcome.

The publish commit touches only `p2/`, which the workflow's `paths-ignore`
excludes, so publishing never retriggers the workflow.
