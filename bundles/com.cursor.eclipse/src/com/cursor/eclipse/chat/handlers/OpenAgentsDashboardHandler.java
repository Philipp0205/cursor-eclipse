package com.cursor.eclipse.chat.handlers;

import java.net.URI;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.PlatformUI;

/** Opens Cursor's shared local/cloud agent dashboard. */
public final class OpenAgentsDashboardHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
		try {
			PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser()
					.openURL(URI.create("https://cursor.com/agents").toURL());
		} catch (Exception e) {
			throw new org.eclipse.core.commands.ExecutionException("Could not open Cursor Agents", e);
		}
		return null;
	}
}
