package com.cursor.eclipse.acp;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Loads Cursor-compatible user and project MCP configuration for ACP sessions. */
public final class McpConfig {

	private McpConfig() {
	}

	public static JsonArray discover(File workingDirectory) {
		Map<String, JsonObject> servers = new LinkedHashMap<>();
		Path home = Path.of(System.getProperty("user.home", ".")).resolve(".cursor").resolve("mcp.json");
		read(home, servers);
		if (workingDirectory != null) {
			read(workingDirectory.toPath().resolve(".cursor").resolve("mcp.json"), servers);
		}
		JsonArray result = new JsonArray();
		servers.forEach((name, config) -> {
			JsonObject server = normalizeStdio(name, config);
			if (server != null) {
				result.add(server);
			}
		});
		return result;
	}

	private static JsonObject normalizeStdio(String name, JsonObject config) {
		if (!config.has("command") || !config.get("command").isJsonPrimitive()) {
			// ACP transport capabilities are negotiated separately; leave HTTP/SSE
			// configs for Cursor CLI's own discovery instead of sending invalid wire data.
			return null;
		}
		JsonObject server = new JsonObject();
		server.addProperty("name", name);
		server.addProperty("command", config.get("command").getAsString());
		server.add("args", config.has("args") && config.get("args").isJsonArray()
				? config.getAsJsonArray("args").deepCopy() : new JsonArray());
		JsonArray env = new JsonArray();
		if (config.has("env") && config.get("env").isJsonObject()) {
			for (Map.Entry<String, JsonElement> entry : config.getAsJsonObject("env").entrySet()) {
				if (entry.getValue().isJsonPrimitive()) {
					JsonObject variable = new JsonObject();
					variable.addProperty("name", entry.getKey());
					variable.addProperty("value", entry.getValue().getAsString());
					env.add(variable);
				}
			}
		} else if (config.has("env") && config.get("env").isJsonArray()) {
			env = config.getAsJsonArray("env").deepCopy();
		}
		server.add("env", env);
		return server;
	}

	private static void read(Path path, Map<String, JsonObject> servers) {
		if (!Files.isRegularFile(path)) {
			return;
		}
		try {
			JsonElement root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
			if (!root.isJsonObject()) {
				return;
			}
			JsonObject object = root.getAsJsonObject();
			JsonObject configured = object.has("mcpServers") && object.get("mcpServers").isJsonObject()
					? object.getAsJsonObject("mcpServers") : object;
			for (Map.Entry<String, JsonElement> entry : configured.entrySet()) {
				if (entry.getValue().isJsonObject()) {
					servers.put(entry.getKey(), entry.getValue().getAsJsonObject());
				}
			}
		} catch (IOException | RuntimeException ignored) {
			// A malformed optional config must not prevent a local agent session.
		}
	}
}
