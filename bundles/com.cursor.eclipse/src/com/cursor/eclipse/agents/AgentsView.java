package com.cursor.eclipse.agents;

import java.io.File;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ColumnViewerToolTipSupport;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.dialogs.PreferencesUtil;
import org.eclipse.ui.part.ViewPart;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.agent.SessionLaunchRegistry;
import com.cursor.eclipse.agent.SessionLaunchRegistry.OpenSession;
import com.cursor.eclipse.chat.ChatView;

/**
 * Navigator for every Cursor agent this account can reach: the chat views open
 * in this workbench, the chats the Cursor CLI already stored on this machine,
 * and the Cloud Agents of the signed-in account.
 *
 * <p>Reading chats and calling the cloud API both block, so each refresh runs on
 * a worker thread and posts its result back to the SWT thread.
 */
public final class AgentsView extends ViewPart {

	public static final String ID = "com.cursor.eclipse.agents.AgentsView";

	private static final String PREFERENCE_PAGE_ID = "com.cursor.eclipse.prefs";
	private static final int CLOUD_AGENT_LIMIT = 50;
	private static final int AUTO_REFRESH_MS = 60_000;
	/** Category, folder, and agent: the whole tree without a manual expand. */
	private static final int EXPAND_LEVELS = 3;

	private TreeViewer viewer;
	private Text search;
	private final Runnable registryListener = this::scheduleRefresh;
	private final AtomicInteger refreshes = new AtomicInteger();

	private volatile List<LocalChat> localChats = List.of();
	private volatile List<CloudAgent> cloudAgents = List.of();
	private volatile Note localNote = new Note("Reading local chats\u2026", NoteAction.NONE);
	private volatile Note cloudNote = new Note("Loading cloud agents\u2026", NoteAction.NONE);

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 6;
		layout.marginWidth = 6;
		layout.verticalSpacing = 5;
		parent.setLayout(layout);
		search = new Text(parent, SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
		search.setMessage("Search agents");
		search.setToolTipText("Filter by agent name, folder, status, branch, repository, or ID");
		search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		viewer = new TreeViewer(parent, SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
		viewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		viewer.setContentProvider(new AgentsContentProvider());
		viewer.setLabelProvider(new AgentsLabelProvider());
		// Without this the label provider's tooltips are never shown.
		ColumnViewerToolTipSupport.enableFor(viewer);
		viewer.setAutoExpandLevel(EXPAND_LEVELS);
		viewer.setInput(this);
		viewer.addDoubleClickListener(event -> {
			if (event.getSelection() instanceof StructuredSelection selection) {
				open(selection.getFirstElement());
			}
		});
		search.addModifyListener(event -> {
			viewer.refresh();
			viewer.expandToLevel(EXPAND_LEVELS);
		});
		createContextMenu();
		SessionLaunchRegistry.addListener(registryListener);
		restoreChatViews();
		refresh();
		scheduleAutoRefresh();
	}

	/**
	 * Eclipse restores inactive tabs lazily. Materialize saved chat views in the
	 * background so the navigator and automatic session resume are complete
	 * without requiring the user to visit every tab first.
	 */
	private void restoreChatViews() {
		viewer.getControl().getDisplay().asyncExec(() -> {
			if (viewer == null || viewer.getControl().isDisposed()) {
				return;
			}
			for (IViewReference reference : getSite().getPage().getViewReferences()) {
				if (ChatView.ID.equals(reference.getId())) {
					reference.getView(true);
				}
			}
			scheduleRefresh();
		});
	}

	private void createContextMenu() {
		MenuManager menu = new MenuManager();
		menu.setRemoveAllWhenShown(true);
		menu.addMenuListener(manager -> {
			Object selected = selection();
			if (selected instanceof OpenSession || selected instanceof LocalChat) {
				manager.add(action("Open in Eclipse", () -> open(selected)));
			}
			if (selected instanceof CloudAgent agent) {
				manager.add(action("Open on cursor.com", () -> browse(agent.url())));
			}
			String id = identifier(selected);
			if (id != null) {
				manager.add(action("Copy ID", () -> copy(id)));
			}
			manager.add(new Separator());
			manager.add(action("Refresh", this::refresh));
		});
		viewer.getControl().setMenu(menu.createContextMenu(viewer.getControl()));
	}

	private static IAction action(String label, Runnable body) {
		return new Action(label) {

			@Override
			public void run() {
				body.run();
			}
		};
	}

	private Object selection() {
		return viewer.getStructuredSelection() == null ? null : viewer.getStructuredSelection().getFirstElement();
	}

	private static String identifier(Object element) {
		if (element instanceof OpenSession session) {
			return session.sessionId();
		}
		if (element instanceof LocalChat chat) {
			return chat.id();
		}
		if (element instanceof CloudAgent agent) {
			return agent.id();
		}
		return null;
	}

	private void copy(String text) {
		Clipboard clipboard = new Clipboard(viewer.getControl().getDisplay());
		try {
			clipboard.setContents(new Object[] { text }, new Transfer[] { TextTransfer.getInstance() });
		} finally {
			clipboard.dispose();
		}
	}

	/** Re-reads open views, local chats, and cloud agents. */
	public void refresh() {
		int token = refreshes.incrementAndGet();
		scheduleRefresh();
		worker("cursor-local-chats", () -> {
			List<LocalChat> chats = LocalChatHistory.read();
			Note note = chats.isEmpty()
					? new Note("No Cursor CLI chats in " + LocalChatHistory.defaultRoot(), NoteAction.NONE)
					: null;
			publish(token, () -> {
				localChats = chats;
				localNote = note;
			});
		});
		worker("cursor-cloud-agents", () -> {
			List<CloudAgent> agents = List.of();
			Note note;
			try {
				agents = CloudAgents.list(CloudAgents.apiKey(), CLOUD_AGENT_LIMIT);
				note = agents.isEmpty() ? new Note("No cloud agents", NoteAction.NONE) : null;
			} catch (CloudAgents.NotAuthorizedException e) {
				note = new Note(e.getMessage(), NoteAction.PREFERENCES);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				CursorPlugin.log("Could not list Cursor cloud agents", e);
				note = new Note("Cloud agents unavailable: " + describe(e), NoteAction.NONE);
			}
			List<CloudAgent> loaded = agents;
			Note loadedNote = note;
			publish(token, () -> {
				cloudAgents = loaded;
				cloudNote = loadedNote;
			});
		});
	}

	private void publish(int token, Runnable update) {
		if (token != refreshes.get()) {
			return;
		}
		update.run();
		scheduleRefresh();
	}

	private static void worker(String name, Runnable body) {
		Thread thread = new Thread(body, name);
		thread.setDaemon(true);
		thread.start();
	}

	private static String describe(Exception e) {
		String message = e.getMessage();
		return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
	}

	private void open(Object element) {
		if (element instanceof OpenSession session) {
			activate(session);
		} else if (element instanceof LocalChat chat) {
			resume(chat);
		} else if (element instanceof CloudAgent agent) {
			browse(agent.url());
		} else if (element instanceof Note note && note.action() == NoteAction.PREFERENCES) {
			PreferencesUtil.createPreferenceDialogOn(getSite().getShell(), PREFERENCE_PAGE_ID,
					new String[] { PREFERENCE_PAGE_ID }, null).open();
		} else if (element != null && viewer != null) {
			viewer.setExpandedState(element, !viewer.getExpandedState(element));
		}
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

	/** Opens a chat view bound to the chat's folder and replays the conversation. */
	private void resume(LocalChat chat) {
		for (OpenSession session : SessionLaunchRegistry.sessions()) {
			if (chat.id().equals(session.sessionId())) {
				activate(session);
				return;
			}
		}
		File root = chat.workspace() != null && chat.workspace().isDirectory() ? chat.workspace()
				: LaunchFactory.workingDirectory();
		String secondaryId = "chat-" + safeId(chat.id()) + "-" + Instant.now().toEpochMilli();
		SessionLaunchRegistry.put(secondaryId, root);
		SessionLaunchRegistry.putResume(secondaryId, chat.id());
		try {
			getSite().getPage().showView(ChatView.ID, secondaryId, IWorkbenchPage.VIEW_ACTIVATE);
		} catch (PartInitException e) {
			SessionLaunchRegistry.remove(secondaryId);
			CursorPlugin.log("Could not resume Cursor chat " + chat.id(), e);
		}
	}

	private void browse(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		try {
			PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(url).toURL());
		} catch (Exception e) {
			CursorPlugin.log("Could not open " + url, e);
		}
	}

	/** Secondary view ids are colon-separated, so an id may not contain one. */
	private static String safeId(String id) {
		String trimmed = id.length() <= 8 ? id : id.substring(0, 8);
		return trimmed.replaceAll("[^A-Za-z0-9-]", "-");
	}

	private void scheduleAutoRefresh() {
		Display display = viewer.getControl().getDisplay();
		display.timerExec(AUTO_REFRESH_MS, () -> {
			if (viewer == null || viewer.getControl().isDisposed()) {
				return;
			}
			if (getSite().getPage().isPartVisible(this)) {
				refresh();
			}
			scheduleAutoRefresh();
		});
	}

	private void scheduleRefresh() {
		if (viewer == null || viewer.getControl().isDisposed()) {
			return;
		}
		viewer.getControl().getDisplay().asyncExec(() -> {
			if (viewer != null && !viewer.getControl().isDisposed()) {
				viewer.refresh();
				viewer.expandToLevel(EXPAND_LEVELS);
			}
		});
	}

	@Override
	public void setFocus() {
		if (search != null && !search.isDisposed()) {
			search.setFocus();
		}
	}

	@Override
	public void dispose() {
		SessionLaunchRegistry.removeListener(registryListener);
		super.dispose();
	}

	// --- tree model ----------------------------------------------------------

	private enum NoteAction {
		NONE, PREFERENCES
	}

	private record Note(String text, NoteAction action) {
	}

	private record Category(String label, List<Object> children) {
	}

	private record FolderNode(File folder, List<Object> children) {
	}

	private record GroupNode(String label, List<Object> children) {
	}

	private List<Object> categories() {
		List<OpenSession> open = SessionLaunchRegistry.sessions().stream().filter(this::matches).toList();
		List<LocalChat> local = localChats.stream().filter(this::matches).toList();
		List<CloudAgent> cloud = cloudAgents.stream().filter(this::matches).toList();
		boolean filtering = !query().isEmpty();
		List<Object> categories = new ArrayList<>();
		categories.add(new Category("Open in Eclipse",
				byFolder(open, OpenSession::root, Comparator.comparing(OpenSession::name))));
		categories.add(new Category("Local chats",
				withNote(byFolder(local, chat -> chat.workspace() == null ? unknownFolder() : chat.workspace(),
						Comparator.comparing(LocalChat::modified).reversed()), filtering ? null : localNote)));
		categories.add(new Category("Cloud agents",
				withNote(byCloudStatus(cloud), filtering ? null : cloudNote)));
		return categories;
	}

	private String query() {
		return search == null || search.isDisposed() ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
	}

	private boolean matches(OpenSession session) {
		return matches(session.name(), session.status(), session.sessionId(), session.root().getAbsolutePath());
	}

	private boolean matches(LocalChat chat) {
		return matches(chat.title(), chat.id(), chat.workspace() == null ? null : chat.workspace().getAbsolutePath());
	}

	private boolean matches(CloudAgent agent) {
		return matches(agent.name(), agent.id(), agent.status(), agent.repository());
	}

	private boolean matches(String... values) {
		String query = query();
		if (query.isEmpty()) {
			return true;
		}
		for (String value : values) {
			if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) {
				return true;
			}
		}
		return false;
	}

	private static List<Object> byCloudStatus(List<CloudAgent> agents) {
		Map<String, List<Object>> groups = new LinkedHashMap<>();
		groups.put("In progress", new ArrayList<>());
		groups.put("Needs attention", new ArrayList<>());
		groups.put("Done", new ArrayList<>());
		for (CloudAgent agent : agents) {
			groups.get(cloudGroup(agent.status())).add(agent);
		}
		List<Object> result = new ArrayList<>();
		groups.forEach((label, children) -> {
			if (!children.isEmpty()) {
				result.add(new GroupNode(label, children));
			}
		});
		return result;
	}

	private static String cloudGroup(String status) {
		String value = status == null ? "" : status.toUpperCase(Locale.ROOT);
		if (value.contains("ACTIVE") || value.contains("RUNNING") || value.contains("CREATING")) {
			return "In progress";
		}
		if (value.contains("ERROR") || value.contains("FAIL") || value.contains("ATTENTION")) {
			return "Needs attention";
		}
		return "Done";
	}

	private static List<Object> withNote(List<Object> children, Note note) {
		if (note != null) {
			children.add(note);
		}
		return children;
	}

	private static File unknownFolder() {
		return new File("Unknown folder");
	}

	private static <T> List<Object> byFolder(List<T> items, Function<T, File> folderOf, Comparator<T> order) {
		Map<String, List<T>> grouped = new LinkedHashMap<>();
		items.stream().sorted(Comparator.comparing(item -> folderOf.apply(item).getAbsolutePath()))
				.forEach(item -> grouped.computeIfAbsent(folderOf.apply(item).getAbsolutePath(),
						ignored -> new ArrayList<>()).add(item));
		List<Object> folders = new ArrayList<>();
		grouped.forEach((path, members) -> {
			List<Object> children = new ArrayList<>(members.stream().sorted(order).toList());
			folders.add(new FolderNode(new File(path), children));
		});
		return folders;
	}

	private final class AgentsContentProvider implements ITreeContentProvider {

		@Override
		public Object[] getElements(Object inputElement) {
			return categories().toArray();
		}

		@Override
		public Object[] getChildren(Object parentElement) {
			if (parentElement instanceof Category category) {
				return category.children().toArray();
			}
			if (parentElement instanceof FolderNode folder) {
				return folder.children().toArray();
			}
			if (parentElement instanceof GroupNode group) {
				return group.children().toArray();
			}
			return new Object[0];
		}

		@Override
		public Object getParent(Object element) {
			return null;
		}

		@Override
		public boolean hasChildren(Object element) {
			return getChildren(element).length > 0;
		}
	}

	private static final class AgentsLabelProvider extends ColumnLabelProvider {

		@Override
		public String getText(Object element) {
			if (element instanceof Category category) {
				return category.label() + " (" + count(category) + ")";
			}
			if (element instanceof FolderNode folder) {
				String name = folder.folder().getName();
				return name.isBlank() ? folder.folder().getAbsolutePath() : name;
			}
			if (element instanceof GroupNode group) {
				return group.label() + " (" + group.children().size() + ")";
			}
			if (element instanceof OpenSession session) {
				return session.name() + "  \u00b7  " + session.status();
			}
			if (element instanceof LocalChat chat) {
				return chat.title() + "  \u00b7  " + age(chat.modified());
			}
			if (element instanceof CloudAgent agent) {
				String status = agent.status() == null ? "" : "  \u00b7  " + readable(agent.status());
				return agent.name() + status + "  \u00b7  " + age(agent.created());
			}
			if (element instanceof Note note) {
				return note.text();
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
			if (element instanceof LocalChat chat) {
				String folder = chat.workspace() == null ? "unknown folder" : chat.workspace().getAbsolutePath();
				return folder + "\nChat: " + chat.id() + "\nDouble-click to resume it in Eclipse";
			}
			if (element instanceof CloudAgent agent) {
				String repository = agent.repository() == null ? "" : "\nRepository: " + agent.repository();
				return agent.name() + "\nAgent: " + agent.id() + repository
						+ "\nDouble-click to open it on cursor.com";
			}
			if (element instanceof Note note) {
				return note.action() == NoteAction.PREFERENCES
						? note.text() + "\nDouble-click to open Cursor preferences"
						: note.text();
			}
			return null;
		}

		private static int count(Category category) {
			int total = 0;
			for (Object child : category.children()) {
				if (child instanceof FolderNode folder) {
					total += folder.children().size();
				} else if (child instanceof GroupNode group) {
					total += group.children().size();
				} else if (!(child instanceof Note)) {
					total++;
				}
			}
			return total;
		}

		private static String readable(String status) {
			String lower = status.toLowerCase(Locale.ROOT).replace('_', ' ');
			return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
		}

		private static String age(Instant instant) {
			if (instant == null || instant.equals(Instant.EPOCH)) {
				return "unknown";
			}
			Duration elapsed = Duration.between(instant, Instant.now());
			long minutes = elapsed.toMinutes();
			if (minutes < 1) {
				return "just now";
			}
			if (minutes < 60) {
				return minutes + "m ago";
			}
			long hours = elapsed.toHours();
			if (hours < 24) {
				return hours + "h ago";
			}
			return elapsed.toDays() + "d ago";
		}
	}
}
