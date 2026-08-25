package com.cursor.eclipse.chat.handlers;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.handlers.HandlerUtil;

import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.agent.SessionLaunchRegistry;
import com.cursor.eclipse.chat.ChatView;

/** Opens another independent local agent, optionally in an isolated worktree. */
public final class NewAgentHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) {
			return null;
		}
		InputDialog dialog = new InputDialog(window.getShell(), "New Cursor agent", "Agent name:", "agent", value ->
				value == null || value.isBlank() ? "Enter a name" : null);
		if (dialog.open() != Window.OK) {
			return null;
		}
		String name = safeName(dialog.getValue());
		File root = LaunchFactory.workingDirectory();
		boolean isolated = MessageDialog.openQuestion(window.getShell(), "Isolate changes?",
				"Create this agent in a separate Git worktree?");
		if (isolated) {
			try {
				root = createWorktree(root, name);
			} catch (Exception e) {
				throw new org.eclipse.core.commands.ExecutionException("Could not create Cursor worktree", e);
			}
		}
		String secondaryId = name + "-" + Instant.now().toEpochMilli();
		SessionLaunchRegistry.put(secondaryId, root);
		try {
			window.getActivePage().showView(ChatView.ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE);
		} catch (Exception e) {
			SessionLaunchRegistry.remove(secondaryId);
			throw new org.eclipse.core.commands.ExecutionException("Could not open Cursor agent view", e);
		}
		return null;
	}

	private static File createWorktree(File repository, String name) throws Exception {
		File container = new File(LaunchFactory.workspaceDirectory(), ".cursor-worktrees");
		Files.createDirectories(container.toPath());
		File target = new File(container, name + "-" + Instant.now().toEpochMilli());
		String branch = "cursor/eclipse-" + name + "-" + Long.toString(Instant.now().toEpochMilli(), 36);
		Process process = new ProcessBuilder("git", "worktree", "add", "-b", branch, target.getAbsolutePath())
				.directory(repository).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		if (process.waitFor() != 0) {
			throw new IllegalStateException(output.isBlank() ? "git worktree add failed" : output.trim());
		}
		return target;
	}

	private static String safeName(String value) {
		String name = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-").replaceAll("(^-+|-+$)", "");
		return name.isBlank() ? "agent" : name;
	}
}
