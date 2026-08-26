package com.cursor.eclipse.perspective;

import org.eclipse.ui.IPageLayout;
import org.eclipse.ui.IPerspectiveFactory;

import com.cursor.eclipse.agents.AgentsView;
import com.cursor.eclipse.chat.ChatView;

/** Default layout for working with local Cursor agents in Eclipse. */
public final class CursorPerspective implements IPerspectiveFactory {

	@Override
	public void createInitialLayout(IPageLayout layout) {
		String editorArea = layout.getEditorArea();
		layout.setEditorAreaVisible(true);

		layout.addView(AgentsView.ID, IPageLayout.LEFT, 0.22f, editorArea);
		layout.addView(ChatView.ID, IPageLayout.RIGHT, 0.65f, editorArea);

		layout.addShowViewShortcut(AgentsView.ID);
		layout.addShowViewShortcut(ChatView.ID);
		layout.addPerspectiveShortcut("org.eclipse.jdt.ui.JavaPerspective");
		layout.addPerspectiveShortcut("org.eclipse.ui.resourcePerspective");
	}
}
