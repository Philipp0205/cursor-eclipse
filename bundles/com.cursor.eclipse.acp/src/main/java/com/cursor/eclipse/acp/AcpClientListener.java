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

	default void onTransportError(Throwable error) {
		// optional
	}
}
