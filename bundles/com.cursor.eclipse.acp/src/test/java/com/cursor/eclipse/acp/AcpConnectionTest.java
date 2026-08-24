package com.cursor.eclipse.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class AcpConnectionTest {

	@Test
	void streamsPromptAndAnswersPermission() throws Exception {
		PipedOutputStream clientToAgent = new PipedOutputStream();
		PipedInputStream agentIn = new PipedInputStream(clientToAgent, 1 << 16);
		PipedOutputStream agentToClient = new PipedOutputStream();
		PipedInputStream clientIn = new PipedInputStream(agentToClient, 1 << 16);

		FakeAcpAgent agent = new FakeAcpAgent(agentIn, agentToClient);
		Thread agentThread = new Thread(agent, "fake-acp-agent");
		agentThread.setDaemon(true);
		agentThread.start();

		CopyOnWriteArrayList<String> chunks = new CopyOnWriteArrayList<>();
		CountDownLatch permission = new CountDownLatch(1);
		AtomicInteger permissions = new AtomicInteger();

		AcpClientListener listener = new AcpClientListener() {
			@Override
			public void onSessionUpdate(JsonObject params) {
				String text = SessionUpdates.agentTextChunk(params);
				if (text != null) {
					chunks.add(text);
				}
			}

			@Override
			public JsonObject onRequestPermission(JsonObject params) {
				permissions.incrementAndGet();
				permission.countDown();
				return PermissionDecisions.allowOnce();
			}
		};

		try (AcpConnection connection = new AcpConnection(clientIn, clientToAgent, listener)) {
			JsonObject init = connection.initialize();
			assertEquals(1, init.get("protocolVersion").getAsInt());
			connection.authenticate("cursor_login");
			assertEquals("sess-1", connection.newSession("/tmp/project"));
			JsonObject prompt = connection.prompt("Say hello");
			assertEquals("end_turn", prompt.get("stopReason").getAsString());
			assertTrue(permission.await(5, TimeUnit.SECONDS));
			assertEquals(1, permissions.get());
			assertEquals("Hello from ACP.", String.join("", chunks));
		}
	}

	@Test
	void extractsAgentTextFromWrappedUpdate() {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", "hi");
		JsonObject update = new JsonObject();
		update.addProperty("sessionUpdate", "agent_message_chunk");
		update.add("content", content);
		JsonObject params = new JsonObject();
		params.add("update", update);
		assertEquals("hi", SessionUpdates.agentTextChunk(params));
	}
}
