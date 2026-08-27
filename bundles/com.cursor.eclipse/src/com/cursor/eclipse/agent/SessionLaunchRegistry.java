package com.cursor.eclipse.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.cursor.eclipse.agents.CloudAgent;

/**
 * Tracks open chat views and their working folders.
 *
 * <p>The registry also passes launch roots to independently instantiated chat
 * views. Listeners are used by the Agents view to stay in sync without owning
 * any of the agent processes.
 */
public final class SessionLaunchRegistry {

	private static final Map<String, File> ROOTS = new ConcurrentHashMap<>();
	private static final Map<String, String> RESUMES = new ConcurrentHashMap<>();
	private static final Map<String, PendingPrompt> PROMPTS = new ConcurrentHashMap<>();
	private static final Map<String, CloudAgent> CLOUD_SESSIONS = new ConcurrentHashMap<>();
	private static final Map<String, OpenSession> SESSIONS = new ConcurrentHashMap<>();
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

	public static final String PRIMARY_ID = "primary";

	private SessionLaunchRegistry() {
	}

	public static void put(String secondaryId, File root) {
		if (secondaryId != null && root != null) {
			ROOTS.put(secondaryId, root);
		}
	}

	public static File get(String secondaryId) {
		return secondaryId == null ? null : ROOTS.get(secondaryId);
	}

	/** Asks the chat view opened under {@code secondaryId} to resume {@code sessionId}. */
	public static void putResume(String secondaryId, String sessionId) {
		if (secondaryId != null && sessionId != null && !sessionId.isBlank()) {
			RESUMES.put(secondaryId, sessionId);
		}
	}

	/** Returns and clears the session a newly opened chat view should resume. */
	public static String takeResume(String secondaryId) {
		return secondaryId == null ? null : RESUMES.remove(secondaryId);
	}

	/** Supplies the task that a newly opened agent should start immediately. */
	public static void putPrompt(String secondaryId, String text, boolean inCloud) {
		if (secondaryId != null && text != null && !text.isBlank()) {
			PROMPTS.put(secondaryId, new PendingPrompt(text, inCloud));
		}
	}

	/** Returns and clears a task queued for a newly opened agent view. */
	public static PendingPrompt takePrompt(String secondaryId) {
		return secondaryId == null ? null : PROMPTS.remove(secondaryId);
	}

	/** Opens a chat view backed by an existing Cloud Agent. */
	public static void putCloud(String secondaryId, CloudAgent agent) {
		if (secondaryId != null && agent != null) {
			CLOUD_SESSIONS.put(secondaryId, agent);
		}
	}

	/** Returns and clears the Cloud Agent assigned to a newly opened chat view. */
	public static CloudAgent takeCloud(String secondaryId) {
		return secondaryId == null ? null : CLOUD_SESSIONS.remove(secondaryId);
	}

	public static void register(String secondaryId, String name, File root) {
		String id = key(secondaryId);
		SESSIONS.put(id, new OpenSession(id, secondaryId, name, root, null, "Disconnected"));
		fireChanged();
	}

	public static void update(String secondaryId, String sessionId, String status) {
		String id = key(secondaryId);
		SESSIONS.computeIfPresent(id, (ignored, current) -> new OpenSession(current.id(), current.secondaryId(),
				current.name(), current.root(), sessionId == null ? current.sessionId() : sessionId,
				status == null ? current.status() : status));
		fireChanged();
	}

	public static List<OpenSession> sessions() {
		return List.copyOf(new ArrayList<>(SESSIONS.values()));
	}

	public static void addListener(Runnable listener) {
		if (listener != null) {
			LISTENERS.add(listener);
		}
	}

	public static void removeListener(Runnable listener) {
		LISTENERS.remove(listener);
	}

	public static void remove(String secondaryId) {
		if (secondaryId != null) {
			ROOTS.remove(secondaryId);
			RESUMES.remove(secondaryId);
			PROMPTS.remove(secondaryId);
			CLOUD_SESSIONS.remove(secondaryId);
		}
		SESSIONS.remove(key(secondaryId));
		fireChanged();
	}

	private static String key(String secondaryId) {
		return secondaryId == null ? PRIMARY_ID : secondaryId;
	}

	private static void fireChanged() {
		for (Runnable listener : LISTENERS) {
			listener.run();
		}
	}

	public record OpenSession(String id, String secondaryId, String name, File root, String sessionId, String status) {
	}

	public record PendingPrompt(String text, boolean inCloud) {
	}
}
