package com.cursor.eclipse.acp;

import com.google.gson.JsonObject;

/**
 * Standard ACP permission replies used by Cursor CLI.
 */
public final class PermissionDecisions {

	private PermissionDecisions() {
	}

	public static JsonObject selected(String optionId) {
		JsonObject selected = new JsonObject();
		selected.addProperty("outcome", "selected");
		selected.addProperty("optionId", optionId);
		JsonObject result = new JsonObject();
		result.add("outcome", selected);
		return result;
	}

	public static JsonObject allowOnce() {
		return selected("allow-once");
	}

	public static JsonObject rejectOnce() {
		return selected("reject-once");
	}
}
