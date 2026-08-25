package com.cursor.eclipse.workspace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.eclipse.core.resources.ResourcesPlugin;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class WorkspaceTerminalsTest {

	@Test
	public void runsAndCapturesCommandInsideWorkspace() throws Exception {
		try (WorkspaceTerminals terminals = new WorkspaceTerminals()) {
			JsonObject create = new JsonObject();
			create.addProperty("cwd", ResourcesPlugin.getWorkspace().getRoot().getLocation().toOSString());
			create.addProperty("command", "sh");
			JsonArray args = new JsonArray();
			args.add("-c");
			args.add("printf terminal-ok");
			create.add("args", args);
			String id = terminals.handle("terminal/create", create).get("terminalId").getAsString();

			JsonObject target = new JsonObject();
			target.addProperty("terminalId", id);
			assertEquals(0, terminals.handle("terminal/wait_for_exit", target).get("exitCode").getAsInt());
			for (int i = 0; i < 20; i++) {
				String output = terminals.handle("terminal/output", target).get("output").getAsString();
				if (output.contains("terminal-ok")) {
					assertTrue(output.contains("terminal-ok"));
					return;
				}
				Thread.sleep(25);
			}
			assertTrue("terminal output was not captured", false);
		}
	}
}
