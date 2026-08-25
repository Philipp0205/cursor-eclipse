package com.cursor.eclipse.agent;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Passes launch roots to independently instantiated Eclipse chat views. */
public final class SessionLaunchRegistry {

	private static final Map<String, File> ROOTS = new ConcurrentHashMap<>();

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

	public static void remove(String secondaryId) {
		if (secondaryId != null) {
			ROOTS.remove(secondaryId);
		}
	}
}
