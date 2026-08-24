package com.cursor.eclipse.prefs;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.cursor.eclipse.CursorPlugin;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

	@Override
	public void initializeDefaultPreferences() {
		IPreferenceStore store = CursorPlugin.getDefault().getPreferenceStore();
		store.setDefault(PreferenceConstants.AGENT_PATH, "");
		store.setDefault(PreferenceConstants.AGENT_ARGS, "acp");
		store.setDefault(PreferenceConstants.API_KEY, "");
	}
}
