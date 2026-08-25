package com.cursor.eclipse.agent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.cursor.eclipse.acp.AcpClientListener;
import com.cursor.eclipse.acp.AcpConnection;
import com.cursor.eclipse.acp.CursorAgentLaunch;
import com.cursor.eclipse.acp.PermissionDecisions;
import com.cursor.eclipse.acp.SessionUpdates;
import com.cursor.eclipse.workspace.WorkspaceFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class CursorSession implements AutoCloseable, AcpClientListener {

	public record SessionMode(String id, String name, String description) {
	}

	private final Consumer<String> transcript;
	private final Consumer<String> status;
	private final Consumer<List<SessionMode>> modes;
	private final Consumer<String> tasks;
	private final AtomicReference<AcpConnection> connection = new AtomicReference<>();
	private volatile boolean cancelling;

	public CursorSession(Consumer<String> transcript, Consumer<String> status,
			Consumer<List<SessionMode>> modes, Consumer<String> tasks) {
		this.transcript = transcript;
		this.status = status;
		this.modes = modes;
		this.tasks = tasks;
	}

	public synchronized void start(CursorAgentLaunch launch) throws IOException {
		stop();
		status.accept("Starting " + launch.getExecutable().getAbsolutePath() + " …");
		AcpConnection acp = AcpConnection.connect(launch, this);
		connection.set(acp);
		JsonObject init = acp.initialize();
		authenticateIfNeeded(acp, init);
		String cwd = launch.getWorkingDirectory().getAbsolutePath();
		JsonObject session = acp.newSession(cwd);
		publishModes(session);
		status.accept("Connected · session " + acp.getSessionId());
		transcript.accept("[system] Connected to Cursor Agent via ACP.\n");
	}

	public synchronized void prompt(JsonArray prompt, String displayText) {
		AcpConnection acp = connection.get();
		if (acp == null) {
			throw new IllegalStateException("Not connected");
		}
		cancelling = false;
		transcript.accept("You: " + displayText + "\n");
		status.accept("Waiting for Cursor…");
		JsonObject result = acp.prompt(prompt);
		String stop = result.has("stopReason") ? result.get("stopReason").getAsString() : "done";
		status.accept("Idle · " + stop);
		transcript.accept("\n");
	}

	public synchronized void cancel() {
		AcpConnection acp = connection.get();
		if (acp != null) {
			cancelling = true;
			acp.cancel();
			status.accept("Cancelling…");
		}
	}

	public synchronized void newSession(String cwd) {
		AcpConnection acp = requireConnection();
		JsonObject result = acp.newSession(cwd);
		publishModes(result);
		transcript.accept("\n[system] New session " + acp.getSessionId() + "\n");
		status.accept("Connected · session " + acp.getSessionId());
	}

	public void setMode(String modeId) {
		requireConnection().setMode(modeId);
		status.accept("Mode · " + modeId);
	}

	public synchronized void stop() {
		AcpConnection acp = connection.getAndSet(null);
		if (acp != null) {
			acp.close();
			status.accept("Disconnected");
		}
	}

	public boolean isConnected() {
		return connection.get() != null;
	}

	@Override
	public void onSessionUpdate(JsonObject params) {
		String chunk = SessionUpdates.agentTextChunk(params);
		if (chunk != null) {
			transcript.accept(chunk);
			return;
		}
		String tool = SessionUpdates.toolSummary(params);
		if (tool != null) {
			transcript.accept("\n[tool] " + tool + "\n");
			return;
		}
		JsonObject update = SessionUpdates.updateObject(params);
		if (update != null && "plan".equals(SessionUpdates.kind(params))) {
			tasks.accept(formatPlan(update));
		}
	}

	@Override
	public JsonObject onRequestPermission(JsonObject params) {
		if (cancelling) {
			return PermissionDecisions.cancelled();
		}
		AtomicReference<JsonObject> decision = new AtomicReference<>(PermissionDecisions.rejectOnce());
		Display display = Display.getDefault();
		display.syncExec(() -> {
			Shell shell = activeShell(display);
			String details = describePermission(params);
			JsonArray options = params.has("options") && params.get("options").isJsonArray()
					? params.getAsJsonArray("options") : new JsonArray();
			if (options.isEmpty()) {
				boolean allow = MessageDialog.openQuestion(shell, "Cursor wants to run a tool",
						details + "\n\nAllow this tool call once?");
				decision.set(allow ? PermissionDecisions.allowOnce() : PermissionDecisions.rejectOnce());
				return;
			}
			String[] labels = new String[options.size()];
			for (int i = 0; i < options.size(); i++) {
				JsonObject option = options.get(i).getAsJsonObject();
				labels[i] = option.has("name") ? option.get("name").getAsString() : option.get("optionId").getAsString();
			}
			MessageDialog dialog = new MessageDialog(shell, "Cursor wants to run a tool", null, details,
					MessageDialog.QUESTION, labels, 0);
			int selected = dialog.open();
			if (selected >= 0 && selected < options.size()) {
				decision.set(PermissionDecisions.selected(
						options.get(selected).getAsJsonObject().get("optionId").getAsString()));
			}
		});
		return decision.get();
	}

	@Override
	public JsonObject onReadTextFile(JsonObject params) {
		try {
			Integer line = number(params, "line");
			Integer limit = number(params, "limit");
			JsonObject result = new JsonObject();
			result.addProperty("content", WorkspaceFiles.read(requiredString(params, "path"), line, limit));
			return result;
		} catch (CoreException | IOException e) {
			throw new IllegalStateException("Could not read workspace file", e);
		}
	}

	@Override
	public JsonObject onWriteTextFile(JsonObject params) {
		try {
			WorkspaceFiles.write(requiredString(params, "path"), requiredString(params, "content"));
			return new JsonObject();
		} catch (CoreException e) {
			throw new IllegalStateException("Could not write workspace file", e);
		} catch (IOException e) {
			throw new IllegalStateException("Could not write workspace file", e);
		}
	}

	@Override
	public JsonObject onCursorRequest(String method, JsonObject params) {
		return switch (method) {
		case "cursor/ask_question" -> askQuestions(params);
		case "cursor/create_plan" -> confirmPlan(params);
		default -> AcpClientListener.super.onCursorRequest(method, params);
		};
	}

	@Override
	public void onNotification(String method, JsonObject params) {
		switch (method) {
		case "cursor/update_todos" -> tasks.accept(formatTodos(params));
		case "cursor/task" -> transcript.accept("\n[subagent] " + string(params, "description", "Task completed") + "\n");
		case "cursor/generate_image" -> transcript.accept("\n[image] " + string(params, "filePath", "Image generated") + "\n");
		default -> {
		}
		}
	}

	@Override
	public void onTransportError(Throwable error) {
		status.accept("Transport error: " + error.getMessage());
		transcript.accept("\n[error] " + error.getMessage() + "\n");
	}

	private static void authenticateIfNeeded(AcpConnection acp, JsonObject init) {
		if (!init.has("authMethods") || !init.get("authMethods").isJsonArray()) {
			return;
		}
		JsonArray methods = init.getAsJsonArray("authMethods");
		for (JsonElement element : methods) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject method = element.getAsJsonObject();
			if (method.has("id") && "cursor_login".equals(method.get("id").getAsString())) {
				acp.authenticate("cursor_login");
				return;
			}
		}
		if (methods.size() > 0 && methods.get(0).isJsonObject() && methods.get(0).getAsJsonObject().has("id")) {
			acp.authenticate(methods.get(0).getAsJsonObject().get("id").getAsString());
		}
	}

	private static String describePermission(JsonObject params) {
		if (params == null) {
			return "The agent requested permission for a tool call.";
		}
		if (params.has("toolCall") && params.get("toolCall").isJsonObject()) {
			JsonObject call = params.getAsJsonObject("toolCall");
			return string(call, "title", "Cursor requested a tool call");
		}
		return "The agent requested permission:\n" + params;
	}

	private JsonObject askQuestions(JsonObject params) {
		AtomicReference<JsonObject> response = new AtomicReference<>();
		Display.getDefault().syncExec(() -> {
			JsonArray answers = new JsonArray();
			JsonArray questions = params.has("questions") ? params.getAsJsonArray("questions") : new JsonArray();
			for (JsonElement element : questions) {
				JsonObject question = element.getAsJsonObject();
				JsonArray options = question.getAsJsonArray("options");
				String[] labels = new String[options.size() + 1];
				for (int i = 0; i < options.size(); i++) {
					labels[i] = string(options.get(i).getAsJsonObject(), "label", "Option " + (i + 1));
				}
				labels[options.size()] = "Skip";
				MessageDialog dialog = new MessageDialog(activeShell(Display.getDefault()),
						string(params, "title", "Cursor needs input"), null,
						string(question, "prompt", "Choose an option"), MessageDialog.QUESTION, labels, 0);
				int selected = dialog.open();
				if (selected < 0 || selected >= options.size()) {
					response.set(outcome("skipped"));
					return;
				}
				JsonObject answer = new JsonObject();
				answer.addProperty("questionId", requiredString(question, "id"));
				JsonArray selectedIds = new JsonArray();
				selectedIds.add(requiredString(options.get(selected).getAsJsonObject(), "id"));
				answer.add("selectedOptionIds", selectedIds);
				answers.add(answer);
			}
			JsonObject answered = new JsonObject();
			answered.addProperty("outcome", "answered");
			answered.add("answers", answers);
			JsonObject result = new JsonObject();
			result.add("outcome", answered);
			response.set(result);
		});
		return response.get();
	}

	private JsonObject confirmPlan(JsonObject params) {
		AtomicReference<JsonObject> response = new AtomicReference<>();
		Display.getDefault().syncExec(() -> {
			String plan = string(params, "plan", string(params, "overview", "Cursor created a plan."));
			tasks.accept(plan);
			boolean accepted = MessageDialog.openQuestion(activeShell(Display.getDefault()),
					string(params, "name", "Cursor plan"), plan + "\n\nAccept this plan?");
			response.set(outcome(accepted ? "accepted" : "rejected"));
		});
		return response.get();
	}

	private static JsonObject outcome(String value) {
		JsonObject outcome = new JsonObject();
		outcome.addProperty("outcome", value);
		JsonObject result = new JsonObject();
		result.add("outcome", outcome);
		return result;
	}

	private void publishModes(JsonObject session) {
		List<SessionMode> available = new ArrayList<>();
		if (session.has("modes") && session.get("modes").isJsonObject()) {
			JsonObject modeState = session.getAsJsonObject("modes");
			if (modeState.has("availableModes")) {
				for (JsonElement element : modeState.getAsJsonArray("availableModes")) {
					JsonObject mode = element.getAsJsonObject();
					available.add(new SessionMode(requiredString(mode, "id"),
							string(mode, "name", requiredString(mode, "id")), string(mode, "description", "")));
				}
			}
		}
		modes.accept(List.copyOf(available));
	}

	private AcpConnection requireConnection() {
		AcpConnection acp = connection.get();
		if (acp == null) {
			throw new IllegalStateException("Not connected");
		}
		return acp;
	}

	private static Shell activeShell(Display display) {
		Shell shell = display.getActiveShell();
		if (shell == null && display.getShells().length > 0) {
			shell = display.getShells()[0];
		}
		return shell;
	}

	private static Integer number(JsonObject object, String key) {
		return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsInt() : null;
	}

	private static String requiredString(JsonObject object, String key) {
		if (!object.has(key) || object.get(key).isJsonNull()) {
			throw new IllegalArgumentException("Missing required field: " + key);
		}
		return object.get(key).getAsString();
	}

	private static String string(JsonObject object, String key, String fallback) {
		return object != null && object.has(key) && !object.get(key).isJsonNull()
				? object.get(key).getAsString() : fallback;
	}

	private static String formatPlan(JsonObject update) {
		StringBuilder text = new StringBuilder("Plan\n");
		if (update.has("entries")) {
			for (JsonElement element : update.getAsJsonArray("entries")) {
				JsonObject entry = element.getAsJsonObject();
				text.append("• [").append(string(entry, "status", "pending")).append("] ")
						.append(string(entry, "content", "")).append('\n');
			}
		}
		return text.toString();
	}

	private static String formatTodos(JsonObject params) {
		StringBuilder text = new StringBuilder("Todos\n");
		if (params.has("todos")) {
			for (JsonElement element : params.getAsJsonArray("todos")) {
				JsonObject todo = element.getAsJsonObject();
				text.append("• [").append(string(todo, "status", "pending")).append("] ")
						.append(string(todo, "content", "")).append('\n');
			}
		}
		return text.toString();
	}

	@Override
	public void close() {
		stop();
	}
}
