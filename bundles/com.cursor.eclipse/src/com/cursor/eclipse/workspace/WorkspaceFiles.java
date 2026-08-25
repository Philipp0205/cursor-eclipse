package com.cursor.eclipse.workspace;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;

/**
 * Workspace-confined file access for ACP. Reads observe dirty Eclipse editor
 * buffers and writes go through Eclipse resources to preserve local history and
 * trigger incremental builders.
 */
public final class WorkspaceFiles {

	private WorkspaceFiles() {
	}

	public static String read(String absolutePath, Integer line, Integer limit) throws CoreException, IOException {
		Path path = checkedPath(absolutePath);
		String content = documentContent(path);
		if (content == null) {
			content = Files.readString(path, StandardCharsets.UTF_8);
		}
		if (line == null && limit == null) {
			return content;
		}
		String[] lines = content.split("(?<=\\n)", -1);
		int start = Math.max(0, (line == null ? 1 : line) - 1);
		int end = limit == null ? lines.length : Math.min(lines.length, start + Math.max(0, limit));
		if (start >= lines.length) {
			return "";
		}
		return String.join("", Arrays.copyOfRange(lines, start, end));
	}

	public static void write(String absolutePath, String content) throws CoreException, IOException {
		Path path = checkedPath(absolutePath);
		IPath location = org.eclipse.core.runtime.Path.fromOSString(path.toString());
		IFile file = resolveWorkspaceFile(location);
		if (file == null) {
			writeOutsideProjects(path, content);
			return;
		}
		writeThroughResource(file, location, content);
	}

	private static void writeThroughResource(IFile file, IPath location, String content) throws CoreException {
		ensureParents(file.getParent());

		ITextFileBuffer buffer = FileBuffers.getTextFileBufferManager().getTextFileBuffer(location, LocationKind.LOCATION);
		if (buffer != null && file.exists()) {
			buffer.getDocument().set(content);
			buffer.commit(new NullProgressMonitor(), true);
			return;
		}

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
	 * outside existing projects (including the workspace root itself), so project
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
			if (!project.isOpen() || project.getLocation() == null) {
				continue;
			}
			IPath projectLocation = project.getLocation();
			if (projectLocation.isPrefixOf(location) && !projectLocation.equals(location)) {
				IPath relative = location.makeRelativeTo(projectLocation);
				if (relative.segmentCount() > 0) {
					return project.getFile(relative);
				}
			}
		}
		return null;
	}

	private static String documentContent(Path path) {
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		IPath location = org.eclipse.core.runtime.Path.fromOSString(path.toString());
		ITextFileBuffer buffer = manager.getTextFileBuffer(location, LocationKind.LOCATION);
		return buffer == null ? null : buffer.getDocument().get();
	}

	private static Path checkedPath(String absolutePath) {
		if (absolutePath == null || absolutePath.isBlank()) {
			throw new IllegalArgumentException("ACP file path is required");
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
