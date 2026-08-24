package com.cursor.eclipse.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.preference.IPreferenceStore;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.acp.AgentLocator;
import com.cursor.eclipse.acp.CursorAgentLaunch;
import com.cursor.eclipse.prefs.PreferenceConstants;

public final class LaunchFactory {

	private LaunchFactory() {
	}

	public static CursorAgentLaunch fromPreferences() {
		IPreferenceStore store = CursorPlugin.getDefault().getPreferenceStore();
		File executable = AgentLocator.find(store.getString(PreferenceConstants.AGENT_PATH))
				.orElseThrow(() -> new IllegalStateException(
						"Cursor agent binary not found. Set Window → Preferences → Cursor, or install the CLI so `agent` is on PATH."));
		List<String> args = parseArgs(store.getString(PreferenceConstants.AGENT_ARGS));
		if (args.isEmpty()) {
			args = List.of("acp");
		}
		return CursorAgentLaunch.builder(executable)
				.arguments(args)
				.workingDirectory(workspaceDirectory())
				.apiKey(store.getString(PreferenceConstants.API_KEY))
				.build();
	}

	public static File workspaceDirectory() {
		IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		if (location != null) {
			return location.toFile();
		}
		return new File(System.getProperty("user.dir"));
	}

	static List<String> parseArgs(String raw) {
		if (raw == null || raw.isBlank()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(raw.trim().split("\\s+")));
	}
}
