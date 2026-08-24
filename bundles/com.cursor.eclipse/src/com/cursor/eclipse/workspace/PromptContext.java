package com.cursor.eclipse.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Creates ACP prompt blocks from the active editor, selection, and selected
 * Project Explorer resources.
 */
public final class PromptContext {

	private PromptContext() {
	}

	public static JsonArray collect(String prompt, IWorkbenchWindow window) {
		JsonArray blocks = new JsonArray();
		blocks.add(text(prompt));
		if (window == null || window.getActivePage() == null) {
			return blocks;
		}

		Set<IFile> files = new LinkedHashSet<>();
		IEditorPart editor = window.getActivePage().getActiveEditor();
		if (editor != null && editor.getEditorInput() instanceof FileEditorInput input) {
			files.add(input.getFile());
			addSelection(blocks, editor, input.getFile());
		}
		if (window.getSelectionService().getSelection() instanceof IStructuredSelection selection) {
			for (Object item : selection.toList()) {
				if (item instanceof IFile file) {
					files.add(file);
				} else if (item instanceof IResource resource && resource.getType() == IResource.FILE) {
					files.add((IFile) resource);
				}
			}
		}
		for (IFile file : files) {
			addResource(blocks, file);
		}
		return blocks;
	}

	private static void addSelection(JsonArray blocks, IEditorPart editor, IFile file) {
		if (!(editor instanceof ITextEditor textEditor)
				|| !(textEditor.getSelectionProvider().getSelection() instanceof ITextSelection selection)
				|| selection.isEmpty()) {
			return;
		}
		JsonObject block = text("Selected text from " + file.getFullPath() + " (line "
				+ (selection.getStartLine() + 1) + "):\n" + selection.getText());
		blocks.add(block);
	}

	private static void addResource(JsonArray blocks, IFile file) {
		if (file.getLocation() == null || !file.exists()) {
			return;
		}
		try {
			String content = WorkspaceFiles.read(file.getLocation().toOSString(), null, null);
			JsonObject resource = new JsonObject();
			resource.addProperty("uri", file.getLocationURI().toString());
			resource.addProperty("mimeType", mimeType(file));
			resource.addProperty("text", content);
			JsonObject block = new JsonObject();
			block.addProperty("type", "resource");
			block.add("resource", resource);
			blocks.add(block);
		} catch (CoreException | IOException ignored) {
			// A missing/unreadable selected resource should not prevent the prompt.
		}
	}

	private static JsonObject text(String value) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", value);
		return block;
	}

	private static String mimeType(IFile file) throws IOException {
		String detected = Files.probeContentType(file.getLocation().toFile().toPath());
		return detected == null ? "text/plain" : detected;
	}
}
