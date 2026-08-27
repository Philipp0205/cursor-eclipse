package com.cursor.eclipse.chat.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cursor.eclipse.agents.AgentsView;

/** Re-reads local chats and cloud agents in the Cursor Agents view. */
public final class RefreshAgentsHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) {
		if (HandlerUtil.getActivePart(event) instanceof AgentsView view) {
			view.refresh();
			return null;
		}
		IWorkbenchPage page = HandlerUtil.getActiveWorkbenchWindow(event) == null ? null
				: HandlerUtil.getActiveWorkbenchWindow(event).getActivePage();
		IViewPart part = page == null ? null : page.findView(AgentsView.ID);
		if (part instanceof AgentsView view) {
			view.refresh();
		}
		return null;
	}
}
