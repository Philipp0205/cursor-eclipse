package com.cursor.eclipse.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WorkspaceFilesTest {

	private static final String PROJECT_NAME = "WorkspaceFilesTestProject";
	private static final String ROOT_FILE = "FakeAgentDemo.txt";
	private static final String PROJECT_FILE = "NestedWrite.txt";

	@Before
	public void setUp() throws Exception {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IProject project = root.getProject(PROJECT_NAME);
		if (!project.exists()) {
			project.create(new NullProgressMonitor());
		}
		if (!project.isOpen()) {
			project.open(new NullProgressMonitor());
		}
		deleteWorkspaceRootFile(root, ROOT_FILE);
		deleteProjectFile(project, PROJECT_FILE);
	}

	@After
	public void tearDown() throws Exception {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		deleteWorkspaceRootFile(root, ROOT_FILE);
		deleteProjectFile(root.getProject(PROJECT_NAME), PROJECT_FILE);
	}

	@Test
	public void resolveWorkspaceFileReturnsNullForWorkspaceRootOutsideProjects() throws Exception {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		IPath target = root.getLocation().append(ROOT_FILE);
		assertNull(root.getFileForLocation(target));
		assertNull(WorkspaceFiles.resolveWorkspaceFile(target));
	}

	@Test
	public void writeCreatesFileAtWorkspaceRootOutsideProjects() throws Exception {
		IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
		java.nio.file.Path target = root.getLocation().toFile().toPath().resolve(ROOT_FILE).normalize();
		assertFalse("precondition: file must not exist", Files.exists(target));

		String content = "Written through Eclipse IFile via ACP.\n";
		WorkspaceFiles.write(target.toString(), content);

		assertTrue("file should exist on disk", Files.exists(target));
		assertEquals(content, Files.readString(target, StandardCharsets.UTF_8));
	}

	@Test
	public void writeCreatesFileInsideOpenProject() throws Exception {
		IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(PROJECT_NAME);
		java.nio.file.Path target = project.getLocation().toFile().toPath().resolve(PROJECT_FILE).normalize();
		assertFalse("precondition: file must not exist", Files.exists(target));
		assertNotNull(WorkspaceFiles.resolveWorkspaceFile(org.eclipse.core.runtime.Path.fromOSString(target.toString())));

		String content = "project write\n";
		WorkspaceFiles.write(target.toString(), content);

		assertTrue("project file resource should exist", project.getFile(PROJECT_FILE).exists());
		assertTrue("file should exist on disk", Files.exists(target));
		assertEquals(content, Files.readString(target, StandardCharsets.UTF_8));
	}

	@Test
	public void writeRejectsPathsOutsideWorkspace() {
		try {
			WorkspaceFiles.write("/tmp/outside-workspace.txt", "nope");
			fail("expected SecurityException");
		} catch (SecurityException expected) {
			assertTrue(expected.getMessage().contains("restricted to the Eclipse workspace"));
		} catch (Exception e) {
			fail("expected SecurityException but got " + e);
		}
	}

	private static void deleteWorkspaceRootFile(IWorkspaceRoot root, String name) throws Exception {
		java.nio.file.Path path = root.getLocation().toFile().toPath().resolve(name);
		if (Files.exists(path)) {
			Files.delete(path);
		}
		root.refreshLocal(org.eclipse.core.resources.IResource.DEPTH_INFINITE, new NullProgressMonitor());
	}

	private static void deleteProjectFile(IProject project, String name) throws Exception {
		if (project == null || !project.exists()) {
			return;
		}
		IFile file = project.getFile(name);
		if (file.exists()) {
			file.delete(true, new NullProgressMonitor());
		}
	}
}
