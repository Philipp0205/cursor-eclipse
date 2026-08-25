package com.cursor.eclipse.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens Eclipse's Git staging/diff surface when EGit is installed. */
public final class OpenReviewHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) {
			return null;
		}
		try {
			window.getActivePage().showView("org.eclipse.egit.ui.GitStagingView");
		} catch (Exception e) {
			MessageDialog.openInformation(window.getShell(), "Review agent changes",
					"Install EGit to review, stage, commit, and push agent changes inside Eclipse.");
		}
		return null;
	}
}
