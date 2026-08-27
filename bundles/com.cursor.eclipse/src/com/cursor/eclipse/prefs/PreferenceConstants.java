package com.cursor.eclipse.prefs;

import com.cursor.eclipse.CursorPlugin;

public final class PreferenceConstants {

	private PreferenceConstants() {
	}

	public static final String AGENT_PATH = CursorPlugin.PLUGIN_ID + ".agentPath";
	public static final String AGENT_ARGS = CursorPlugin.PLUGIN_ID + ".agentArgs";
	public static final String API_KEY = CursorPlugin.PLUGIN_ID + ".apiKey";
	public static final String DEFAULT_CWD = CursorPlugin.PLUGIN_ID + ".defaultCwd";
	public static final String AUTO_START = CursorPlugin.PLUGIN_ID + ".autoStart";
	public static final String SHOW_ARCHIVED_CLOUD_AGENTS = CursorPlugin.PLUGIN_ID + ".showArchivedCloudAgents";
}
