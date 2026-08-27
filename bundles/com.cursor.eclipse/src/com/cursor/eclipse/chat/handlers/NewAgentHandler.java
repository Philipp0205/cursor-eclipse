package com.cursor.eclipse.chat.handlers;

import java.io.File;
import java.time.Instant;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cursor.eclipse.agent.GitWorktrees;
import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.agent.SessionLaunchRegistry;
import com.cursor.eclipse.chat.ChatView;
import com.cursor.eclipse.chat.handlers.NewAgentDialog.Result;
import com.cursor.eclipse.chat.handlers.NewAgentDialog.Target;

/** Opens one or more independent local or cloud-backed agents. */
public final class NewAgentHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
		IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) {
			return null;
		}
		NewAgentDialog dialog = new NewAgentDialog(window.getShell());
		if (dialog.open() != Window.OK) {
			return null;
		}
		Result request = dialog.result();
		File selectedRoot = LaunchFactory.workingDirectory();
		int opened = 0;
		for (int index = 0; index < request.copies(); index++) {
			String displayName = request.copies() == 1 ? request.name() : request.name() + " " + (index + 1);
			File root = selectedRoot;
			try {
				if (request.isolated()) {
					root = GitWorktrees.create(selectedRoot, LaunchFactory.workspaceDirectory(), displayName);
				}
				open(window, displayName, root, request.prompt(), request.target() == Target.CLOUD);
				opened++;
			} catch (Exception e) {
				String summary = opened == 0 ? "No agents were started."
						: opened + " of " + request.copies() + " agents were started.";
				MessageDialog.openError(window.getShell(), "Could not start " + displayName,
						e.getMessage() + "\n\n" + summary);
				break;
			}
		}
		return null;
	}

	private static void open(IWorkbenchWindow window, String displayName, File root, String prompt, boolean inCloud)
			throws Exception {
		String name = GitWorktrees.safeName(displayName);
		String secondaryId = name + "-" + Instant.now().toEpochMilli() + "-"
				+ Integer.toString(displayName.hashCode(), 36).replace('-', 'x');
		SessionLaunchRegistry.put(secondaryId, root);
		SessionLaunchRegistry.putPrompt(secondaryId, prompt, inCloud);
		try {
			window.getActivePage().showView(ChatView.ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE);
		} catch (Exception e) {
			SessionLaunchRegistry.remove(secondaryId);
			throw e;
		}
	}
}
