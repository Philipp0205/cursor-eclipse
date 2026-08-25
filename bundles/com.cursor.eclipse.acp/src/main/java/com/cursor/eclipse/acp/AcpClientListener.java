package com.cursor.eclipse.acp;

import com.google.gson.JsonObject;

/**
 * Callbacks the IDE implements while an ACP session is running.
 */
public interface AcpClientListener {

	/**
	 * Streaming session updates: message chunks, tool calls, plans, etc.
	 */
	void onSessionUpdate(JsonObject params);

	/**
	 * Tool-call permission request. Must return a JSON-RPC {@code result} object
	 * such as {@code {"outcome":{"outcome":"selected","optionId":"allow-once"}}}.
	 */
	JsonObject onRequestPermission(JsonObject params);

	/**
	 * Reads a file on behalf of the agent. Implementations may return unsaved
	 * editor contents.
	 */
	default JsonObject onReadTextFile(JsonObject params) {
		return onUnhandledRequest("fs/read_text_file", params);
	}

	/**
	 * Writes a file on behalf of the agent through the client's workspace APIs.
	 */
	default JsonObject onWriteTextFile(JsonObject params) {
		return onUnhandledRequest("fs/write_text_file", params);
	}

	/** Handles ACP v1 client-owned terminal lifecycle requests. */
	default JsonObject onTerminalRequest(String method, JsonObject params) {
		return onUnhandledRequest(method, params);
	}

	/**
	 * Cursor-specific blocking extension. Default skips so the agent does not hang.
	 */
	default JsonObject onCursorRequest(String method, JsonObject params) {
		JsonObject outcome = new JsonObject();
		outcome.addProperty("outcome", "skipped");
		JsonObject result = new JsonObject();
		result.add("outcome", outcome);
		return result;
	}

	/**
	 * Unhandled JSON-RPC request from the agent.
	 */
	default JsonObject onUnhandledRequest(String method, JsonObject params) {
		throw new AcpException(-32601, "Method not found: " + method);
	}

	/**
	 * Non-blocking extension notifications, including Cursor todos and tasks.
	 */
	default void onNotification(String method, JsonObject params) {
		// optional
	}

	default void onTransportError(Throwable error) {
		// optional
	}
}
