package com.cursor.eclipse.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class SessionUpdatesTest {

	@Test
	void readsTheTextOfEitherSideOfAReplayedConversation() {
		JsonObject prompt = update("user_message_chunk", "Where does the folder come from?");
		JsonObject answer = update("agent_message_chunk", "From LaunchFactory.");

		assertEquals("Where does the folder come from?", SessionUpdates.chunkText(prompt));
		assertEquals("From LaunchFactory.", SessionUpdates.chunkText(answer));
		assertEquals("user_message_chunk", SessionUpdates.kind(prompt));
	}

	@Test
	void keepsUserChunksOutOfTheAgentStream() {
		assertNull(SessionUpdates.agentTextChunk(update("user_message_chunk", "A prompt")));
		assertEquals("An answer", SessionUpdates.agentTextChunk(update("agent_message_chunk", "An answer")));
	}

	@Test
	void ignoresChunksWithoutText() {
		JsonObject image = JsonParser
				.parseString("{\"update\":{\"sessionUpdate\":\"user_message_chunk\","
						+ "\"content\":{\"type\":\"image\",\"data\":\"…\"}}}")
				.getAsJsonObject();

		assertNull(SessionUpdates.chunkText(image));
		assertNull(SessionUpdates.chunkText(new JsonObject()));
	}

	private static JsonObject update(String kind, String text) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonObject update = new JsonObject();
		update.addProperty("sessionUpdate", kind);
		update.add("content", content);
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", "sess-1");
		params.add("update", update);
		return params;
	}
}
