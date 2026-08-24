package com.cursor.eclipse.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentLocatorTest {

	@Test
	void prefersConfiguredExecutable(@TempDir Path dir) throws Exception {
		Path agent = dir.resolve("agent");
		Files.writeString(agent, "#!/bin/sh\n");
		agent.toFile().setExecutable(true);
		assertEquals(agent.toFile(), AgentLocator.find(agent.toString()).orElseThrow());
	}

	@Test
	void rejectsMissingConfiguredPath() {
		assertTrue(AgentLocator.find("/definitely/missing/cursor-agent-bin").isEmpty());
	}

	@Test
	void defaultCandidatesIncludeLocalBin() {
		boolean found = AgentLocator.defaultCandidates().stream()
				.map(File::getPath)
				.anyMatch(path -> path.endsWith("/.local/bin/agent"));
		assertTrue(found);
	}
}
