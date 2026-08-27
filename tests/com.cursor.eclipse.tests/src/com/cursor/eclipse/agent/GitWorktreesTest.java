package com.cursor.eclipse.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public class GitWorktreesTest {

	@Test
	public void findsTheRepositoryRootFromANestedProject() throws Exception {
		Path repository = repository();
		Path nested = Files.createDirectories(repository.resolve("services/orders"));

		assertEquals(repository.toRealPath().toFile(), GitWorktrees.repositoryRoot(nested.toFile()));
	}

	@Test
	public void createsAnIsolatedWorktreeInsideTheWorkspace() throws Exception {
		Path repository = repository();
		Path nested = Files.createDirectories(repository.resolve("services/orders"));
		Path workspace = Files.createTempDirectory("cursor-workspace");

		File worktree = GitWorktrees.create(nested.toFile(), workspace.toFile(), "Orders API");

		assertTrue(worktree.isDirectory());
		assertTrue(worktree.toPath().startsWith(workspace.resolve(".cursor-worktrees")));
		assertEquals("true", git(worktree.toPath(), "rev-parse", "--is-inside-work-tree"));
		assertTrue(git(worktree.toPath(), "branch", "--show-current").startsWith("cursor/eclipse-orders-api-"));
	}

	@Test
	public void rejectsFoldersOutsideAGitRepository() throws Exception {
		Path directory = Files.createTempDirectory("not-a-repository");

		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> GitWorktrees.repositoryRoot(directory.toFile()));

		assertTrue(error.getMessage().contains("not a git repository")
				|| error.getMessage().contains("not inside a Git repository"));
	}

	@Test
	public void makesNamesSafeForBranchesAndViewIds() {
		assertEquals("orders-api-2", GitWorktrees.safeName(" Orders API #2 "));
		assertEquals("agent", GitWorktrees.safeName("!!!"));
	}

	private static Path repository() throws Exception {
		Path repository = Files.createTempDirectory("cursor-repository");
		git(repository, "init");
		git(repository, "config", "user.name", "Cursor Test");
		git(repository, "config", "user.email", "test@example.com");
		Files.writeString(repository.resolve("README.md"), "test\n");
		git(repository, "add", "README.md");
		git(repository, "commit", "-m", "Initial");
		return repository;
	}

	private static String git(Path directory, String... arguments) throws Exception {
		String[] command = new String[arguments.length + 1];
		command[0] = "git";
		System.arraycopy(arguments, 0, command, 1, arguments.length);
		Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
		int exit = process.waitFor();
		if (exit != 0) {
			throw new AssertionError(String.join(" ", command) + " failed: " + output);
		}
		return output;
	}
}
