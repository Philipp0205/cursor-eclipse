package com.cursor.eclipse.chat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.dialogs.ResourceListSelectionDialog;
import org.eclipse.ui.IMemento;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.PartInitException;

import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.agent.SessionMode;
import com.cursor.eclipse.agent.SessionModel;
import com.cursor.eclipse.agent.SessionLaunchRegistry;
import com.cursor.eclipse.agent.SessionLaunchRegistry.PendingPrompt;
import com.cursor.eclipse.agents.CloudAgent;
import com.cursor.eclipse.agents.CloudAgents;
import com.cursor.eclipse.workspace.PromptContext;
import com.cursor.eclipse.prefs.PreferenceConstants;
import com.google.gson.JsonArray;

/**
 * The Cursor chat view.
 *
 * <p>This class owns widgets only; {@link ChatController} owns the agent and the
 * conversation lifecycle. Every method that a worker thread may call marshals to
 * the SWT thread itself, so callers never need to know which thread they are on.
 */
public class ChatView extends ViewPart {

	public static final String ID = "com.cursor.eclipse.chat.ChatView";

	private static final int INPUT_MIN_HEIGHT = 56;
	private static final int INPUT_MAX_HEIGHT = 180;
	private static final String MEMENTO_SESSION_ROOT = "cursorSessionRoot";
	private static final String MEMENTO_SESSION_ID = "cursorSessionId";
	private static final String MEMENTO_CLOUD_AGENT_ID = "cursorCloudAgentId";

	private ConversationBrowser conversation;
	private Text input;
	private GridData inputLayout;
	private Combo modeCombo;
	private Combo modelCombo;
	private Label status;
	private Button send;
	private Button cloud;
	private Button attach;
	private Composite statusRow;
	private ChatController controller;
	private File sessionRoot;
	private String sessionId;
	private String cloudAgentId;

	private List<SessionMode> modes = List.of();
	private List<SessionModel> models = List.of();
	private final List<String> history = new ArrayList<>();
	private final List<IFile> attachments = new ArrayList<>();
	private int historyIndex;

	@Override
	public void createPartControl(Composite parent) {
		GridLayout layout = new GridLayout(1, false);
		layout.marginHeight = 3;
		layout.marginWidth = 3;
		layout.verticalSpacing = 3;
		parent.setLayout(layout);

		conversation = new ConversationBrowser(parent);
		conversation.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

		createInput(parent);
		createContextRow(parent);
		createStatusRow(parent);

		controller = new ChatController(this);
		if (sessionRoot == null) {
			sessionRoot = SessionLaunchRegistry.get(getViewSite().getSecondaryId());
		}
		if (sessionRoot == null) {
			sessionRoot = LaunchFactory.workingDirectory();
		}
		String secondaryId = getViewSite().getSecondaryId();
		CloudAgent cloudSession = SessionLaunchRegistry.takeCloud(secondaryId);
		String viewName = secondaryId == null ? "Chat" : readableName(secondaryId);
		if (cloudSession != null) {
			viewName = cloudSession.name();
		}
		setPartName(secondaryId == null ? "Cursor Chat" : "Cursor: " + viewName);
		SessionLaunchRegistry.register(secondaryId, viewName, sessionRoot);
		refreshState("Disconnected");
		if (cloudSession != null) {
			cloudAgentId = cloudSession.id();
		}
		if (cloudAgentId != null) {
			controller.loadCloudSession(cloudAgentId, CloudAgents.apiKey());
			input.setMessage("Continue this Cursor Cloud conversation\u2026");
			return;
		}
		String resume = SessionLaunchRegistry.takeResume(secondaryId);
		if (resume == null) {
			resume = sessionId;
		}
		if (resume != null) {
			controller.loadSession(resume, workingDirectory());
		} else {
			PendingPrompt pending = SessionLaunchRegistry.takePrompt(secondaryId);
			if (pending != null) {
				input.setText(pending.text());
				input.getDisplay().asyncExec(() -> {
					if (!input.isDisposed()) {
						sendPrompt(pending.inCloud());
					}
				});
				return;
			}
			if (CursorPlugin.getDefault().getPreferenceStore().getBoolean(PreferenceConstants.AUTO_START)) {
				controller.connect(workingDirectory());
			}
		}
	}

	@Override
	public void init(IViewSite site, IMemento memento) throws PartInitException {
		super.init(site, memento);
		if (memento != null) {
			String root = memento.getString(MEMENTO_SESSION_ROOT);
			if (root != null && new File(root).isDirectory()) {
				sessionRoot = new File(root);
			}
			sessionId = memento.getString(MEMENTO_SESSION_ID);
			cloudAgentId = memento.getString(MEMENTO_CLOUD_AGENT_ID);
		}
	}

	@Override
	public void saveState(IMemento memento) {
		if (sessionRoot != null) {
			memento.putString(MEMENTO_SESSION_ROOT, sessionRoot.getAbsolutePath());
		}
		if (cloudAgentId != null && !cloudAgentId.isBlank()) {
			memento.putString(MEMENTO_CLOUD_AGENT_ID, cloudAgentId);
		} else if (sessionId != null && !sessionId.isBlank()) {
			memento.putString(MEMENTO_SESSION_ID, sessionId);
		}
		super.saveState(memento);
	}

	private void createContextRow(Composite parent) {
		Composite row = new Composite(parent, SWT.NONE);
		row.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		GridLayout rowLayout = new GridLayout(2, false);
		rowLayout.marginHeight = 0;
		rowLayout.marginWidth = 0;
		row.setLayout(rowLayout);
		attach = new Button(row, SWT.PUSH);
		attach.setText("Attach files\u2026");
		attach.setToolTipText("Choose Eclipse workspace files to add to the next prompt");
		attach.addListener(SWT.Selection, event -> {
			ResourceListSelectionDialog dialog = new ResourceListSelectionDialog(dialogShell(),
					ResourcesPlugin.getWorkspace().getRoot(), IResource.FILE);
			dialog.setTitle("Attach workspace files");
			dialog.setMessage("Choose files to include in the next Cursor prompt");
			if (dialog.open() == ResourceListSelectionDialog.OK) {
				attachments.clear();
				for (Object result : dialog.getResult()) {
					if (result instanceof IFile file) {
						attachments.add(file);
					}
				}
				updateAttachmentLabel();
			}
		});
		Combo commands = new Combo(row, SWT.DROP_DOWN | SWT.READ_ONLY);
		String[] commandItems = { "Commands\u2026", "/summarize", "/worktree", "/best-of-n", "/in-cloud" };
		commands.setItems(commandItems);
		commands.select(0);
		commands.setToolTipText("Insert a Cursor slash command");
		commands.addListener(SWT.Selection, event -> {
			int index = commands.getSelectionIndex();
			if (index > 0) {
				String command = commandItems[index];
				commands.getDisplay().asyncExec(() -> {
					if (!input.isDisposed()) {
						String prefix = input.getText().isBlank() ? "" : input.getText() + "\n";
						input.setText(prefix + command + " ");
						input.setSelection(input.getCharCount());
						input.setFocus();
					}
					if (!commands.isDisposed()) {
						commands.select(0);
					}
				});
			}
		});
	}

	private void updateAttachmentLabel() {
		if (attach != null && !attach.isDisposed()) {
			attach.setText(attachments.isEmpty() ? "Attach files\u2026"
					: "Attached: " + attachments.size() + " file" + (attachments.size() == 1 ? "" : "s"));
			attach.getParent().layout(true, true);
		}
	}

	private void createInput(Composite parent) {
		input = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		input.setMessage("Ask Cursor about this workspace\u2026    Enter to send, Shift+Enter for a new line");
		inputLayout = new GridData(SWT.FILL, SWT.CENTER, true, false);
		inputLayout.heightHint = INPUT_MIN_HEIGHT;
		input.setLayoutData(inputLayout);

		input.addListener(SWT.KeyDown, event -> {
			if ((event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) && (event.stateMask & SWT.SHIFT) == 0) {
				event.doit = false;
				sendPrompt(false);
				return;
			}
			if ((event.stateMask & SWT.ALT) != 0 && event.keyCode == SWT.ARROW_UP) {
				event.doit = false;
				navigateHistory(-1);
			} else if ((event.stateMask & SWT.ALT) != 0 && event.keyCode == SWT.ARROW_DOWN) {
				event.doit = false;
				navigateHistory(1);
			}
		});
		input.addModifyListener(event -> growInput());
	}

	private void createStatusRow(Composite parent) {
		statusRow = new Composite(parent, SWT.NONE);
		statusRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		GridLayout layout = new GridLayout(5, false);
		layout.marginHeight = 0;
		layout.marginWidth = 0;
		statusRow.setLayout(layout);

		modeCombo = new Combo(statusRow, SWT.DROP_DOWN | SWT.READ_ONLY);
		modeCombo.setToolTipText("Agent mode");
		GridData modeLayout = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		// Without a hint the drop-down collapses and clips its label in a narrow view.
		modeLayout.widthHint = 110;
		modeCombo.setLayoutData(modeLayout);
		modeCombo.addListener(SWT.Selection, event -> {
			int index = modeCombo.getSelectionIndex();
			if (index >= 0 && index < modes.size()) {
				controller.setMode(modes.get(index).id());
			}
		});

		modelCombo = new Combo(statusRow, SWT.DROP_DOWN | SWT.READ_ONLY);
		modelCombo.setToolTipText("Model");
		GridData modelLayout = new GridData(SWT.LEFT, SWT.CENTER, false, false);
		modelLayout.widthHint = 150;
		modelCombo.setLayoutData(modelLayout);
		modelCombo.addListener(SWT.Selection, event -> {
			int index = modelCombo.getSelectionIndex();
			if (index >= 0 && index < models.size()) {
				controller.setModel(models.get(index).id());
			}
		});

		status = new Label(statusRow, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		cloud = new Button(statusRow, SWT.PUSH);
		cloud.setText("Cloud");
		cloud.setToolTipText("Hand this prompt to a Cursor Cloud Agent");
		cloud.addListener(SWT.Selection, event -> sendPrompt(true));

		send = new Button(statusRow, SWT.PUSH);
		send.setText("Send");
		send.addListener(SWT.Selection, event -> {
			if (controller.isBusy()) {
				controller.cancel();
			} else {
				sendPrompt(false);
			}
		});
	}

	private void sendPrompt(boolean inCloud) {
		String text = input.getText().trim();
		if (text.isEmpty() || controller.isBusy()) {
			return;
		}
		// Editor, selection, and project state must be read on the SWT thread,
		// before any worker touches the agent.
		boolean existingCloud = controller.isCloud();
		String agentText = inCloud && !existingCloud ? "& " + text : text;
		JsonArray prompt = PromptContext.collect(agentText, getSite().getWorkbenchWindow(), List.copyOf(attachments));
		File workingDirectory = workingDirectory();
		input.setText("");
		attachments.clear();
		updateAttachmentLabel();
		growInput();
		rememberHistory(text);
		controller.send(prompt, inCloud && !existingCloud ? text + "\n\n_Sent to Cursor Cloud_" : text,
				workingDirectory);
	}

	private void rememberHistory(String text) {
		if (history.isEmpty() || !history.get(history.size() - 1).equals(text)) {
			history.add(text);
		}
		historyIndex = history.size();
	}

	private void navigateHistory(int direction) {
		if (history.isEmpty()) {
			return;
		}
		historyIndex = Math.max(0, Math.min(history.size(), historyIndex + direction));
		input.setText(historyIndex == history.size() ? "" : history.get(historyIndex));
		input.setSelection(input.getCharCount());
		growInput();
	}

	private void growInput() {
		if (input.isDisposed()) {
			return;
		}
		int preferred = input.computeSize(input.getSize().x, SWT.DEFAULT).y;
		int height = Math.max(INPUT_MIN_HEIGHT, Math.min(INPUT_MAX_HEIGHT, preferred));
		if (inputLayout.heightHint != height) {
			inputLayout.heightHint = height;
			input.getParent().layout(true, true);
		}
	}

	// --- API used by the controller (safe from any thread) -------------------

	/** Runs on the SWT thread if the view is still alive. */
	void ui(Runnable runnable) {
		Display display = Display.getDefault();
		if (display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (!display.isDisposed() && input != null && !input.isDisposed()) {
				runnable.run();
			}
		});
	}

	void putBlock(String id, String html) {
		ui(() -> {
			if (conversation != null && !conversation.isDisposed()) {
				conversation.put(id, html);
			}
		});
	}

	void removeBlock(String id) {
		ui(() -> {
			if (conversation != null && !conversation.isDisposed()) {
				conversation.remove(id);
			}
		});
	}

	void clearConversation() {
		if (conversation != null && !conversation.isDisposed()) {
			conversation.clear();
		}
	}

	void clearConversationBeforeReplay() {
		Display display = Display.getDefault();
		if (display.isDisposed()) {
			return;
		}
		Runnable clear = this::clearConversation;
		if (display.getThread() == Thread.currentThread()) {
			clear.run();
		} else {
			display.syncExec(clear);
		}
	}

	void refreshState() {
		refreshState(null);
	}

	/** Updates the status text and the enablement of every stateful control. */
	void refreshState(String message) {
		if (message != null && getViewSite() != null) {
			SessionLaunchRegistry.update(getViewSite().getSecondaryId(), null, message);
		}
		ui(() -> {
			if (message != null) {
				status.setText(message);
				status.setToolTipText(message);
			}
			boolean busy = controller.isBusy();
			send.setText(busy ? "Stop" : "Send");
			send.setEnabled(busy || !input.getText().isBlank() || controller.isConnected());
			cloud.setEnabled(!controller.isCloud() && !busy && !input.getText().isBlank());
			modeCombo.setEnabled(!busy && controller.isConnected() && !modes.isEmpty());
			modelCombo.setEnabled(!busy && controller.isConnected() && !models.isEmpty());
			statusRow.layout(true, true);
		});
	}

	void setModes(List<SessionMode> available, String currentModeId) {
		modes = List.copyOf(available);
		if (modeCombo.isDisposed()) {
			return;
		}
		if (modes.isEmpty()) {
			modeCombo.setItems(new String[0]);
			modeCombo.setEnabled(false);
			return;
		}
		modeCombo.setItems(modes.stream().map(SessionMode::name).toArray(String[]::new));
		selectMode(currentModeId);
	}

	void selectMode(String modeId) {
		if (modeCombo.isDisposed() || modes.isEmpty()) {
			return;
		}
		int index = 0;
		for (int i = 0; i < modes.size(); i++) {
			if (modes.get(i).id().equals(modeId)) {
				index = i;
				break;
			}
		}
		modeCombo.select(index);
	}

	void setModels(List<SessionModel> available, String currentModelId) {
		models = List.copyOf(available);
		if (modelCombo.isDisposed()) {
			return;
		}
		if (models.isEmpty()) {
			modelCombo.setItems(new String[0]);
			modelCombo.setEnabled(false);
			return;
		}
		modelCombo.setItems(models.stream().map(SessionModel::name).toArray(String[]::new));
		selectModel(currentModelId);
	}

	void setSessionId(String sessionId) {
		this.sessionId = sessionId;
		SessionLaunchRegistry.update(getViewSite().getSecondaryId(), sessionId, null);
	}

	void setCloudAgentId(String agentId) {
		cloudAgentId = agentId;
		sessionId = null;
		SessionLaunchRegistry.update(getViewSite().getSecondaryId(), agentId, null);
	}

	void selectModel(String modelId) {
		if (modelCombo.isDisposed() || models.isEmpty()) {
			return;
		}
		int index = 0;
		for (int i = 0; i < models.size(); i++) {
			if (models.get(i).id().equals(modelId)) {
				index = i;
				break;
			}
		}
		modelCombo.select(index);
	}

	Shell dialogShell() {
		Shell shell = getSite() == null ? null : getSite().getShell();
		if (shell != null && !shell.isDisposed()) {
			return shell;
		}
		Display display = Display.getDefault();
		return display.getShells().length > 0 ? display.getShells()[0] : null;
	}

	// --- commands ------------------------------------------------------------

	/** Starts a fresh conversation, keeping the agent process alive when possible. */
	public void newSession() {
		controller.newSession(workingDirectory());
	}

	public void connect() {
		controller.connect(workingDirectory());
	}

	public void resumeSession() {
		InputDialog dialog = new InputDialog(dialogShell(), "Resume Cursor session", "ACP session ID:", "", value ->
				value == null || value.isBlank() ? "Enter a session ID" : null);
		if (dialog.open() == Window.OK) {
			controller.loadSession(dialog.getValue().trim(), workingDirectory());
		}
	}

	private File workingDirectory() {
		return sessionRoot == null ? LaunchFactory.workingDirectory() : sessionRoot;
	}

	private static String readableName(String secondaryId) {
		String withoutTimestamp = secondaryId.replaceFirst("-\\d{10,}$", "");
		String readable = withoutTimestamp.replace('-', ' ').trim();
		return readable.isBlank() ? "Agent" : Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
	}

	public void disconnect() {
		controller.disconnect();
	}

	@Override
	public void setFocus() {
		if (input != null && !input.isDisposed()) {
			input.setFocus();
		}
	}

	@Override
	public void dispose() {
		if (controller != null) {
			controller.shutdown();
		}
		SessionLaunchRegistry.remove(getViewSite().getSecondaryId());
		super.dispose();
	}

	Control conversationControl() {
		return conversation;
	}
}
