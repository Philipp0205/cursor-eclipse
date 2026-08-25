package com.cursor.eclipse.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.acp.AgentLocator;
import com.cursor.eclipse.acp.CursorAgentLaunch;
import com.cursor.eclipse.prefs.PreferenceConstants;

/** Builds the {@code agent acp} launch from preferences and workspace state. */
public final class LaunchFactory {

	private LaunchFactory() {
	}

	/**
	 * Builds a launch rooted at {@code workingDirectory}.
	 *
	 * <p>Safe to call from any thread: the caller supplies the directory so this
	 * method never reads workbench state.
	 */
	public static CursorAgentLaunch fromPreferences(File workingDirectory) {
		IPreferenceStore store = CursorPlugin.getDefault().getPreferenceStore();
		File executable = AgentLocator.find(store.getString(PreferenceConstants.AGENT_PATH))
				.orElseThrow(() -> new IllegalStateException(
						"Cursor agent binary not found. Set it in Window > Preferences > Cursor, "
								+ "or install the Cursor CLI so 'agent' is on PATH."));
		List<String> args = parseArgs(store.getString(PreferenceConstants.AGENT_ARGS));
		if (args.isEmpty()) {
			args = List.of("acp");
		}
		return CursorAgentLaunch.builder(executable)
				.arguments(args)
				.workingDirectory(workingDirectory == null ? workspaceDirectory() : workingDirectory)
				.apiKey(store.getString(PreferenceConstants.API_KEY))
				.build();
	}

	/** The workspace root on disk. Safe from any thread. */
	public static File workspaceDirectory() {
		IPath location = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		return location != null ? location.toFile() : new File(System.getProperty("user.dir"));
	}

	/**
	 * The best working directory for a new session: the selected project, else the
	 * only open project, else the workspace root.
	 *
	 * <p>Must be called on the SWT thread because it inspects the active editor and
	 * selection.
	 */
	public static File workingDirectory() {
		IProject project = selectedProject();
		if (project != null && project.getLocation() != null) {
			return project.getLocation().toFile();
		}
		return workspaceDirectory();
	}

	private static IProject selectedProject() {
		if (PlatformUI.isWorkbenchRunning()) {
			IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
			IWorkbenchPage page = window == null ? null : window.getActivePage();
			if (page != null) {
				if (page.getSelection() instanceof IStructuredSelection selection
						&& selection.getFirstElement() instanceof IResource resource) {
					IProject project = resource.getProject();
					if (project != null && project.isOpen()) {
						return project;
					}
				}
				IEditorPart editor = page.getActiveEditor();
				if (editor != null && editor.getEditorInput() instanceof FileEditorInput input) {
					IProject project = input.getFile().getProject();
					if (project != null && project.isOpen()) {
						return project;
					}
				}
			}
		}
		List<IProject> open = new ArrayList<>();
		for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
			if (project.isOpen()) {
				open.add(project);
			}
		}
		return open.size() == 1 ? open.get(0) : null;
	}

	static List<String> parseArgs(String raw) {
		if (raw == null || raw.isBlank()) {
			return new ArrayList<>();
		}
		return new ArrayList<>(Arrays.asList(raw.trim().split("\\s+")));
	}
}
