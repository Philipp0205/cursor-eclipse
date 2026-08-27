package com.cursor.eclipse.agent;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Safe Git worktree creation for isolated parallel agents. */
public final class GitWorktrees {

	private GitWorktrees() {
	}

	/**
	 * Finds the repository root that contains {@code location}.
	 *
	 * @throws IllegalStateException when the location is not in a Git worktree
	 */
	public static File repositoryRoot(File location) throws IOException, InterruptedException {
		Command result = run(location, "git", "rev-parse", "--show-toplevel");
		if (result.exitCode() != 0 || result.output().isBlank()) {
			throw new IllegalStateException(result.output().isBlank()
					? "The selected folder is not inside a Git repository."
					: result.output());
		}
		File root = new File(result.output().lines().findFirst().orElseThrow().trim()).getCanonicalFile();
		if (!root.isDirectory()) {
			throw new IllegalStateException("Git returned a repository root that does not exist: " + root);
		}
		return root;
	}

	/**
	 * Creates an isolated worktree inside the Eclipse workspace.
	 *
	 * <p>Putting it below the workspace root keeps ACP's workspace confinement
	 * intact even when the worktree is not imported as an Eclipse project.
	 */
	public static File create(File location, File workspace, String displayName) throws IOException, InterruptedException {
		File repository = repositoryRoot(location);
		File container = new File(workspace, ".cursor-worktrees");
		Files.createDirectories(container.toPath());
		String name = safeName(displayName);
		String unique = Long.toString(Instant.now().toEpochMilli(), 36) + "-"
				+ UUID.randomUUID().toString().substring(0, 6);
		File target = new File(container, name + "-" + unique);
		String branch = "cursor/eclipse-" + name + "-" + unique;
		Command result = run(repository, "git", "worktree", "add", "-b", branch, target.getAbsolutePath());
		if (result.exitCode() != 0) {
			throw new IllegalStateException(result.output().isBlank() ? "git worktree add failed" : result.output());
		}
		return target;
	}

	public static String safeName(String value) {
		String name = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-")
				.replaceAll("(^-+|-+$)", "");
		return name.isBlank() ? "agent" : name;
	}

	private static Command run(File directory, String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).directory(directory).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return new Command(process.waitFor(), output);
	}

	private record Command(int exitCode, String output) {
	}
}
