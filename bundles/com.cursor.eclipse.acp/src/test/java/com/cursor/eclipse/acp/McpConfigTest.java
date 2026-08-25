package com.cursor.eclipse.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;

class McpConfigTest {

	@Test
	void projectServersOverrideUserServersAndReceiveNames() throws Exception {
		Path originalHome = Path.of(System.getProperty("user.home"));
		Path root = Files.createTempDirectory("cursor-eclipse-mcp");
		Path home = root.resolve("home");
		Path project = root.resolve("project");
		Files.createDirectories(home.resolve(".cursor"));
		Files.createDirectories(project.resolve(".cursor"));
		Files.writeString(home.resolve(".cursor/mcp.json"),
				"{\"mcpServers\":{\"shared\":{\"command\":\"user\"},\"global\":{\"command\":\"global\"}}}");
		Files.writeString(project.resolve(".cursor/mcp.json"),
				"{\"mcpServers\":{\"shared\":{\"command\":\"project\",\"env\":{\"TOKEN\":\"secret\"}},"
						+ "\"remote\":{\"url\":\"https://example.com/mcp\"}}}");
		try {
			System.setProperty("user.home", home.toString());
			JsonArray servers = McpConfig.discover(project.toFile());
			assertEquals(2, servers.size());
			assertEquals("project", servers.get(0).getAsJsonObject().get("command").getAsString());
			assertEquals("shared", servers.get(0).getAsJsonObject().get("name").getAsString());
			assertEquals("global", servers.get(1).getAsJsonObject().get("name").getAsString());
			assertEquals(0, servers.get(0).getAsJsonObject().getAsJsonArray("args").size());
			assertEquals("TOKEN", servers.get(0).getAsJsonObject().getAsJsonArray("env").get(0)
					.getAsJsonObject().get("name").getAsString());
		} finally {
			System.setProperty("user.home", originalHome.toString());
		}
	}
}
