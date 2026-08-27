package com.cursor.eclipse.agents;

/** One streamed Cloud Agent tool invocation. */
public record CloudToolCall(String id, String name, String kind, String status, String detail) {
}
