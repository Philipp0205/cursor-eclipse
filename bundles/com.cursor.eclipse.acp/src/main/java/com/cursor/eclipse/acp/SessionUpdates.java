package com.cursor.eclipse.acp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Helpers for {@code session/update} payloads.
 */
public final class SessionUpdates {

	private SessionUpdates() {
	}

	public static String agentTextChunk(JsonObject params) {
		JsonObject update = updateObject(params);
		if (update == null) {
			return null;
		}
		String kind = text(update, "sessionUpdate");
		if (!"agent_message_chunk".equals(kind) && !"agent_thought_chunk".equals(kind)) {
			return null;
		}
		if (!update.has("content") || !update.get("content").isJsonObject()) {
			return null;
		}
		JsonObject content = update.getAsJsonObject("content");
		if (!"text".equals(text(content, "type"))) {
			return null;
		}
		return text(content, "text");
	}

	public static JsonObject updateObject(JsonObject params) {
		if (params == null) {
			return null;
		}
		if (params.has("update") && params.get("update").isJsonObject()) {
			return params.getAsJsonObject("update");
		}
		if (params.has("sessionUpdate")) {
			return params;
		}
		return null;
	}

	public static String kind(JsonObject params) {
		JsonObject update = updateObject(params);
		return update == null ? null : text(update, "sessionUpdate");
	}

	public static String toolSummary(JsonObject params) {
		JsonObject update = updateObject(params);
		if (update == null) {
			return null;
		}
		String kind = text(update, "sessionUpdate");
		if (!"tool_call".equals(kind) && !"tool_call_update".equals(kind)) {
			return null;
		}
		String title = text(update, "title");
		String status = text(update, "status");
		String toolCallId = text(update, "toolCallId");
		String label = title != null ? title : toolCallId;
		if (label == null) {
			label = "Tool";
		}
		return status == null ? label : label + " · " + status;
	}

	public static String currentMode(JsonObject params) {
		JsonObject update = updateObject(params);
		if (update == null || !"current_mode_update".equals(text(update, "sessionUpdate"))) {
			return null;
		}
		return text(update, "modeId");
	}

	private static String text(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) {
			return null;
		}
		return value.getAsString();
	}
}
