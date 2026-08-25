package com.cursor.eclipse.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.acp.AgentLocator;
import com.cursor.eclipse.prefs.PreferenceConstants;

/** Starts the Cursor CLI's browser-based login flow. */
public final class LoginHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		String configured = CursorPlugin.getDefault().getPreferenceStore().getString(PreferenceConstants.AGENT_PATH);
		var executable = AgentLocator.find(configured);
		if (executable.isEmpty()) {
			throw new org.eclipse.core.commands.ExecutionException("Cursor agent binary not found");
		}
		try {
			new ProcessBuilder(executable.get().getAbsolutePath(), "login")
					.redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			if (window != null) {
				MessageDialog.openInformation(window.getShell(), "Cursor sign in",
						"The Cursor CLI login flow has started. Complete sign-in in your browser, then reconnect.");
			}
		} catch (Exception e) {
			throw new org.eclipse.core.commands.ExecutionException("Could not start Cursor login", e);
		}
		return null;
	}
}
