package com.cursor.eclipse.agent;

import java.util.List;

/**
 * Events raised by {@link CursorSession}.
 *
 * <p>Every callback arrives on a worker thread. Implementations must marshal to
 * the SWT thread before touching widgets. The three {@code ask*} callbacks block
 * the calling worker until the user answers, which is safe because inbound agent
 * requests are dispatched off the protocol reader thread.
 */
public interface SessionListener {

	void onConnected(String sessionId, List<SessionMode> modes, String currentModeId);

	default void onModelsChanged(List<SessionModel> models, String currentModelId) {
		// optional
	}

	void onDisconnected();

	/** A chunk of streamed assistant text. */
	void onAgentText(String text);

	/** A chunk of streamed reasoning text. */
	void onAgentThought(String text);

	void onToolCall(ToolCall call);

	void onPlan(List<PlanEntry> entries);

	void onTodos(List<PlanEntry> todos);

	void onModeChanged(String modeId);

	default void onModelChanged(String modelId) {
		// optional
	}

	/** Informational message for the transcript, such as a subagent result. */
	void onNotice(String message);

	default void onGeneratedImage(String filePath) {
		onNotice("Image: " + filePath);
	}

	/** A recoverable failure that should be shown without a modal dialog. */
	void onError(String message);

	/**
	 * Asks the user to approve a tool call.
	 *
	 * @return the chosen option id, or {@code null} to reject
	 */
	String askPermission(String title, List<PermissionOption> options);

	/**
	 * Asks the user a multiple-choice question.
	 *
	 * @return the chosen option id, or {@code null} when skipped
	 */
	String askQuestion(String title, String prompt, List<PermissionOption> options);

	/** Asks the user to approve a proposed plan. */
	boolean askPlanApproval(String name, String markdown);
}
