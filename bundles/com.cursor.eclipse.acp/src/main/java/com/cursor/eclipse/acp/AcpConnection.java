package com.cursor.eclipse.acp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * ACP client over newline-delimited JSON-RPC 2.0 (Cursor {@code agent acp}).
 */
public final class AcpConnection implements Closeable {

	private static final String JSONRPC = "2.0";
	private static final long DEFAULT_TIMEOUT_SECONDS = 120;

	private final Gson gson = new Gson();
	private final BufferedWriter writer;
	private final BufferedReader reader;
	private final Closeable extraCloseable;
	private final AcpClientListener listener;
	private final AtomicInteger nextId = new AtomicInteger(1);
	private final ConcurrentHashMap<String, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<>();
	private final AtomicBoolean closed = new AtomicBoolean(false);
	private final Thread readerThread;
	private final AtomicReference<String> sessionId = new AtomicReference<>();
	/**
	 * Inbound requests run here rather than on the reader thread, so a permission
	 * dialog cannot stop the client from reading streamed updates or from
	 * completing the in-flight prompt.
	 */
	private final ExecutorService inbound = Executors.newCachedThreadPool(runnable -> {
		Thread thread = new Thread(runnable, "cursor-eclipse-acp-inbound");
		thread.setDaemon(true);
		return thread;
	});

	public AcpConnection(InputStream input, OutputStream output, AcpClientListener listener) {
		this(input, output, listener, null);
	}

	public AcpConnection(InputStream input, OutputStream output, AcpClientListener listener, Closeable extraCloseable) {
		this.listener = Objects.requireNonNull(listener, "listener");
		this.writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
		this.reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
		this.extraCloseable = extraCloseable;
		this.readerThread = new Thread(this::readLoop, "cursor-eclipse-acp-reader");
		this.readerThread.setDaemon(true);
		this.readerThread.start();
	}

	public static AcpConnection connect(CursorAgentLaunch launch, AcpClientListener listener) throws IOException {
		ProcessBuilder builder = new ProcessBuilder(launch.commandLine());
		if (launch.getWorkingDirectory() != null) {
			builder.directory(launch.getWorkingDirectory());
		}
		builder.environment().putAll(launch.getExtraEnv());
		Process process = builder.start();
		return new AcpConnection(process.getInputStream(), process.getOutputStream(),
				new ProcessAwareListener(listener, process), process::destroy);
	}

	public JsonObject initialize() {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", 1);
		JsonObject clientInfo = new JsonObject();
		clientInfo.addProperty("name", "cursor-eclipse");
		clientInfo.addProperty("version", "0.1.0");
		params.add("clientInfo", clientInfo);
		JsonObject fs = new JsonObject();
		fs.addProperty("readTextFile", true);
		fs.addProperty("writeTextFile", true);
		JsonObject capabilities = new JsonObject();
		capabilities.add("fs", fs);
		capabilities.addProperty("terminal", true);
		params.add("clientCapabilities", capabilities);
		return request("initialize", params).getAsJsonObject();
	}

	public JsonObject authenticate(String methodId) {
		JsonObject params = new JsonObject();
		params.addProperty("methodId", methodId);
		JsonElement result = request("authenticate", params);
		return result == null || result.isJsonNull() ? new JsonObject() : result.getAsJsonObject();
	}

	public JsonObject newSession(String cwd) {
		return newSession(cwd, new JsonArray());
	}

	public JsonObject newSession(String cwd, JsonArray mcpServers) {
		JsonObject params = new JsonObject();
		params.addProperty("cwd", cwd);
		params.add("mcpServers", mcpServers == null ? new JsonArray() : mcpServers);
		JsonObject result = request("session/new", params).getAsJsonObject();
		String id = result.get("sessionId").getAsString();
		sessionId.set(id);
		return result;
	}

	public JsonObject loadSession(String id, String cwd, JsonArray mcpServers) {
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", id);
		params.addProperty("cwd", cwd);
		params.add("mcpServers", mcpServers == null ? new JsonArray() : mcpServers);
		JsonElement result = request("session/load", params);
		sessionId.set(id);
		return result == null || result.isJsonNull() ? new JsonObject() : result.getAsJsonObject();
	}

	public JsonObject prompt(String text) {
		JsonArray prompt = new JsonArray();
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", text);
		prompt.add(block);
		return prompt(prompt);
	}

	public JsonObject prompt(JsonArray prompt) {
		String id = sessionId.get();
		if (id == null) {
			throw new AcpException("No ACP session. Call newSession first.");
		}
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", id);
		params.add("prompt", prompt);
		JsonElement result = request("session/prompt", params, 10, TimeUnit.MINUTES);
		return result == null || result.isJsonNull() ? new JsonObject() : result.getAsJsonObject();
	}

	public JsonObject setMode(String modeId) {
		String id = sessionId.get();
		if (id == null) {
			throw new AcpException("No ACP session. Call newSession first.");
		}
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", id);
		params.addProperty("modeId", modeId);
		JsonElement result = request("session/set_mode", params);
		return result == null || result.isJsonNull() ? new JsonObject() : result.getAsJsonObject();
	}

	public JsonObject setConfigOption(String configId, String value) {
		String id = sessionId.get();
		if (id == null) {
			throw new AcpException("No ACP session. Call newSession first.");
		}
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", id);
		params.addProperty("configId", configId);
		params.addProperty("value", value);
		JsonElement result = request("session/set_config_option", params);
		return result == null || result.isJsonNull() ? new JsonObject() : result.getAsJsonObject();
	}

	public void cancel() {
		String id = sessionId.get();
		if (id == null) {
			return;
		}
		JsonObject params = new JsonObject();
		params.addProperty("sessionId", id);
		notify("session/cancel", params);
	}

	public String getSessionId() {
		return sessionId.get();
	}

	public JsonElement request(String method, JsonObject params) {
		return request(method, params, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}

	public JsonElement request(String method, JsonObject params, long timeout, TimeUnit unit) {
		String id = Integer.toString(nextId.getAndIncrement());
		CompletableFuture<JsonElement> future = new CompletableFuture<>();
		pending.put(id, future);
		JsonObject message = envelope(id, method, params);
		try {
			send(message);
		} catch (IOException e) {
			pending.remove(id);
			throw new AcpException("Failed to send " + method, e);
		}
		try {
			return future.get(timeout, unit);
		} catch (Exception e) {
			pending.remove(id);
			throw new AcpException("ACP request failed: " + method, e);
		}
	}

	public void notify(String method, JsonObject params) {
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", JSONRPC);
		message.addProperty("method", method);
		if (params != null) {
			message.add("params", params);
		}
		try {
			send(message);
		} catch (IOException e) {
			throw new AcpException("Failed to send notification " + method, e);
		}
	}

	private JsonObject envelope(String id, String method, JsonObject params) {
		JsonObject message = new JsonObject();
		message.addProperty("jsonrpc", JSONRPC);
		message.addProperty("id", id);
		message.addProperty("method", method);
		if (params != null) {
			message.add("params", params);
		}
		return message;
	}

	private synchronized void send(JsonObject message) throws IOException {
		if (closed.get()) {
			throw new IOException("ACP connection is closed");
		}
		writer.write(gson.toJson(message));
		writer.write('\n');
		writer.flush();
	}

	private void readLoop() {
		try {
			String line;
			while (!closed.get() && (line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				JsonObject message;
				try {
					message = gson.fromJson(line, JsonObject.class);
				} catch (Exception e) {
					continue;
				}
				if (message == null) {
					continue;
				}
				handleMessage(message);
			}
		} catch (IOException e) {
			if (!closed.get()) {
				listener.onTransportError(e);
			}
		} finally {
			failPending(new AcpException("ACP connection closed"));
		}
	}

	private void handleMessage(JsonObject message) {
		boolean hasId = message.has("id") && !message.get("id").isJsonNull();
		boolean hasMethod = message.has("method");
		if (hasId && (message.has("result") || message.has("error"))) {
			completePending(idToString(message.get("id")), message);
			return;
		}
		if (hasMethod && hasId) {
			handleIncomingRequest(message);
			return;
		}
		if (hasMethod) {
			handleNotification(message);
		}
	}

	private void handleIncomingRequest(JsonObject message) {
		String method = message.get("method").getAsString();
		JsonObject params = message.has("params") && message.get("params").isJsonObject()
				? message.getAsJsonObject("params")
				: new JsonObject();
		try {
			inbound.execute(() -> respondToRequest(message, method, params));
		} catch (RuntimeException rejected) {
			// Shutting down; the agent observes the closed transport instead.
		}
	}

	private void respondToRequest(JsonObject message, String method, JsonObject params) {
		try {
			JsonObject result;
			if ("session/request_permission".equals(method)) {
				result = listener.onRequestPermission(params);
			} else if ("fs/read_text_file".equals(method)) {
				result = listener.onReadTextFile(params);
			} else if ("fs/write_text_file".equals(method)) {
				result = listener.onWriteTextFile(params);
			} else if (method.startsWith("terminal/")) {
				result = listener.onTerminalRequest(method, params);
			} else if (method.startsWith("cursor/")) {
				result = listener.onCursorRequest(method, params);
			} else {
				result = listener.onUnhandledRequest(method, params);
			}
			JsonObject response = new JsonObject();
			response.addProperty("jsonrpc", JSONRPC);
			copyId(message.get("id"), response);
			response.add("result", result == null ? new JsonObject() : result);
			send(response);
		} catch (Exception e) {
			try {
				JsonObject response = new JsonObject();
				response.addProperty("jsonrpc", JSONRPC);
				copyId(message.get("id"), response);
				JsonObject error = new JsonObject();
				error.addProperty("code", -32603);
				error.addProperty("message", e.getMessage() == null ? "Internal error" : e.getMessage());
				response.add("error", error);
				send(response);
			} catch (IOException ignored) {
				// connection already broken
			}
		}
	}

	private void handleNotification(JsonObject message) {
		String method = message.get("method").getAsString();
		JsonObject params = message.has("params") && message.get("params").isJsonObject()
				? message.getAsJsonObject("params")
				: new JsonObject();
		if ("session/update".equals(method)) {
			listener.onSessionUpdate(params);
		} else {
			listener.onNotification(method, params);
		}
	}

	private void completePending(String id, JsonObject message) {
		CompletableFuture<JsonElement> future = pending.remove(id);
		if (future == null) {
			return;
		}
		if (message.has("error")) {
			JsonObject error = message.getAsJsonObject("error");
			int code = error.has("code") ? error.get("code").getAsInt() : -1;
			String text = error.has("message") ? error.get("message").getAsString() : "ACP error";
			future.completeExceptionally(new AcpException(code, text));
			return;
		}
		future.complete(message.get("result"));
	}

	private void failPending(Throwable error) {
		for (CompletableFuture<JsonElement> future : pending.values()) {
			future.completeExceptionally(error);
		}
		pending.clear();
	}

	private static String idToString(JsonElement id) {
		if (id == null || id.isJsonNull()) {
			return UUID.randomUUID().toString();
		}
		if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
			return Long.toString(id.getAsLong());
		}
		return id.getAsString();
	}

	private static void copyId(JsonElement id, JsonObject target) {
		if (id == null || id.isJsonNull()) {
			return;
		}
		if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isNumber()) {
			target.add("id", id);
		} else {
			target.addProperty("id", id.getAsString());
		}
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			writer.close();
		} catch (IOException ignored) {
		}
		try {
			reader.close();
		} catch (IOException ignored) {
		}
		if (extraCloseable != null) {
			try {
				extraCloseable.close();
			} catch (Exception ignored) {
			}
		}
		inbound.shutdownNow();
		readerThread.interrupt();
	}

	private static final class ProcessAwareListener implements AcpClientListener {
		private final AcpClientListener delegate;
		private final Process process;

		private ProcessAwareListener(AcpClientListener delegate, Process process) {
			this.delegate = delegate;
			this.process = process;
			Thread stderr = new Thread(() -> drain(process.getErrorStream()), "cursor-eclipse-acp-stderr");
			stderr.setDaemon(true);
			stderr.start();
		}

		@Override
		public void onSessionUpdate(JsonObject params) {
			delegate.onSessionUpdate(params);
		}

		@Override
		public JsonObject onRequestPermission(JsonObject params) {
			return delegate.onRequestPermission(params);
		}

		@Override
		public JsonObject onReadTextFile(JsonObject params) {
			return delegate.onReadTextFile(params);
		}

		@Override
		public JsonObject onWriteTextFile(JsonObject params) {
			return delegate.onWriteTextFile(params);
		}

		@Override
		public JsonObject onTerminalRequest(String method, JsonObject params) {
			return delegate.onTerminalRequest(method, params);
		}

		@Override
		public JsonObject onCursorRequest(String method, JsonObject params) {
			return delegate.onCursorRequest(method, params);
		}

		@Override
		public JsonObject onUnhandledRequest(String method, JsonObject params) {
			return delegate.onUnhandledRequest(method, params);
		}

		@Override
		public void onTransportError(Throwable error) {
			delegate.onTransportError(error);
		}

		@Override
		public void onNotification(String method, JsonObject params) {
			delegate.onNotification(method, params);
		}

		private static void drain(InputStream stream) {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				while (reader.readLine() != null) {
					// stderr is discarded at the protocol layer; the UI can wrap this later
				}
			} catch (IOException ignored) {
			}
		}
	}
}
