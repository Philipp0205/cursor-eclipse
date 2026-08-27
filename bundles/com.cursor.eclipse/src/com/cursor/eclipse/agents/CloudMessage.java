package com.cursor.eclipse.agents;

/** One user or assistant message in a Cloud Agent conversation. */
public record CloudMessage(String id, String role, String text) {
}
