package com.cursor.eclipse.acp;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How to launch Cursor CLI in ACP mode ({@code agent acp}).
 */
public final class CursorAgentLaunch {

	private final File executable;
	private final List<String> arguments;
	private final File workingDirectory;
	private final Map<String, String> extraEnv;

	private CursorAgentLaunch(File executable, List<String> arguments, File workingDirectory,
			Map<String, String> extraEnv) {
		this.executable = executable;
		this.arguments = List.copyOf(arguments);
		this.workingDirectory = workingDirectory;
		this.extraEnv = Map.copyOf(extraEnv);
	}

	public File getExecutable() {
		return executable;
	}

	public List<String> getArguments() {
		return arguments;
	}

	public File getWorkingDirectory() {
		return workingDirectory;
	}

	public Map<String, String> getExtraEnv() {
		return extraEnv;
	}

	public List<String> commandLine() {
		List<String> command = new ArrayList<>();
		command.add(executable.getAbsolutePath());
		command.addAll(arguments);
		return command;
	}

	public static Builder builder(File executable) {
		return new Builder(executable);
	}

	public static final class Builder {
		private final File executable;
		private final List<String> arguments = new ArrayList<>();
		private File workingDirectory = new File(".");
		private final Map<String, String> extraEnv = new LinkedHashMap<>();

		private Builder(File executable) {
			this.executable = Objects.requireNonNull(executable, "executable");
			this.arguments.add("acp");
		}

		public Builder arguments(List<String> arguments) {
			this.arguments.clear();
			this.arguments.addAll(arguments);
			return this;
		}

		public Builder workingDirectory(File workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		public Builder apiKey(String apiKey) {
			if (apiKey != null && !apiKey.isBlank()) {
				extraEnv.put("CURSOR_API_KEY", apiKey);
			}
			return this;
		}

		public Builder env(String key, String value) {
			extraEnv.put(key, value);
			return this;
		}

		public CursorAgentLaunch build() {
			return new CursorAgentLaunch(executable, arguments, workingDirectory, extraEnv);
		}
	}
}
