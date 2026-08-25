package com.cursor.eclipse.workspace;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.ResourcesPlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** ACP v1 terminals, confined to working directories in the Eclipse workspace. */
public final class WorkspaceTerminals implements AutoCloseable {

	private final Map<String, RunningTerminal> terminals = new ConcurrentHashMap<>();

	public JsonObject handle(String method, JsonObject params) {
		return switch (method) {
		case "terminal/create" -> create(params);
		case "terminal/output" -> terminal(params).output();
		case "terminal/wait_for_exit" -> terminal(params).waitForExit();
		case "terminal/kill" -> {
			terminal(params).process.destroy();
			yield new JsonObject();
		}
		case "terminal/release" -> {
			RunningTerminal running = terminals.remove(required(params, "terminalId"));
			if (running != null) {
				running.process.destroy();
			}
			yield new JsonObject();
		}
		default -> throw new IllegalArgumentException("Unsupported terminal request: " + method);
		};
	}

	private JsonObject create(JsonObject params) {
		Path cwd = checkedDirectory(required(params, "cwd"));
		ArrayList<String> command = new ArrayList<>();
		command.add(required(params, "command"));
		if (params.has("args") && params.get("args").isJsonArray()) {
			for (JsonElement arg : params.getAsJsonArray("args")) {
				command.add(arg.getAsString());
			}
		}
		try {
			ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
			if (params.has("env") && params.get("env").isJsonArray()) {
				for (JsonElement item : params.getAsJsonArray("env")) {
					JsonObject value = item.getAsJsonObject();
					builder.environment().put(required(value, "name"), required(value, "value"));
				}
			}
			int limit = params.has("outputByteLimit") ? Math.max(1024, params.get("outputByteLimit").getAsInt())
					: 1_048_576;
			String id = "term-" + UUID.randomUUID();
			terminals.put(id, new RunningTerminal(builder.start(), limit));
			JsonObject result = new JsonObject();
			result.addProperty("terminalId", id);
			return result;
		} catch (Exception e) {
			throw new IllegalStateException("Could not start terminal command: " + e.getMessage(), e);
		}
	}

	private RunningTerminal terminal(JsonObject params) {
		RunningTerminal running = terminals.get(required(params, "terminalId"));
		if (running == null) {
			throw new IllegalArgumentException("Unknown terminal");
		}
		return running;
	}

	private static Path checkedDirectory(String value) {
		try {
			Path path = Path.of(value).toAbsolutePath().normalize().toRealPath();
			Path root = Path.of(ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString()).toAbsolutePath()
					.normalize().toRealPath();
			if (!path.startsWith(root)) {
				throw new SecurityException("Cursor terminal access is restricted to the Eclipse workspace: " + path);
			}
			return path;
		} catch (java.io.IOException e) {
			throw new IllegalArgumentException("Terminal working directory does not exist: " + value, e);
		}
	}

	private static String required(JsonObject object, String key) {
		if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
			throw new IllegalArgumentException("Missing terminal field: " + key);
		}
		return object.get(key).getAsString();
	}

	@Override
	public void close() {
		terminals.values().forEach(terminal -> terminal.process.destroy());
		terminals.clear();
	}

	private static final class RunningTerminal {
		private final Process process;
		private final int limit;
		private final StringBuilder output = new StringBuilder();
		private volatile boolean truncated;

		private RunningTerminal(Process process, int limit) {
			this.process = process;
			this.limit = limit;
			Thread.ofVirtual().name("cursor-eclipse-terminal-output").start(() -> {
				try (BufferedReader reader = new BufferedReader(
						new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
					char[] buffer = new char[4096];
					int read;
					while ((read = reader.read(buffer)) >= 0) {
						synchronized (output) {
							output.append(buffer, 0, read);
							while (output.toString().getBytes(StandardCharsets.UTF_8).length > limit
									&& output.length() > 0) {
								int remove = Character.charCount(output.codePointAt(0));
								output.delete(0, remove);
								truncated = true;
							}
						}
					}
				} catch (Exception ignored) {
					// Process termination can close the stream asynchronously.
				}
			});
		}

		private JsonObject output() {
			JsonObject result = new JsonObject();
			synchronized (output) {
				result.addProperty("output", output.toString());
			}
			result.addProperty("truncated", truncated);
			if (!process.isAlive()) {
				result.add("exitStatus", exitStatus(process.exitValue()));
			}
			return result;
		}

		private JsonObject waitForExit() {
			try {
				return exitStatus(process.waitFor());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while waiting for terminal", e);
			}
		}

		private static JsonObject exitStatus(int code) {
			JsonObject status = new JsonObject();
			status.addProperty("exitCode", code);
			status.add("signal", JsonNull.INSTANCE);
			return status;
		}
	}
}
