package com.cursor.eclipse.agents;

import java.time.Instant;

/**
 * A Cursor Cloud Agent owned by the signed-in account.
 *
 * @param id         the agent id, for example {@code bc-0000...}
 * @param name       the agent name shown in Cursor
 * @param status     the lifecycle status reported by the API
 * @param url        the cursor.com page for the agent, or {@code null}
 * @param repository the repository the agent works on, or {@code null}
 * @param created    when the agent was created
 * @param archived   whether the agent is archived
 */
public record CloudAgent(String id, String name, String status, String url, String repository, Instant created,
		boolean archived) {
}
