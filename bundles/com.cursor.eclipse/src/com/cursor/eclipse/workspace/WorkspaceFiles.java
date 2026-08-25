package com.cursor.eclipse.workspace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;

/**
 * Workspace-confined file access for ACP.
 *
 * <p>Reads observe unsaved editor buffers and writes go through Eclipse
 * resources so local history and incremental builders behave as if a user had
 * made the edit. Document access is marshalled to the SWT thread because text
 * buffers belong to open editors.
 */
public final class WorkspaceFiles {

	private WorkspaceFiles() {
	}

	public static String read(String absolutePath, Integer line, Integer limit) throws CoreException, IOException {
		Path path = checkedPath(absolutePath);
		String content = bufferContent(location(path));
		if (content == null) {
			content = Files.readString(path, StandardCharsets.UTF_8);
		}
		if (line == null && limit == null) {
			return content;
		}
		String[] lines = content.split("(?<=\\n)", -1);
		int start = Math.max(0, (line == null ? 1 : line) - 1);
		int end = limit == null ? lines.length : Math.min(lines.length, start + Math.max(0, limit));
		return start >= lines.length ? "" : String.join("", Arrays.copyOfRange(lines, start, end));
	}

	public static void write(String absolutePath, String content) throws CoreException, IOException {
		Path path = checkedPath(absolutePath);
		IPath location = location(path);
		IFile file = resolveWorkspaceFile(location);
		if (file == null) {
			writeOutsideProjects(path, content);
			return;
		}
		if (updateOpenBuffer(location, content)) {
			return;
		}
		IWorkspace workspace = ResourcesPlugin.getWorkspace();
		workspace.run(monitor -> writeThroughResource(file, content), workspace.getRoot(), IWorkspace.AVOID_UPDATE,
				new NullProgressMonitor());
	}

	/**
	 * Applies the edit to the editor's document when the file is open, so the user
	 * sees the change immediately and can undo it.
	 *
	 * @return {@code true} when the open buffer handled the write
	 */
	private static boolean updateOpenBuffer(IPath location, String content) {
		AtomicReference<Boolean> handled = new AtomicReference<>(Boolean.FALSE);
		onDisplayThread(() -> {
			ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(location,
					LocationKind.LOCATION);
			if (buffer == null) {
				return;
			}
			try {
				buffer.getDocument().set(content);
				buffer.commit(new NullProgressMonitor(), true);
				handled.set(Boolean.TRUE);
			} catch (CoreException e) {
				handled.set(Boolean.FALSE);
			}
		});
		return handled.get();
	}

	private static void writeThroughResource(IFile file, String content) throws CoreException {
		ensureParents(file.getParent());
		ByteArrayInputStream bytes = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
		if (file.exists()) {
			file.setContents(bytes, IResource.FORCE | IResource.KEEP_HISTORY, new NullProgressMonitor());
		} else {
			file.create(bytes, IResource.FORCE, new NullProgressMonitor());
		}
	}

	private static void writeOutsideProjects(Path path, String content) throws CoreException, IOException {
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);
		ResourcesPlugin.getWorkspace().getRoot().refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
	}

	/**
	 * Maps an absolute filesystem location to an {@link IFile}. Eclipse returns
	 * {@code null} from {@link IWorkspaceRoot#getFileForLocation(IPath)} for paths
	 * outside existing projects, including the workspace root itself, so project
	 * membership is resolved explicitly.
	 */
	static IFile resolveWorkspaceFile(IPath location) {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IFile file = root.getFileForLocation(location);
		if (file != null) {
			return file;
		}
		IPath workspaceLocation = root.getLocation();
		if (workspaceLocation == null || !workspaceLocation.isPrefixOf(location)) {
			return null;
		}
		for (IProject project : root.getProjects()) {
			IPath projectLocation = project.isOpen() ? project.getLocation() : null;
			if (projectLocation != null && projectLocation.isPrefixOf(location)
					&& !projectLocation.equals(location)) {
				IPath relative = location.makeRelativeTo(projectLocation);
				if (relative.segmentCount() > 0) {
					return project.getFile(relative);
				}
			}
		}
		return null;
	}

	private static String bufferContent(IPath location) {
		AtomicReference<String> content = new AtomicReference<>();
		onDisplayThread(() -> {
			ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(location,
					LocationKind.LOCATION);
			if (buffer != null) {
				content.set(buffer.getDocument().get());
			}
		});
		return content.get();
	}

	private static void onDisplayThread(Runnable runnable) {
		Display display = Display.getCurrent();
		if (display != null) {
			if (!display.isDisposed()) {
				runnable.run();
			}
			return;
		}
		// Display.getDefault() creates a GTK Display when none exists, which
		// fails in headless CI (gtk_init_check / "No more handles") and would
		// pin this worker as the UI thread. Only marshal when the workbench
		// already owns a display, as ACP writes do in a running Eclipse.
		if (!PlatformUI.isWorkbenchRunning()) {
			runnable.run();
			return;
		}
		display = PlatformUI.getWorkbench().getDisplay();
		if (display == null || display.isDisposed()) {
			runnable.run();
			return;
		}
		display.syncExec(runnable);
	}

	private static IPath location(Path path) {
		return org.eclipse.core.runtime.Path.fromOSString(path.toString());
	}

	private static Path checkedPath(String absolutePath) {
		if (absolutePath == null || absolutePath.isBlank()) {
			throw new IllegalArgumentException("The agent did not provide a file path");
		}
		Path path = Path.of(absolutePath).toAbsolutePath().normalize();
		IPath workspaceLocation = ResourcesPlugin.getWorkspace().getRoot().getLocation();
		if (workspaceLocation == null) {
			throw new IllegalStateException("The Eclipse workspace has no local filesystem location");
		}
		Path root = Path.of(workspaceLocation.toOSString()).toAbsolutePath().normalize();
		if (!path.startsWith(root)) {
			throw new SecurityException("Cursor file access is restricted to the Eclipse workspace: " + path);
		}
		return path;
	}

	private static void ensureParents(IContainer container) throws CoreException {
		if (container == null || container.exists() || container.getType() == IResource.ROOT) {
			return;
		}
		ensureParents(container.getParent());
		((IFolder) container).create(IResource.FORCE, true, new NullProgressMonitor());
	}
}
