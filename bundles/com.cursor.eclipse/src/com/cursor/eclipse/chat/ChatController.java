package com.cursor.eclipse.chat;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.agent.CursorSession;
import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.agent.PermissionOption;
import com.cursor.eclipse.agent.PlanEntry;
import com.cursor.eclipse.agent.SessionListener;
import com.cursor.eclipse.agent.SessionModel;
import com.cursor.eclipse.agent.SessionMode;
import com.cursor.eclipse.agent.ToolCall;
import com.google.gson.JsonArray;

/**
 * Owns the conversation lifecycle for one {@link ChatView}.
 *
 * <p>All agent work runs on named worker threads; every view update is posted
 * back through {@link ChatView#ui(Runnable)}. Streamed text is buffered and
 * flushed on a timer so a fast stream cannot saturate the SWT event queue.
 */
final class ChatController implements SessionListener {

	private static final int FLUSH_DELAY_MS = 60;

	private final ChatView view;
	private final CursorSession session = new CursorSession(this);

	private final StringBuilder assistantText = new StringBuilder();
	private final StringBuilder thoughtText = new StringBuilder();
	private final StringBuilder userText = new StringBuilder();
	private final AtomicBoolean flushScheduled = new AtomicBoolean();
	private final AtomicInteger turn = new AtomicInteger();
	private final AtomicReference<String> pendingStatus = new AtomicReference<>();
	private volatile boolean busy;
	private volatile boolean connecting;

	ChatController(ChatView view) {
		this.view = view;
	}

	boolean isBusy() {
		return busy;
	}

	boolean isConnected() {
		return session.isConnected();
	}

	boolean isConnecting() {
		return connecting;
	}

	/**
	 * Sends a prompt, connecting first when needed.
	 *
	 * @param prompt           ACP content blocks, already collected on the UI thread
	 * @param displayText      the text to show in the transcript
	 * @param workingDirectory session root, already resolved on the UI thread
	 */
	void send(JsonArray prompt, String displayText, File workingDirectory) {
		if (busy) {
			return;
		}
		busy = true;
		// A replay leaves its last turn buffered, and none of it belongs here.
		clearBuffers();
		int current = turn.incrementAndGet();
		String userId = "user-" + current;
		view.putBlock(userId, ConversationHtml.message(userId, "user", displayText));
		view.putBlock(activityId(current), ConversationHtml.activity(activityId(current), "Working"));
		view.refreshState("Sending");

		worker("cursor-prompt", () -> {
			try {
				if (!session.isConnected()) {
					connecting = true;
					view.refreshState("Starting Cursor agent");
					session.start(LaunchFactory.fromPreferences(workingDirectory));
				}
				view.refreshState("Working");
				String stop = session.prompt(prompt);
				finishTurn(current, stopLabel(stop));
			} catch (Exception e) {
				String message = describe(e);
				CursorPlugin.log(message, e);
				view.putBlock("error-" + current, ConversationHtml.error("error-" + current, message));
				finishTurn(current, "Failed");
			} finally {
				connecting = false;
			}
		});
	}

	void cancel() {
		if (!busy) {
			return;
		}
		view.refreshState("Stopping");
		worker("cursor-cancel", session::cancel);
	}

	void newSession(File workingDirectory) {
		worker("cursor-new-session", () -> {
			try {
				clearBuffers();
				if (!session.isConnected()) {
					connecting = true;
					session.start(LaunchFactory.fromPreferences(workingDirectory));
				} else {
					session.newSession(workingDirectory.getAbsolutePath());
				}
				view.ui(() -> {
					view.clearConversation();
					view.refreshState("Ready");
				});
			} catch (Exception e) {
				reportError(e);
			} finally {
				connecting = false;
			}
		});
	}

	void loadSession(String sessionId, File workingDirectory) {
		worker("cursor-load-session", () -> {
			try {
				clearBuffers();
				turn.set(0);
				view.clearConversationBeforeReplay();
				if (!session.isConnected()) {
					connecting = true;
					session.startForResume(LaunchFactory.fromPreferences(workingDirectory), sessionId);
				} else {
					session.loadSession(sessionId, workingDirectory.getAbsolutePath());
				}
				view.refreshState("Resumed " + sessionId);
			} catch (Exception e) {
				reportError(e);
			} finally {
				connecting = false;
			}
		});
	}

	void connect(File workingDirectory) {
		if (session.isConnected() || connecting) {
			return;
		}
		connecting = true;
		view.refreshState("Starting Cursor agent");
		worker("cursor-connect", () -> {
			try {
				session.start(LaunchFactory.fromPreferences(workingDirectory));
			} catch (Exception e) {
				reportError(e);
			} finally {
				connecting = false;
				view.refreshState();
			}
		});
	}

	void disconnect() {
		worker("cursor-disconnect", session::stop);
	}

	void setMode(String modeId) {
		if (!session.isConnected()) {
			return;
		}
		worker("cursor-set-mode", () -> {
			try {
				session.setMode(modeId);
			} catch (Exception e) {
				reportError(e);
			}
		});
	}

	void setModel(String modelId) {
		if (!session.isConnected()) {
			return;
		}
		worker("cursor-set-model", () -> {
			try {
				session.setModel(modelId);
			} catch (Exception e) {
				reportError(e);
			}
		});
	}

	/** Stops the agent without blocking the SWT thread. */
	void shutdown() {
		new Thread(session::close, "cursor-shutdown").start();
	}

	// --- SessionListener -----------------------------------------------------

	@Override
	public void onConnected(String sessionId, List<SessionMode> modes, String currentModeId) {
		view.ui(() -> {
			view.setSessionId(sessionId);
			view.setModes(modes, currentModeId);
			view.refreshState("Ready");
		});
	}

	@Override
	public void onModelsChanged(List<SessionModel> models, String currentModelId) {
		view.ui(() -> view.setModels(models, currentModelId));
	}

	@Override
	public void onDisconnected() {
		busy = false;
		view.ui(() -> {
			view.setModes(List.of(), null);
			view.setModels(List.of(), null);
			view.refreshState("Disconnected");
		});
	}

	@Override
	public void onAgentText(String text) {
		synchronized (assistantText) {
			assistantText.append(text);
		}
		scheduleFlush();
	}

	@Override
	public void onAgentThought(String text) {
		synchronized (thoughtText) {
			thoughtText.append(text);
		}
		scheduleFlush();
	}

	/**
	 * A replayed prompt. Anything already buffered belongs to the turn before it,
	 * so that turn is written out and the counter moves on.
	 */
	@Override
	public void onUserText(String text) {
		if (buffered(assistantText) || buffered(thoughtText)) {
			flush();
			clearBuffers();
			turn.incrementAndGet();
		}
		synchronized (userText) {
			userText.append(text);
		}
		scheduleFlush();
	}

	@Override
	public void onToolCall(ToolCall call) {
		String id = "tool-" + turn.get() + "-" + call.id();
		view.putBlock(id, ConversationHtml.tool(id, call));
	}

	@Override
	public void onPlan(List<PlanEntry> entries) {
		renderChecklist("plan-" + turn.get(), "Plan", entries);
	}

	@Override
	public void onTodos(List<PlanEntry> todos) {
		renderChecklist("todos-" + turn.get(), "Todos", todos);
	}

	@Override
	public void onModeChanged(String modeId) {
		view.ui(() -> view.selectMode(modeId));
	}

	@Override
	public void onModelChanged(String modelId) {
		view.ui(() -> view.selectModel(modelId));
	}

	@Override
	public void onNotice(String message) {
		String id = "notice-" + System.nanoTime();
		view.putBlock(id, ConversationHtml.notice(id, message));
	}

	@Override
	public void onGeneratedImage(String filePath) {
		String id = "image-" + System.nanoTime();
		view.putBlock(id, ConversationHtml.image(id, filePath));
	}

	@Override
	public void onError(String message) {
		String id = "error-" + System.nanoTime();
		view.putBlock(id, ConversationHtml.error(id, message));
		view.refreshState("Error");
	}

	@Override
	public String askPermission(String title, List<PermissionOption> options) {
		return choose("Cursor permission", title, options);
	}

	@Override
	public String askQuestion(String title, String prompt, List<PermissionOption> options) {
		return choose(title, prompt, options);
	}

	@Override
	public boolean askPlanApproval(String name, String markdown) {
		String id = "plan-proposal-" + turn.get();
		view.putBlock(id, ConversationHtml.message(id, "assistant", markdown));
		AtomicBoolean accepted = new AtomicBoolean();
		syncUi(shell -> accepted.set(MessageDialog.openConfirm(shell, name,
				"Cursor proposed a plan. It is shown in the conversation.\n\nStart working on it?")));
		return accepted.get();
	}

	// --- helpers -------------------------------------------------------------

	private String choose(String title, String message, List<PermissionOption> options) {
		if (options.isEmpty()) {
			return null;
		}
		String[] labels = options.stream().map(PermissionOption::name).toArray(String[]::new);
		AtomicInteger picked = new AtomicInteger(-1);
		syncUi(shell -> {
			MessageDialog dialog = new MessageDialog(shell, title, null, message, MessageDialog.QUESTION, labels, 0);
			picked.set(dialog.open());
		});
		int index = picked.get();
		return index >= 0 && index < options.size() ? options.get(index).optionId() : null;
	}

	private void syncUi(java.util.function.Consumer<Shell> action) {
		Display display = Display.getDefault();
		if (display.isDisposed()) {
			return;
		}
		display.syncExec(() -> {
			if (!display.isDisposed()) {
				action.accept(view.dialogShell());
			}
		});
	}

	private void renderChecklist(String id, String heading, List<PlanEntry> entries) {
		if (entries.isEmpty()) {
			return;
		}
		StringBuilder markdown = new StringBuilder("**").append(heading).append("**\n\n");
		for (PlanEntry entry : entries) {
			markdown.append("- [").append("completed".equals(entry.status()) ? "x" : " ").append("] ")
					.append(entry.content()).append('\n');
		}
		view.putBlock(id, ConversationHtml.message(id, "system", markdown.toString()));
	}

	private void scheduleFlush() {
		if (!flushScheduled.compareAndSet(false, true)) {
			return;
		}
		Display display = Display.getDefault();
		if (display.isDisposed()) {
			flushScheduled.set(false);
			return;
		}
		display.asyncExec(() -> display.timerExec(FLUSH_DELAY_MS, () -> {
			flushScheduled.set(false);
			flush();
		}));
	}

	private void flush() {
		int current = turn.get();
		String prompt;
		synchronized (userText) {
			prompt = userText.toString();
		}
		if (!prompt.isEmpty()) {
			String id = "user-" + current;
			view.putBlock(id, ConversationHtml.message(id, "user", prompt));
		}
		String thought;
		synchronized (thoughtText) {
			thought = thoughtText.toString();
		}
		if (!thought.isEmpty()) {
			String id = "thought-" + current;
			view.putBlock(id, ConversationHtml.thought(id, thought));
		}
		String text;
		synchronized (assistantText) {
			text = assistantText.toString();
		}
		if (!text.isEmpty()) {
			String id = "assistant-" + current;
			view.putBlock(id, ConversationHtml.message(id, "assistant", text));
			view.removeBlock(activityId(current));
		}
	}

	private void finishTurn(int current, String status) {
		flush();
		clearBuffers();
		busy = false;
		pendingStatus.set(status);
		view.removeBlock(activityId(current));
		view.refreshState(status);
	}

	private void clearBuffers() {
		synchronized (assistantText) {
			assistantText.setLength(0);
		}
		synchronized (thoughtText) {
			thoughtText.setLength(0);
		}
		synchronized (userText) {
			userText.setLength(0);
		}
	}

	private static boolean buffered(StringBuilder text) {
		synchronized (text) {
			return text.length() > 0;
		}
	}

	private void reportError(Exception e) {
		String message = describe(e);
		CursorPlugin.log(message, e);
		String id = "error-" + System.nanoTime();
		view.putBlock(id, ConversationHtml.error(id, message));
		view.refreshState("Error");
	}

	private static String activityId(int current) {
		return "activity-" + current;
	}

	private static String stopLabel(String stopReason) {
		return switch (stopReason) {
		case "end_turn" -> "Ready";
		case "cancelled" -> "Cancelled";
		case "max_tokens" -> "Stopped: token limit";
		case "max_turn_requests" -> "Stopped: request limit";
		case "refusal" -> "Stopped: refused";
		default -> "Ready";
		};
	}

	private static String describe(Throwable error) {
		Throwable cause = error;
		while (cause.getCause() != null && (cause.getMessage() == null || cause.getMessage().isBlank())) {
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}

	private static void worker(String name, Runnable task) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		thread.start();
	}
}
