package com.cursor.eclipse.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.Test;

public class CloudAgentsTest {

	@Test
	public void readsTheV1ListShape() {
		List<CloudAgent> agents = CloudAgents.parse("""
				{
				  "items": [
				    {
				      "id": "bc-1",
				      "name": "Add README with setup instructions",
				      "status": "ACTIVE",
				      "url": "https://cursor.com/agents/bc-1",
				      "createdAt": "2026-04-13T18:30:00.000Z"
				    }
				  ],
				  "nextCursor": "bc-2"
				}
				""");

		assertEquals(1, agents.size());
		CloudAgent agent = agents.get(0);
		assertEquals("bc-1", agent.id());
		assertEquals("Add README with setup instructions", agent.name());
		assertEquals("ACTIVE", agent.status());
		assertEquals("https://cursor.com/agents/bc-1", agent.url());
		assertEquals(Instant.parse("2026-04-13T18:30:00Z"), agent.created());
		assertNull(agent.repository());
	}

	@Test
	public void readsTheLegacyV0ListShape() {
		List<CloudAgent> agents = CloudAgents.parse("""
				{
				  "agents": [
				    {
				      "id": "bc_abc123",
				      "name": "Fix auth bug",
				      "status": "RUNNING",
				      "source": { "repository": "https://github.com/acme/app", "ref": "main" },
				      "target": { "url": "https://cursor.com/agents?id=bc_abc123" },
				      "createdAt": "2026-01-15T10:30:00Z"
				    }
				  ]
				}
				""");

		assertEquals(1, agents.size());
		CloudAgent agent = agents.get(0);
		assertEquals("https://cursor.com/agents?id=bc_abc123", agent.url());
		assertEquals("https://github.com/acme/app", agent.repository());
		assertEquals("RUNNING", agent.status());
	}

	@Test
	public void readsRepositoryFromV1AgentDetails() {
		List<CloudAgent> agents = CloudAgents.parse("""
				{
				  "items": [{
				    "id": "bc-2",
				    "name": "Update payments",
				    "repos": [{"url": "https://github.com/acme/payments", "startingRef": "main"}]
				  }]
				}
				""");

		assertEquals("https://github.com/acme/payments", agents.get(0).repository());
	}

	@Test
	public void readsCloudConversationMessages() {
		List<CloudMessage> messages = CloudAgents.parseConversation("""
				{"messages":[
				  {"id":"msg-1","type":"user_message","text":"Fix it"},
				  {"id":"msg-2","type":"assistant_message","text":"Done"},
				  {"id":"ignored","type":"assistant_message"}
				]}
				""");

		assertEquals(2, messages.size());
		assertEquals("user", messages.get(0).role());
		assertEquals("Fix it", messages.get(0).text());
		assertEquals("assistant", messages.get(1).role());
	}

	@Test
	public void usesRepositoryOwnerAndNameAsTheCloudFolder() {
		assertEquals("acme/payments",
				AgentsView.repositoryLabel("https://github.com/acme/payments.git"));
		assertEquals("No repository", AgentsView.repositoryLabel(null));
	}

	@Test
	public void fillsInMissingFields() {
		List<CloudAgent> agents = CloudAgents.parse("{\"items\":[{\"id\":\"bc-3\"},{\"name\":\"no id\"}]}");

		assertEquals(1, agents.size());
		assertEquals("bc-3", agents.get(0).name());
		assertEquals("https://cursor.com/agents/bc-3", agents.get(0).url());
		assertEquals(Instant.EPOCH, agents.get(0).created());
	}

	@Test
	public void toleratesEmptyAndUnexpectedBodies() {
		assertTrue(CloudAgents.parse("").isEmpty());
		assertTrue(CloudAgents.parse("{}").isEmpty());
		assertTrue(CloudAgents.parse("[]").isEmpty());
		assertTrue(CloudAgents.parse("{\"items\":\"none\"}").isEmpty());
	}
}
