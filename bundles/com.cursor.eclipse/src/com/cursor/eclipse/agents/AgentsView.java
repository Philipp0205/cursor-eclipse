package com.cursor.eclipse.agents;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.ViewPart;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.agent.SessionLaunchRegistry;
import com.cursor.eclipse.agent.SessionLaunchRegistry.OpenSession;
import com.cursor.eclipse.chat.ChatView;

/**
 * Folder-grouped navigator for the chat views currently open in this workbench.
 */
public final class AgentsView extends ViewPart {

	public static final String ID = "com.cursor.eclipse.agents.AgentsView";

	private TreeViewer viewer;
	private final Runnable registryListener = this::scheduleRefresh;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new FillLayout());
		viewer = new TreeViewer(parent, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
		viewer.setContentProvider(new SessionsContentProvider());
		viewer.setLabelProvider(new SessionsLabelProvider());
		viewer.setInput(SessionLaunchRegistry.sessions());
		viewer.setAutoExpandLevel(2);
		viewer.addDoubleClickListener(event -> {
			if (event.getSelection() instanceof StructuredSelection selection
					&& selection.getFirstElement() instanceof OpenSession session) {
				activate(session);
			}
		});
		SessionLaunchRegistry.addListener(registryListener);
	}

	private void activate(OpenSession session) {
		try {
			IWorkbenchPage page = getSite().getPage();
			if (session.secondaryId() == null) {
				page.showView(ChatView.ID);
			} else {
				page.showView(ChatView.ID, session.secondaryId(), IWorkbenchPage.VIEW_ACTIVATE);
			}
		} catch (PartInitException e) {
			CursorPlugin.log("Could not activate Cursor chat " + session.name(), e);
		}
	}

	private void scheduleRefresh() {
		if (viewer == null || viewer.getControl().isDisposed()) {
			return;
		}
		viewer.getControl().getDisplay().asyncExec(() -> {
			if (viewer != null && !viewer.getControl().isDisposed()) {
				viewer.setInput(SessionLaunchRegistry.sessions());
				viewer.expandToLevel(2);
			}
		});
	}

	@Override
	public void setFocus() {
		if (viewer != null && !viewer.getControl().isDisposed()) {
			viewer.getControl().setFocus();
		}
	}

	@Override
	public void dispose() {
		SessionLaunchRegistry.removeListener(registryListener);
		super.dispose();
	}

	private record FolderNode(File folder, List<OpenSession> sessions) {
	}

	private static final class SessionsContentProvider implements ITreeContentProvider {

		@Override
		public Object[] getElements(Object inputElement) {
			Map<String, List<OpenSession>> grouped = new LinkedHashMap<>();
			if (inputElement instanceof List<?> input) {
				input.stream().filter(OpenSession.class::isInstance).map(OpenSession.class::cast)
						.sorted(Comparator.comparing(session -> session.root().getAbsolutePath()))
						.forEach(session -> grouped.computeIfAbsent(session.root().getAbsolutePath(),
								ignored -> new ArrayList<>()).add(session));
			}
			return grouped.entrySet().stream()
					.map(entry -> new FolderNode(new File(entry.getKey()), entry.getValue().stream()
							.sorted(Comparator.comparing(OpenSession::name)).toList()))
					.toArray();
		}

		@Override
		public Object[] getChildren(Object parentElement) {
			return parentElement instanceof FolderNode folder ? folder.sessions().toArray() : new Object[0];
		}

		@Override
		public Object getParent(Object element) {
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			return element instanceof FolderNode folder && !folder.sessions().isEmpty();
		}
	}

	private static final class SessionsLabelProvider extends ColumnLabelProvider {

		@Override
		public String getText(Object element) {
			if (element instanceof FolderNode folder) {
				String name = folder.folder().getName();
				return name.isBlank() ? folder.folder().getAbsolutePath() : name;
			}
			if (element instanceof OpenSession session) {
				return session.name() + "  \u00b7  " + session.status();
			}
			return super.getText(element);
		}

		@Override
		public String getToolTipText(Object element) {
			if (element instanceof FolderNode folder) {
				return folder.folder().getAbsolutePath();
			}
			if (element instanceof OpenSession session) {
				String id = session.sessionId() == null ? "not connected" : session.sessionId();
				return session.root().getAbsolutePath() + "\nSession: " + id + "\nStatus: " + session.status();
			}
			return null;
		}
	}
}
