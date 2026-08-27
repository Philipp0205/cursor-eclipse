#!/usr/bin/env python3
"""A fake ACP agent for manually testing the Eclipse plugin without a Cursor subscription.

Point Window > Preferences > Cursor at this file with arguments `acp`.

Prompts containing "slow" stream for a minute so the Stop button can be exercised;
any other prompt runs a short scripted demo covering markdown, thinking, tool
calls, a permission request, a plan, todos, and a workspace file write.
"""

import json
import os
import sys
import threading
import time

STATE = {"cwd": os.getcwd(), "cancelled": False, "session": "demo-session"}
WRITE_LOCK = threading.Lock()


def send(message):
    with WRITE_LOCK:
        sys.stdout.write(json.dumps(message) + "\n")
        sys.stdout.flush()


def notify(method, params):
    send({"jsonrpc": "2.0", "method": method, "params": params})


def update(payload):
    notify("session/update", {"sessionId": STATE["session"], "update": payload})


def chunk(text, kind="agent_message_chunk"):
    update({"sessionUpdate": kind, "content": {"type": "text", "text": text}})


def tool(call_id, title, kind, status, path=None):
    payload = {
        "sessionUpdate": "tool_call" if status == "pending" else "tool_call_update",
        "toolCallId": call_id,
        "title": title,
        "kind": kind,
        "status": status,
    }
    if path:
        payload["locations"] = [{"path": path}]
    update(payload)


def request(request_id, method, params):
    send({"jsonrpc": "2.0", "id": request_id, "method": method, "params": params})


def stream(text, delay=0.03):
    for word in text.split(" "):
        if STATE["cancelled"]:
            return False
        chunk(word + " ")
        time.sleep(delay)
    return True


def run_demo(request_id, prompt_text):
    if "slow" in prompt_text.lower():
        chunk("Streaming until you press Stop. ")
        # Long enough that the turn can only end through cancellation.
        for i in range(2400):
            if STATE["cancelled"]:
                send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "cancelled"}})
                return
            chunk("tick %d " % i)
            time.sleep(0.25)
        send({"jsonrpc": "2.0", "id": request_id, "result": {"stopReason": "end_turn"}})
        return

    update({"sessionUpdate": "agent_thought_chunk",
            "content": {"type": "text", "text": "Reading the workspace and planning the change."}})

    update({"sessionUpdate": "plan", "entries": [
        {"content": "Inspect the workspace", "status": "completed", "priority": "high"},
        {"content": "Write the demo file", "status": "in_progress", "priority": "high"},
        {"content": "Summarise the result", "status": "pending", "priority": "medium"},
    ]})

    demo_file = os.path.join(STATE["cwd"], "FakeAgentDemo.txt")
    tool("call-1", "Write FakeAgentDemo.txt", "edit", "pending", demo_file)
    request("perm-1", "session/request_permission", {
        "sessionId": "demo-session",
        "toolCall": {"toolCallId": "call-1", "title": "Write FakeAgentDemo.txt", "kind": "edit"},
        "options": [
            {"optionId": "allow-once", "name": "Allow once", "kind": "allow_once"},
            {"optionId": "allow-always", "name": "Always allow", "kind": "allow_always"},
            {"optionId": "reject-once", "name": "Reject", "kind": "reject_once"},
        ],
    })


def continue_after_permission(allowed):
    demo_file = os.path.join(STATE["cwd"], "FakeAgentDemo.txt")
    if not allowed:
        tool("call-1", "Write FakeAgentDemo.txt", "edit", "failed", demo_file)
        chunk("The write was rejected, so nothing changed.")
        finish()
        return

    tool("call-1", "Write FakeAgentDemo.txt", "edit", "in_progress", demo_file)
    request("write-1", "fs/write_text_file", {
        "sessionId": "demo-session",
        "path": demo_file,
        "content": "Written through Eclipse IFile via ACP.\n",
    })


def finish_after_write():
    demo_file = os.path.join(STATE["cwd"], "FakeAgentDemo.txt")
    tool("call-1", "Write FakeAgentDemo.txt", "edit", "completed", demo_file)
    notify("cursor/update_todos", {"merge": False, "todos": [
        {"id": "1", "content": "Inspect the workspace", "status": "completed"},
        {"id": "2", "content": "Write the demo file", "status": "completed"},
        {"id": "3", "content": "Summarise the result", "status": "completed"},
    ]})
    stream("## ACP demo complete\n", 0.02)
    stream("I wrote **FakeAgentDemo.txt** through the Eclipse workspace APIs, "
           "so local history and the incremental builder both saw the change.\n", 0.02)
    stream("Rendering check: `inline code`, a list, and a fenced block.\n", 0.02)
    stream("- streaming markdown\n- tool call status\n- plan and todos\n", 0.02)
    chunk("\n```java\npublic record Demo(String value) {}\n```\n")
    chunk("\n| Feature | State |\n| --- | --- |\n| Streaming | ok |\n| Permissions | ok |\n")
    finish()


def replay(request_id):
    """Answers session/load the way an agent restoring a stored chat does."""
    conversation = [
        ("user", "Where does the plugin decide which folder a session runs in?"),
        ("agent", "`LaunchFactory.workingDirectory()` picks the selected project, then the "
                  "configured default, then the workspace root.\n"),
        ("user", "Good. Add a test for the worktree case."),
        ("agent", "Added `SessionLaunchRegistryTest.tracksPrimaryAndSecondarySessionsByFolder`, "
                  "which registers a chat in a worktree and asserts the folder grouping.\n"),
    ]
    for role, text in conversation:
        update({"sessionUpdate": "user_message_chunk" if role == "user" else "agent_message_chunk",
                "content": {"type": "text", "text": text}})
        time.sleep(0.2)
    send({"jsonrpc": "2.0", "id": request_id, "result": {}})


def finish():
    if STATE.get("prompt_id") is not None:
        send({"jsonrpc": "2.0", "id": STATE["prompt_id"],
              "result": {"stopReason": "cancelled" if STATE["cancelled"] else "end_turn"}})
        STATE["prompt_id"] = None


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue

        method = message.get("method")
        request_id = message.get("id")

        if method == "initialize":
            send({"jsonrpc": "2.0", "id": request_id, "result": {
                "protocolVersion": 1,
                "authMethods": [],
                "agentCapabilities": {"loadSession": True},
            }})
        elif method == "session/new":
            STATE["cwd"] = message["params"]["cwd"]
            STATE["session"] = "demo-session"
            send({"jsonrpc": "2.0", "id": request_id, "result": {
                "sessionId": "demo-session",
                "modes": {
                    "currentModeId": "agent",
                    "availableModes": [
                        {"id": "agent", "name": "Agent", "description": "Full tool access"},
                        {"id": "plan", "name": "Plan", "description": "Plan without editing"},
                        {"id": "ask", "name": "Ask", "description": "Answer questions only"},
                    ],
                },
            }})
        elif method == "session/load":
            STATE["cwd"] = message["params"].get("cwd", STATE["cwd"])
            STATE["session"] = message["params"]["sessionId"]
            threading.Thread(target=replay, args=(request_id,), daemon=True).start()
        elif method == "session/set_mode":
            update({"sessionUpdate": "current_mode_update", "modeId": message["params"]["modeId"]})
            send({"jsonrpc": "2.0", "id": request_id, "result": {}})
        elif method == "session/cancel":
            STATE["cancelled"] = True
        elif method == "session/prompt":
            STATE["cancelled"] = False
            STATE["prompt_id"] = request_id
            text = " ".join(block.get("text", "") for block in message["params"]["prompt"]
                            if block.get("type") == "text")
            threading.Thread(target=run_demo, args=(request_id, text), daemon=True).start()
        elif request_id == "perm-1":
            outcome = (message.get("result") or {}).get("outcome") or {}
            allowed = outcome.get("optionId", "").startswith("allow")
            threading.Thread(target=continue_after_permission, args=(allowed,), daemon=True).start()
        elif request_id == "write-1":
            if "error" in message:
                tool("call-1", "Write FakeAgentDemo.txt", "edit", "failed")
                chunk("Eclipse rejected the write: %s" % message["error"].get("message"))
                finish()
            else:
                threading.Thread(target=finish_after_write, daemon=True).start()


if __name__ == "__main__":
    main()
