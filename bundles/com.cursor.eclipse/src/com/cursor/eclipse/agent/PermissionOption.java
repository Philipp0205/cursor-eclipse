package com.cursor.eclipse.agent;

/** A choice offered by the agent when it asks permission to run a tool. */
public record PermissionOption(String optionId, String name, String kind) {
}
