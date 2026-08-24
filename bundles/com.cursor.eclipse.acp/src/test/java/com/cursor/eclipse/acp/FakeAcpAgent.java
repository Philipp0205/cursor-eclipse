package com.cursor.eclipse.acp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Minimal ACP agent used by unit tests. Speaks NDJSON JSON-RPC.
 */
public final class FakeAcpAgent implements Runnable {

	private final Gson gson = new Gson();
	private final BufferedReader reader;
	private final BufferedWriter writer;
	private volatile boolean permissionRequested;

	public FakeAcpAgent(InputStream in, OutputStream out) {
		this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
	}

	public boolean wasPermissionRequested() {
		return permissionRequested;
	}

	@Override
	public void run() {
		try {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonObject message = gson.fromJson(line, JsonObject.class);
				handle(message);
			}
		} catch (IOException ignored) {
			// test process ended
		}
	}

	private void handle(JsonObject message) throws IOException {
		if (!message.has("method")) {
			return;
		}
		String method = message.get("method").getAsString();
		if ("initialize".equals(method)) {
			JsonObject result = new JsonObject();
			result.addProperty("protocolVersion", 1);
			JsonArray methods = new JsonArray();
			JsonObject login = new JsonObject();
			login.addProperty("id", "cursor_login");
			login.addProperty("name", "Cursor Login");
			methods.add(login);
			result.add("authMethods", methods);
			reply(message, result);
			return;
		}
		if ("authenticate".equals(method)) {
			reply(message, new JsonObject());
			return;
		}
		if ("session/new".equals(method)) {
			JsonObject result = new JsonObject();
			result.addProperty("sessionId", "sess-1");
			reply(message, result);
			return;
		}
		if ("session/prompt".equals(method)) {
			notifyUpdate(agentChunk("Hello from "));
			notifyUpdate(agentChunk("ACP."));
			JsonObject perm = new JsonObject();
			perm.addProperty("jsonrpc", "2.0");
			perm.addProperty("id", "perm-1");
			perm.addProperty("method", "session/request_permission");
			JsonObject params = new JsonObject();
			params.addProperty("toolCallId", "call-1");
			perm.add("params", params);
			send(perm);
			permissionRequested = true;
			JsonObject result = new JsonObject();
			result.addProperty("stopReason", "end_turn");
			reply(message, result);
		}
	}

	private JsonObject agentChunk(String text) {
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", text);
		JsonObject update = new JsonObject();
		update.addProperty("sessionUpdate", "agent_message_chunk");
		update.add("content", content);
		JsonObject params = new JsonObject();
		params.add("update", update);
		return params;
	}

	private void notifyUpdate(JsonObject params) throws IOException {
		JsonObject note = new JsonObject();
		note.addProperty("jsonrpc", "2.0");
		note.addProperty("method", "session/update");
		note.add("params", params);
		send(note);
	}

	private void reply(JsonObject request, JsonObject result) throws IOException {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", request.get("id"));
		response.add("result", result);
		send(response);
	}

	private synchronized void send(JsonObject message) throws IOException {
		writer.write(gson.toJson(message));
		writer.write('\n');
		writer.flush();
	}
}
