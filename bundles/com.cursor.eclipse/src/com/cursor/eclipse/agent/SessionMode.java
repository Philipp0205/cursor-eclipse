package com.cursor.eclipse.agent;

/** An agent operating mode advertised by Cursor, such as Agent, Plan, or Ask. */
public record SessionMode(String id, String name, String description) {
}
