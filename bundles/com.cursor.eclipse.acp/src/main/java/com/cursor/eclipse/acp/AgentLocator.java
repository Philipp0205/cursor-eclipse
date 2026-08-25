package com.cursor.eclipse.acp;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the Cursor {@code agent} binary.
 */
public final class AgentLocator {

	private AgentLocator() {
	}

	public static Optional<File> find(String configuredPath) {
		if (configuredPath != null && !configuredPath.isBlank()) {
			File file = new File(configuredPath.trim());
			if (file.isFile() && file.canExecute()) {
				return Optional.of(file);
			}
			return Optional.empty();
		}

		for (File candidate : defaultCandidates()) {
			if (candidate.isFile() && candidate.canExecute()) {
				return Optional.of(candidate);
			}
		}
		return which("agent");
	}

	static List<File> defaultCandidates() {
		List<File> files = new ArrayList<>();
		String home = System.getProperty("user.home");
		if (home != null) {
			files.add(new File(home, ".local/bin/agent"));
			files.add(new File(home, ".cursor/bin/agent"));
		}
		String localAppData = System.getenv("LOCALAPPDATA");
		if (localAppData != null) {
			files.add(new File(localAppData, "cursor-agent/agent.exe"));
		}
		return files;
	}

	static Optional<File> which(String name) {
		String path = System.getenv("PATH");
		if (path == null || path.isBlank()) {
			return Optional.empty();
		}
		String[] dirs = path.split(File.pathSeparator);
		for (String dir : dirs) {
			Path candidate = Path.of(dir, name);
			if (Files.isExecutable(candidate) && Files.isRegularFile(candidate)) {
				return Optional.of(candidate.toFile());
			}
			Path windows = Path.of(dir, name + ".exe");
			if (Files.isExecutable(windows) && Files.isRegularFile(windows)) {
				return Optional.of(windows.toFile());
			}
		}
		return Optional.empty();
	}
}
