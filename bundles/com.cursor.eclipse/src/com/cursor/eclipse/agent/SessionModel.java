package com.cursor.eclipse.agent;

/** A model advertised through an ACP session config option. */
public record SessionModel(String configId, String id, String name, String description) {
}
