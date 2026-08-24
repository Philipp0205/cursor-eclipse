package com.cursor.eclipse.agent;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.cursor.eclipse.acp.AcpClientListener;
import com.cursor.eclipse.acp.AcpConnection;
import com.cursor.eclipse.acp.CursorAgentLaunch;
import com.cursor.eclipse.acp.PermissionDecisions;
import com.cursor.eclipse.acp.SessionUpdates;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class CursorSession implements AutoCloseable, AcpClientListener {

	private final Consumer<String> transcript;
	private final Consumer<String> status;
	private final AtomicReference<AcpConnection> connection = new AtomicReference<>();

	public CursorSession(Consumer<String> transcript, Consumer<String> status) {
		this.transcript = transcript;
		this.status = status;
	}

	public synchronized void start(CursorAgentLaunch launch) throws IOException {
		stop();
		status.accept("Starting " + launch.getExecutable().getAbsolutePath() + " …");
		AcpConnection acp = AcpConnection.connect(launch, this);
		connection.set(acp);
		JsonObject init = acp.initialize();
		authenticateIfNeeded(acp, init);
		String cwd = launch.getWorkingDirectory().getAbsolutePath();
		acp.newSession(cwd);
		status.accept("Connected · session " + acp.getSessionId());
		transcript.accept("[system] Connected to Cursor Agent via ACP.\n");
	}

	public synchronized void prompt(String text) {
		AcpConnection acp = connection.get();
		if (acp == null) {
			throw new IllegalStateException("Not connected");
		}
		transcript.accept("You: " + text + "\n");
		status.accept("Waiting for Cursor…");
		JsonObject result = acp.prompt(text);
		String stop = result.has("stopReason") ? result.get("stopReason").getAsString() : "done";
		status.accept("Idle · " + stop);
		transcript.accept("\n");
	}

	public synchronized void cancel() {
		AcpConnection acp = connection.get();
		if (acp != null) {
			acp.cancel();
		}
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
		}
	}

	@Override
	public JsonObject onRequestPermission(JsonObject params) {
		AtomicReference<JsonObject> decision = new AtomicReference<>(PermissionDecisions.rejectOnce());
		Display display = Display.getDefault();
		display.syncExec(() -> {
			Shell shell = display.getActiveShell();
			if (shell == null) {
				Shell[] shells = display.getShells();
				if (shells.length > 0) {
					shell = shells[0];
				}
			}
			String details = describePermission(params);
			boolean allow = MessageDialog.openQuestion(shell, "Cursor wants to run a tool",
					details + "\n\nAllow this tool call once?");
			decision.set(allow ? PermissionDecisions.allowOnce() : PermissionDecisions.rejectOnce());
		});
		return decision.get();
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
		return "The agent requested permission:\n" + params;
	}

	@Override
	public void close() {
		stop();
	}
}
