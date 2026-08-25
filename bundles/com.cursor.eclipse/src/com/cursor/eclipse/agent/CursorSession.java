package com.cursor.eclipse.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.cursor.eclipse.acp.AcpClientListener;
import com.cursor.eclipse.acp.AcpConnection;
import com.cursor.eclipse.acp.CursorAgentLaunch;
import com.cursor.eclipse.acp.McpConfig;
import com.cursor.eclipse.acp.PermissionDecisions;
import com.cursor.eclipse.acp.SessionUpdates;
import com.cursor.eclipse.workspace.WorkspaceFiles;
import com.cursor.eclipse.workspace.WorkspaceTerminals;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Owns one {@code agent acp} process and translates ACP traffic into
 * {@link SessionListener} events.
 *
 * <p>No method holds a lock while calling the listener, so the UI thread can
 * always cancel or disconnect while a prompt is in flight.
 */
public final class CursorSession implements AutoCloseable, AcpClientListener {

	private final SessionListener listener;
	private final AtomicReference<AcpConnection> connection = new AtomicReference<>();
	private final AtomicBoolean cancelling = new AtomicBoolean();
	private final AtomicBoolean busy = new AtomicBoolean();
	private volatile String modelConfigId;
	private volatile boolean canLoadSession;
	private final WorkspaceTerminals terminals = new WorkspaceTerminals();

	public CursorSession(SessionListener listener) {
		this.listener = listener;
	}

	/** Starts the agent and opens a session. Blocking; call from a worker thread. */
	public void start(CursorAgentLaunch launch) throws IOException {
		stop();
		AcpConnection acp = AcpConnection.connect(launch, this);
		connection.set(acp);
		try {
			JsonObject init = acp.initialize();
			canLoadSession = supportsLoadSession(init);
			authenticateIfNeeded(acp, init);
			JsonObject session = acp.newSession(launch.getWorkingDirectory().getAbsolutePath(),
					McpConfig.discover(launch.getWorkingDirectory()));
			publishConnected(acp, session);
		} catch (RuntimeException e) {
			stop();
			throw e;
		}
	}

	/** Starts the agent and loads an existing session without creating a throwaway one. */
	public void startForResume(CursorAgentLaunch launch, String sessionId) throws IOException {
		stop();
		AcpConnection acp = AcpConnection.connect(launch, this);
		connection.set(acp);
		try {
			JsonObject init = acp.initialize();
			canLoadSession = supportsLoadSession(init);
			if (!canLoadSession) {
				throw new IllegalStateException("This Cursor agent does not support resuming ACP sessions");
			}
			authenticateIfNeeded(acp, init);
			JsonObject session = acp.loadSession(sessionId, launch.getWorkingDirectory().getAbsolutePath(),
					McpConfig.discover(launch.getWorkingDirectory()));
			if (session.has("modes") || session.has("configOptions") || session.has("models")) {
				publishConnected(acp, session);
			} else {
				listener.onConnected(sessionId, List.of(), null);
			}
		} catch (RuntimeException e) {
			stop();
			throw e;
		}
	}

	/** Sends a prompt and returns its stop reason. Blocking; call from a worker thread. */
	public String prompt(JsonArray prompt) {
		AcpConnection acp = require();
		cancelling.set(false);
		busy.set(true);
		try {
			JsonObject result = acp.prompt(prompt);
			return string(result, "stopReason", "end_turn");
		} finally {
			busy.set(false);
		}
	}

	/** Starts a new conversation on the running agent. Blocking; call from a worker thread. */
	public void newSession(String cwd) {
		AcpConnection acp = require();
		java.io.File directory = new java.io.File(cwd);
		publishConnected(acp, acp.newSession(cwd, McpConfig.discover(directory)));
	}

	/** Restores an ACP thread when supported by the connected agent. */
	public void loadSession(String sessionId, String cwd) {
		if (!canLoadSession) {
			throw new IllegalStateException("This Cursor agent does not support resuming ACP sessions");
		}
		AcpConnection acp = require();
		java.io.File directory = new java.io.File(cwd);
		publishConnected(acp, acp.loadSession(sessionId, cwd, McpConfig.discover(directory)));
	}

	/** Switches agent mode. Blocking; call from a worker thread. */
	public void setMode(String modeId) {
		require().setMode(modeId);
	}

	/** Switches the model advertised by the current ACP session. */
	public void setModel(String modelId) {
		String configId = modelConfigId;
		if (configId == null) {
			throw new IllegalStateException("The agent did not advertise a model selector");
		}
		JsonObject result = require().setConfigOption(configId, modelId);
		if (result.has("configOptions")) {
			publishConfigModels(result.getAsJsonArray("configOptions"));
		}
	}

	/** Requests cancellation of the current turn. Returns immediately. */
	public void cancel() {
		AcpConnection acp = connection.get();
		if (acp == null) {
			return;
		}
		cancelling.set(true);
		acp.cancel();
	}

	/** Stops the agent process. Safe to call repeatedly. */
	public void stop() {
		terminals.close();
		canLoadSession = false;
		AcpConnection acp = connection.getAndSet(null);
		if (acp == null) {
			return;
		}
		cancelling.set(true);
		busy.set(false);
		acp.close();
		listener.onDisconnected();
	}

	public boolean isConnected() {
		return connection.get() != null;
	}

	public boolean isBusy() {
		return busy.get();
	}

	@Override
	public void close() {
		stop();
		terminals.close();
	}

	// --- ACP callbacks -------------------------------------------------------

	@Override
	public void onSessionUpdate(JsonObject params) {
		JsonObject update = SessionUpdates.updateObject(params);
		if (update == null) {
			return;
		}
		String kind = SessionUpdates.kind(params);
		if (kind == null) {
			return;
		}
		switch (kind) {
		case "agent_message_chunk" -> {
			String text = SessionUpdates.agentTextChunk(params);
			if (text != null) {
				listener.onAgentText(text);
			}
		}
		case "agent_thought_chunk" -> {
			String text = SessionUpdates.agentTextChunk(params);
			if (text != null) {
				listener.onAgentThought(text);
			}
		}
		case "tool_call", "tool_call_update" -> listener.onToolCall(toolCall(update));
		case "plan" -> listener.onPlan(entries(update.getAsJsonArray("entries")));
		case "current_mode_update" -> listener.onModeChanged(string(update, "modeId", null));
		case "config_option_update" -> publishConfigModels(array(update, "configOptions"));
		default -> {
			// Other update kinds carry no user-visible content yet.
		}
		}
	}

	@Override
	public JsonObject onRequestPermission(JsonObject params) {
		if (cancelling.get()) {
			return PermissionDecisions.cancelled();
		}
		List<PermissionOption> options = permissionOptions(params);
		String chosen = listener.askPermission(permissionTitle(params), options);
		if (chosen == null) {
			return PermissionDecisions.rejectOnce();
		}
		return PermissionDecisions.selected(chosen);
	}

	@Override
	public JsonObject onReadTextFile(JsonObject params) {
		try {
			JsonObject result = new JsonObject();
			result.addProperty("content", WorkspaceFiles.read(required(params, "path"),
					number(params, "line"), number(params, "limit")));
			return result;
		} catch (Exception e) {
			throw new IllegalStateException(message(e, "Could not read file"), e);
		}
	}

	@Override
	public JsonObject onWriteTextFile(JsonObject params) {
		try {
			WorkspaceFiles.write(required(params, "path"), required(params, "content"));
			return new JsonObject();
		} catch (Exception e) {
			throw new IllegalStateException(message(e, "Could not write file"), e);
		}
	}

	@Override
	public JsonObject onTerminalRequest(String method, JsonObject params) {
		if ("terminal/create".equals(method)) {
			StringBuilder command = new StringBuilder(string(params, "command", "command"));
			for (JsonElement arg : array(params, "args")) {
				command.append(' ').append(shellQuote(arg.getAsString()));
			}
			String cwd = string(params, "cwd", "");
			String chosen = listener.askPermission("Run terminal command in " + cwd + ":\n" + command,
					List.of(new PermissionOption("allow-once", "Allow once", "allow_once"),
							new PermissionOption("reject-once", "Reject", "reject_once")));
			if (!"allow-once".equals(chosen)) {
				throw new SecurityException("Terminal command rejected");
			}
		}
		return terminals.handle(method, params);
	}

	private static String shellQuote(String value) {
		if (value.matches("[A-Za-z0-9_./:@%+=,-]+")) {
			return value;
		}
		return "'" + value.replace("'", "'\"'\"'") + "'";
	}

	@Override
	public JsonObject onCursorRequest(String method, JsonObject params) {
		return switch (method) {
		case "cursor/ask_question" -> answerQuestions(params);
		case "cursor/create_plan" -> approvePlan(params);
		default -> AcpClientListener.super.onCursorRequest(method, params);
		};
	}

	@Override
	public void onNotification(String method, JsonObject params) {
		switch (method) {
		case "cursor/update_todos" -> listener.onTodos(entries(params.getAsJsonArray("todos")));
		case "cursor/task" -> listener.onNotice("Subagent: " + string(params, "description", "task completed"));
		case "cursor/generate_image" -> listener.onGeneratedImage(string(params, "filePath", "generated"));
		default -> {
			// Unknown notifications are ignored so new agent features do not break the view.
		}
		}
	}

	@Override
	public void onTransportError(Throwable error) {
		listener.onError(message(error, "The Cursor agent connection failed"));
	}

	// --- helpers -------------------------------------------------------------

	private void publishConnected(AcpConnection acp, JsonObject session) {
		List<SessionMode> modes = new ArrayList<>();
		String current = null;
		if (session.has("modes") && session.get("modes").isJsonObject()) {
			JsonObject state = session.getAsJsonObject("modes");
			current = string(state, "currentModeId", null);
			if (state.has("availableModes") && state.get("availableModes").isJsonArray()) {
				for (JsonElement element : state.getAsJsonArray("availableModes")) {
					if (!element.isJsonObject()) {
						continue;
					}
					JsonObject mode = element.getAsJsonObject();
					String id = string(mode, "id", null);
					if (id != null) {
						modes.add(new SessionMode(id, string(mode, "name", id), string(mode, "description", "")));
					}
				}
			}
		}
		listener.onConnected(acp.getSessionId(), List.copyOf(modes), current);
		publishModels(session);
	}

	private void publishModels(JsonObject session) {
		if (session.has("configOptions") && session.get("configOptions").isJsonArray()) {
			publishConfigModels(session.getAsJsonArray("configOptions"));
			return;
		}
		modelConfigId = null;
		listener.onModelsChanged(List.of(), null);
	}

	private void publishConfigModels(JsonArray options) {
		for (JsonElement element : options) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject selector = element.getAsJsonObject();
			if (!"model".equals(string(selector, "category", null))) {
				continue;
			}
			String configId = string(selector, "id", null);
			if (configId == null) {
				continue;
			}
			modelConfigId = configId;
			List<SessionModel> models = new ArrayList<>();
			for (JsonElement candidate : array(selector, "options")) {
				if (!candidate.isJsonObject()) {
					continue;
				}
				JsonObject model = candidate.getAsJsonObject();
				String value = string(model, "value", null);
				if (value != null) {
					models.add(new SessionModel(configId, value, string(model, "name", value),
							string(model, "description", "")));
				}
			}
			listener.onModelsChanged(List.copyOf(models), string(selector, "currentValue", null));
			return;
		}
		modelConfigId = null;
		listener.onModelsChanged(List.of(), null);
	}

	private JsonObject answerQuestions(JsonObject params) {
		JsonArray answers = new JsonArray();
		JsonArray questions = array(params, "questions");
		String title = string(params, "title", "Cursor needs input");
		for (JsonElement element : questions) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject question = element.getAsJsonObject();
			List<PermissionOption> options = new ArrayList<>();
			for (JsonElement option : array(question, "options")) {
				JsonObject value = option.getAsJsonObject();
				String id = string(value, "id", null);
				if (id != null) {
					options.add(new PermissionOption(id, string(value, "label", id), null));
				}
			}
			String chosen = listener.askQuestion(title, string(question, "prompt", "Choose an option"), options);
			if (chosen == null) {
				return outcome("skipped");
			}
			JsonObject answer = new JsonObject();
			answer.addProperty("questionId", string(question, "id", ""));
			JsonArray selected = new JsonArray();
			selected.add(chosen);
			answer.add("selectedOptionIds", selected);
			answers.add(answer);
		}
		JsonObject answered = new JsonObject();
		answered.addProperty("outcome", "answered");
		answered.add("answers", answers);
		JsonObject result = new JsonObject();
		result.add("outcome", answered);
		return result;
	}

	private JsonObject approvePlan(JsonObject params) {
		String markdown = string(params, "plan", string(params, "overview", "Cursor proposed a plan."));
		boolean accepted = listener.askPlanApproval(string(params, "name", "Cursor plan"), markdown);
		return outcome(accepted ? "accepted" : "rejected");
	}

	private static List<PermissionOption> permissionOptions(JsonObject params) {
		List<PermissionOption> options = new ArrayList<>();
		for (JsonElement element : array(params, "options")) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject option = element.getAsJsonObject();
			String id = string(option, "optionId", null);
			if (id != null) {
				options.add(new PermissionOption(id, string(option, "name", id), string(option, "kind", null)));
			}
		}
		if (options.isEmpty()) {
			options.add(new PermissionOption("allow-once", "Allow once", "allow_once"));
			options.add(new PermissionOption("reject-once", "Reject", "reject_once"));
		}
		return options;
	}

	private static String permissionTitle(JsonObject params) {
		if (params.has("toolCall") && params.get("toolCall").isJsonObject()) {
			JsonObject call = params.getAsJsonObject("toolCall");
			String title = string(call, "title", null);
			if (title != null) {
				return title;
			}
		}
		return "Cursor wants to run a tool";
	}

	private static ToolCall toolCall(JsonObject update) {
		return new ToolCall(string(update, "toolCallId", "tool"), string(update, "title", null),
				string(update, "kind", null), string(update, "status", null), toolDetail(update));
	}

	private static String toolDetail(JsonObject update) {
		if (update.has("locations") && update.get("locations").isJsonArray()) {
			JsonArray locations = update.getAsJsonArray("locations");
			if (!locations.isEmpty() && locations.get(0).isJsonObject()) {
				return string(locations.get(0).getAsJsonObject(), "path", null);
			}
		}
		if (update.has("rawInput") && update.get("rawInput").isJsonObject()) {
			JsonObject input = update.getAsJsonObject("rawInput");
			for (String key : new String[] { "path", "filePath", "command", "pattern", "query" }) {
				String value = string(input, key, null);
				if (value != null) {
					return value;
				}
			}
		}
		return null;
	}

	private static List<PlanEntry> entries(JsonArray array) {
		List<PlanEntry> entries = new ArrayList<>();
		if (array != null) {
			for (JsonElement element : array) {
				if (!element.isJsonObject()) {
					continue;
				}
				JsonObject entry = element.getAsJsonObject();
				entries.add(new PlanEntry(string(entry, "content", ""), string(entry, "status", "pending")));
			}
		}
		return List.copyOf(entries);
	}

	private static void authenticateIfNeeded(AcpConnection acp, JsonObject init) {
		if (!init.has("authMethods") || !init.get("authMethods").isJsonArray()) {
			return;
		}
		JsonArray methods = init.getAsJsonArray("authMethods");
		if (methods.isEmpty()) {
			return;
		}
		for (JsonElement element : methods) {
			if (element.isJsonObject() && "cursor_login".equals(string(element.getAsJsonObject(), "id", null))) {
				acp.authenticate("cursor_login");
				return;
			}
		}
		String first = methods.get(0).isJsonObject() ? string(methods.get(0).getAsJsonObject(), "id", null) : null;
		if (first != null) {
			acp.authenticate(first);
		}
	}

	private static boolean supportsLoadSession(JsonObject init) {
		if (!init.has("agentCapabilities") || !init.get("agentCapabilities").isJsonObject()) {
			return false;
		}
		JsonObject capabilities = init.getAsJsonObject("agentCapabilities");
		return capabilities.has("loadSession") && capabilities.get("loadSession").getAsBoolean();
	}

	private AcpConnection require() {
		AcpConnection acp = connection.get();
		if (acp == null) {
			throw new IllegalStateException("Not connected to the Cursor agent");
		}
		return acp;
	}

	private static JsonObject outcome(String value) {
		JsonObject outcome = new JsonObject();
		outcome.addProperty("outcome", value);
		JsonObject result = new JsonObject();
		result.add("outcome", outcome);
		return result;
	}

	private static JsonArray array(JsonObject object, String key) {
		return object != null && object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key)
				: new JsonArray();
	}

	private static Integer number(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsInt() : null;
	}

	private static String required(JsonObject object, String key) {
		String value = string(object, key, null);
		if (value == null) {
			throw new IllegalArgumentException("The agent omitted the required field '" + key + "'");
		}
		return value;
	}

	private static String string(JsonObject object, String key, String fallback) {
		return object != null && object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString()
				: fallback;
	}

	private static String message(Throwable error, String fallback) {
		String message = error.getMessage();
		return message == null || message.isBlank() ? fallback : message;
	}
}
