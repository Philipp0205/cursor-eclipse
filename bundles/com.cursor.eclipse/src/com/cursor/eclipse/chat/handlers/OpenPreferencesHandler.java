package com.cursor.eclipse.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.handlers.HandlerUtil;

public class OpenPreferencesHandler extends AbstractHandler {

	private static final String PAGE_ID = "com.cursor.eclipse.prefs";

	@Override
	public Object execute(ExecutionEvent event) {
		PreferencesUtil
				.createPreferenceDialogOn(HandlerUtil.getActiveShell(event), PAGE_ID, new String[] { PAGE_ID }, null)
				.open();
		return null;
	}
}
