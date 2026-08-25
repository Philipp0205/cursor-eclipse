package com.cursor.eclipse.workspace;

import java.util.LinkedHashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.ITextEditor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Builds ACP prompt content from the workbench.
 *
 * <p>Must be called on the SWT thread: it reads the active editor, its
 * selection, and the current workbench selection.
 */
public final class PromptContext {

	private static final int MAX_ATTACHED_FILES = 5;
	private static final int MAX_FILE_CHARACTERS = 60_000;

	private PromptContext() {
	}

	public static JsonArray collect(String prompt, IWorkbenchWindow window) {
		JsonArray blocks = new JsonArray();
		blocks.add(text(prompt));
		IWorkbenchPage page = window == null ? null : window.getActivePage();
		if (page == null) {
			return blocks;
		}

		Set<IFile> files = new LinkedHashSet<>();
		IEditorPart editor = page.getActiveEditor();
		if (editor != null && editor.getEditorInput() instanceof FileEditorInput input) {
			files.add(input.getFile());
			addSelection(blocks, editor, input.getFile());
		}
		if (page.getSelection() instanceof IStructuredSelection selection) {
			for (Object item : selection.toList()) {
				if (item instanceof IFile file) {
					files.add(file);
				}
			}
		}
		for (IEditorReference reference : page.getEditorReferences()) {
			if (files.size() >= MAX_ATTACHED_FILES) {
				break;
			}
			try {
				if (reference.getEditorInput() instanceof FileEditorInput input) {
					files.add(input.getFile());
				}
			} catch (Exception ignored) {
				// A editor whose input cannot be restored is simply not attached.
			}
		}

		int attached = 0;
		for (IFile file : files) {
			if (attached++ >= MAX_ATTACHED_FILES) {
				break;
			}
			addResource(blocks, file);
		}
		return blocks;
	}

	private static void addSelection(JsonArray blocks, IEditorPart editor, IFile file) {
		if (!(editor instanceof ITextEditor textEditor)) {
			return;
		}
		if (!(textEditor.getSelectionProvider().getSelection() instanceof ITextSelection selection)
				|| selection.getText() == null || selection.getText().isBlank()) {
			return;
		}
		blocks.add(text("Selected text in " + file.getFullPath() + " at line " + (selection.getStartLine() + 1) + ":\n"
				+ selection.getText()));
	}

	private static void addResource(JsonArray blocks, IFile file) {
		if (file == null || !file.exists() || file.getLocation() == null) {
			return;
		}
		try {
			String content = WorkspaceFiles.read(file.getLocation().toOSString(), null, null);
			if (content.length() > MAX_FILE_CHARACTERS) {
				content = content.substring(0, MAX_FILE_CHARACTERS) + "\n... truncated by Eclipse ...";
			}
			JsonObject resource = new JsonObject();
			resource.addProperty("uri", file.getLocationURI().toString());
			resource.addProperty("text", content);
			resource.addProperty("mimeType", "text/plain");
			JsonObject block = new JsonObject();
			block.addProperty("type", "resource");
			block.add("resource", resource);
			blocks.add(block);
		} catch (Exception ignored) {
			// Attaching context must never block sending the prompt.
		}
	}

	private static JsonObject text(String value) {
		JsonObject block = new JsonObject();
		block.addProperty("type", "text");
		block.addProperty("text", value);
		return block;
	}
}
