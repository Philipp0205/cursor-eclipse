# Plan: Cursor for Eclipse

This repo will ship an Eclipse plugin that runs Cursor’s agent inside the IDE, the same way an OpenCode Eclipse plugin would run `opencode acp`.

**Status:** plan only. No plugin code until this plan is agreed.

`https://github.com/Philipp0205/eclipse-opencode` is not publicly cloneable from this environment (404 / not found). The plan therefore uses:

- OpenCode’s documented ACP integration (`opencode acp` over stdio JSON-RPC)
- Cursor CLI ACP (`agent acp`) and Cursor-specific extension methods
- Eclipse Agents (`eclipse-agents/eclipse-agents` and `Philipp0205/eclipse-agents`) as the Eclipse-side ACP client / Tycho layout reference
- GitHub Copilot for Eclipse only as a *non-goal* for v1 (it is a full proprietary-style plugin, not an ACP client)

If `eclipse-opencode` can be made readable, the first implementation step is to copy its module layout, chat view, and process lifecycle and swap the agent binary.

---

## Goal

Give Eclipse users a **Cursor Agent chat** that:

1. Uses the user’s Cursor account / API key (same models and agent harness as Cursor CLI)
2. Operates on the **Eclipse workspace** (open editors, dirty buffers, markers)
3. Streams replies, tool calls, and file edits back into Eclipse
4. Installs like a normal Eclipse feature (Tycho + p2 update site)

v1 is **agent chat + edits**, not inline Copilot-style completions.

---

## How it works

```text
  Eclipse plugin (ACP client)              Cursor CLI (ACP server)
  ---------------------------              -----------------------
  Chat view / prefs / jobs
           |  spawn Process
           |  stdin/stdout NDJSON JSON-RPC
           v
  initialize -> authenticate("cursor_login")
  session/new (cwd = workspace / project)
  session/prompt  <----------------------  session/update (stream)
  session/request_permission  -----------> allow-once | allow-always | reject
  fs/readTextFile, fs/writeTextFile  <---  editor buffer I/O
  cursor/ask_question, cursor/create_plan  (Cursor extensions)
```

Same pattern as OpenCode in Zed/JetBrains/Neovim: the IDE is the ACP **client**, the CLI is the **agent**. Cursor’s command is:

```bash
agent acp
# optional: agent --api-key "$CURSOR_API_KEY" acp
```

Default binary locations to search (user-overridable):

- `agent` on `PATH`
- `~/.local/bin/agent`
- Windows: `%LOCALAPPDATA%\cursor-agent\agent.exe` (confirm at implementation time)

Auth (in preference order):

1. Existing `agent login` session on the machine
2. Preference / env `CURSOR_API_KEY` / `--api-key`
3. In-plugin “Log in” that runs `agent login` and shows status

---

## Product shape (v1)

| Surface | Behavior |
|---|---|
| View **Cursor** | Chat transcript, prompt box, Cancel, New session |
| Toolbar | Mode: Agent / Plan / Ask; optional model label if ACP advertises it |
| Context | Attach active editor, selection, and selected resources (`@file`) |
| Permissions | Modal/dialog for tool approval (run command, write file, etc.) |
| Edits | Apply writes through Eclipse `IFile` so JDT/local history stay in sync |
| Prefs | Agent binary path, extra args, API key, default cwd, auto-start |
| Status | Agent process up/down, last error, version (`agent --version`) |

### Explicit non-goals for v1

- Ghost-text / NES completions (needs a different Cursor API than ACP)
- Cloud Agents dashboard / PR lifecycle
- Reimplementing Copilot-for-Eclipse
- Shipping or vendoring the Cursor CLI binary
- Depending on `org.eclipse.agents` at runtime (that project is pre-release). We **copy patterns**, not fork IBM/EPL code unless we later choose a dual-plugin strategy

---

## Repository layout (Tycho, OpenCode-plugin style)

```text
cursor-eclipse/
  pom.xml                          # parent, Tycho 4.x
  target-platforms/latest.target   # Eclipse 2025-09 or 2025-12
  bundles/com.cursor.eclipse/      # UI + ACP client
  features/com.cursor.eclipse.feature/
  releng/com.cursor.eclipse.update/  # p2 site
  releng/com.cursor.eclipse.product/ # optional later
  tests/com.cursor.eclipse.tests/
  README.md
```

Bundle internals (mirroring a typical OpenCode Eclipse plugin / eclipse-agents split):

```text
com.cursor.eclipse
  acp/          JSON-RPC framing, request map, session API
  agent/        Process spawn, env, auth, lifecycle jobs
  chat/         ChatView, markdown/HTML renderer, tool-call chips
  editor/       Read/write IFile, dirty buffer, refresh, compare
  permissions/  session/request_permission UI
  cursor/       Extension methods: ask_question, create_plan, todos
  prefs/        Preference page + initializer
```

Java 21, Eclipse 2025-09+ (same ballpark as Copilot for Eclipse / eclipse-agents). License: **MIT** unless you want EPL because of copied Eclipse snippets.

---

## Implementation phases

### Phase 0 — Align with eclipse-opencode (blocked until repo is readable)

- Diff module names, chat widget (SWT vs Browser), process manager
- Reuse package structure and UX copy where it fits Cursor
- List OpenCode-specific pieces to *not* copy (OpenCode config, provider keys, `opencode.json`)

### Phase 1 — Skeleton that installs and talks ACP

1. Tycho parent + empty plugin/feature/update site that builds
2. Preference page: path to `agent`, Test Connection
3. Spawn `agent acp`, log stderr to Eclipse Error Log / trace
4. `initialize` + `authenticate` + `session/new` + one `session/prompt`
5. Render streamed `agent_message_chunk` text in a simple SWT `StyledText` or Browser view

**Exit criterion:** from a running Eclipse with the plugin, send “Say hello” and see the streamed reply. No file tools required yet.

### Phase 2 — Real agent loop

1. Handle `session/request_permission` (do not auto-allow by default)
2. Advertise client fs capabilities; implement `fs/readTextFile` / `fs/writeTextFile` on workspace files
3. Refresh editors after writes; use `IFile.setContents` so JDT incremental compile runs
4. Show tool-call status (name + path) in the transcript
5. `session/cancel`, new session, set mode (`agent` / `plan` / `ask`)
6. Working directory = selected project, else workspace root

**Exit criterion:** “Add a JUnit test for class X” edits a file that Eclipse then compiles.

### Phase 3 — Cursor-specific UX

1. Blocking: `cursor/ask_question`, `cursor/create_plan`
2. Notifications: `cursor/update_todos` (side drawer), `cursor/task`
3. Attach resources: selection, open editors, problems markers as prompt content blocks
4. Optional: pass MCP servers from `.cursor/mcp.json` in `session/new` (Cursor ACP supports this; team dashboard MCP does **not**)

**Exit criterion:** Plan mode shows an approve/reject plan dialog; todos update in the view.

### Phase 4 — Polish and distribution

1. Headless tests for JSON-RPC framing and permission mapping (no Eclipse UI)
2. Plugin tests with a fake ACP server process
3. README: install CLI, `agent login`, Help → Install New Software
4. GitHub Actions: `mvn verify`, publish p2 site to `gh-pages`

---

## Key design decisions (please confirm)

1. **Transport:** ACP via Cursor CLI (`agent acp`). Not the Cloud Agents HTTP API and not a fork of Copilot for Eclipse.
2. **Independence:** Standalone plugin, not a patch to `org.eclipse.agents`. Users should not need IBM’s pre-release Agents feature.
3. **Edits:** Always through Eclipse resources when the path is in the workspace; refuse or warn for paths outside the workspace unless the user enables it.
4. **Permissions:** Prompt for every tool until “Allow always” for that session.
5. **Chat UI:** Start with SWT (simpler, testable). Switch to a Browser/markdown stack if eclipse-opencode already has a good one we can reuse.
6. **Auth:** Prefer existing CLI login; store API key in Eclipse secure preferences if the user pastes one.
7. **Completions:** Out of v1.

---

## Risks

| Risk | Mitigation |
|---|---|
| `eclipse-opencode` layout unknown | Phase 0 once the repo is public/shared; until then follow this ACP plan |
| Cursor ACP schema drift | Keep a thin Jackson/Gson layer; pin CLI version in README; log unknown methods |
| Permission requests block forever | Timeout UI + default reject; never drop the JSON-RPC id |
| Dirty Eclipse buffers vs disk | Prefer editor document text for reads; write via `IFile` |
| `agent` not on PATH in Eclipse (GUI apps on macOS) | Preference for absolute path; document launching Eclipse from a login shell |
| Cursor extension methods are blocking | Must implement ask_question/create_plan or the agent hangs |

---

## Testing strategy (when we implement)

- Unit tests: NDJSON codec, session state machine, permission option mapping
- Fake ACP subprocess for handshake + prompt + permission
- Manual: install into Eclipse 2025-12, login, edit a Java file, Plan mode
- No GUI computer-use in this planning turn

---

## Suggested first implementation ticket (after approval)

**“Phase 1: Tycho plugin + ACP hello-world chat”** — parent POM, plugin fragment, Cursor preference page, `agent acp` process, Chat view that streams one prompt.
