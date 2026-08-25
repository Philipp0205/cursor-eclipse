package com.cursor.eclipse.agent;

/** A tool invocation reported by the agent. */
public record ToolCall(String id, String title, String kind, String status, String detail) {
}
