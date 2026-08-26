package com.cursor.eclipse.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

public class SessionLaunchRegistryTest {

	private static final String SECONDARY_ID = "agent-test-1234567890123";

	@After
	public void cleanUp() {
		SessionLaunchRegistry.remove(null);
		SessionLaunchRegistry.remove(SECONDARY_ID);
	}

	@Test
	public void tracksPrimaryAndSecondarySessionsByFolder() {
		File workspace = new File("/tmp/workspace");
		File worktree = new File("/tmp/worktree");

		SessionLaunchRegistry.register(null, "Chat", workspace);
		SessionLaunchRegistry.put(SECONDARY_ID, worktree);
		SessionLaunchRegistry.register(SECONDARY_ID, "Agent test", worktree);
		SessionLaunchRegistry.update(SECONDARY_ID, "session-42", "Ready");

		assertEquals(worktree, SessionLaunchRegistry.get(SECONDARY_ID));
		assertEquals(2, SessionLaunchRegistry.sessions().size());
		assertTrue(SessionLaunchRegistry.sessions().stream()
				.anyMatch(session -> "session-42".equals(session.sessionId()) && "Ready".equals(session.status())));
	}

	@Test
	public void notifiesListenersAndRemovesClosedViews() {
		AtomicInteger changes = new AtomicInteger();
		Runnable listener = changes::incrementAndGet;
		SessionLaunchRegistry.addListener(listener);
		try {
			SessionLaunchRegistry.register(null, "Chat", new File("/tmp/workspace"));
			SessionLaunchRegistry.remove(null);
		} finally {
			SessionLaunchRegistry.removeListener(listener);
		}

		assertEquals(2, changes.get());
		assertTrue(SessionLaunchRegistry.sessions().isEmpty());
		assertNull(SessionLaunchRegistry.get(null));
	}
}
