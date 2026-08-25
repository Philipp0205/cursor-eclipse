package com.cursor.eclipse.chat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
import org.eclipse.ui.part.ViewPart;

import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.agent.SessionMode;
import com.cursor.eclipse.workspace.PromptContext;
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

	private ConversationBrowser conversation;
	private Text input;
	private GridData inputLayout;
	private Combo modeCombo;
	private Label status;
	private Button send;
	private Composite statusRow;
	private ChatController controller;

	private List<SessionMode> modes = List.of();
	private final List<String> history = new ArrayList<>();
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
		createStatusRow(parent);

		controller = new ChatController(this);
		refreshState("Disconnected");
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
				sendPrompt();
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
		GridLayout layout = new GridLayout(3, false);
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

		status = new Label(statusRow, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		send = new Button(statusRow, SWT.PUSH);
		send.setText("Send");
		send.addListener(SWT.Selection, event -> {
			if (controller.isBusy()) {
				controller.cancel();
			} else {
				sendPrompt();
			}
		});
	}

	private void sendPrompt() {
		String text = input.getText().trim();
		if (text.isEmpty() || controller.isBusy()) {
			return;
		}
		// Editor, selection, and project state must be read on the SWT thread,
		// before any worker touches the agent.
		JsonArray prompt = PromptContext.collect(text, getSite().getWorkbenchWindow());
		File workingDirectory = LaunchFactory.workingDirectory();
		input.setText("");
		growInput();
		rememberHistory(text);
		controller.send(prompt, text, workingDirectory);
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

	void refreshState() {
		refreshState(null);
	}

	/** Updates the status text and the enablement of every stateful control. */
	void refreshState(String message) {
		ui(() -> {
			if (message != null) {
				status.setText(message);
				status.setToolTipText(message);
			}
			boolean busy = controller.isBusy();
			send.setText(busy ? "Stop" : "Send");
			send.setEnabled(busy || !input.getText().isBlank() || controller.isConnected());
			modeCombo.setEnabled(!busy && controller.isConnected() && !modes.isEmpty());
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
		controller.newSession(LaunchFactory.workingDirectory());
	}

	public void connect() {
		controller.connect(LaunchFactory.workingDirectory());
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
		super.dispose();
	}

	Control conversationControl() {
		return conversation;
	}
}
