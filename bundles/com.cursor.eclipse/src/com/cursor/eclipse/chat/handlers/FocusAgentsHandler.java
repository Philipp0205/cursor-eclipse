package com.cursor.eclipse.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cursor.eclipse.agents.AgentsView;

/** Opens the Agents view and puts keyboard focus in its search field. */
public final class FocusAgentsHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
		try {
			var window = HandlerUtil.getActiveWorkbenchWindowChecked(event);
			window.getActivePage().showView(AgentsView.ID, null, IWorkbenchPage.VIEW_ACTIVATE);
		} catch (Exception e) {
			throw new org.eclipse.core.commands.ExecutionException("Could not focus Cursor Agents", e);
		}
		return null;
	}
}
