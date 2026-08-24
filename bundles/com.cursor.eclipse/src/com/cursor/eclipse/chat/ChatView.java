package com.cursor.eclipse.chat;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.agent.CursorSession;
import com.cursor.eclipse.agent.CursorSession.SessionMode;
import com.cursor.eclipse.agent.LaunchFactory;
import com.cursor.eclipse.workspace.PromptContext;

public class ChatView extends ViewPart {

	public static final String ID = "com.cursor.eclipse.chat.ChatView";

	private StyledText transcript;
	private Text input;
	private Label status;
	private StyledText tasks;
	private Combo mode;
	private Button connect;
	private Button send;
	private Button cancel;
	private CursorSession session;
	private List<SessionMode> availableModes = new ArrayList<>();

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(1, false));

		Composite toolbar = new Composite(parent, SWT.NONE);
		toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		toolbar.setLayout(new GridLayout(7, false));

		connect = new Button(toolbar, SWT.PUSH);
		connect.setText("Connect");
		connect.addListener(SWT.Selection, event -> connectOrDisconnect());

		Button newSession = new Button(toolbar, SWT.PUSH);
		newSession.setText("New session");
		newSession.addListener(SWT.Selection, event -> newSession());

		mode = new Combo(toolbar, SWT.DROP_DOWN | SWT.READ_ONLY);
		mode.setToolTipText("Agent mode");
		mode.setItems("Agent", "Plan", "Ask");
		mode.select(0);
		mode.addListener(SWT.Selection, event -> {
			int selected = mode.getSelectionIndex();
			if (session != null && session.isConnected() && selected >= 0 && selected < availableModes.size()) {
				runJob("Change Cursor mode", () -> session.setMode(availableModes.get(selected).id()));
			}
		});

		send = new Button(toolbar, SWT.PUSH);
		send.setText("Send");
		send.addListener(SWT.Selection, event -> sendPrompt());

		cancel = new Button(toolbar, SWT.PUSH);
		cancel.setText("Cancel");
		cancel.addListener(SWT.Selection, event -> {
			if (session != null) {
				session.cancel();
			}
		});

		status = new Label(toolbar, SWT.NONE);
		status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		status.setText("Disconnected");

		transcript = new StyledText(parent, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY | SWT.WRAP);
		transcript.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		transcript.setWordWrap(true);

		Label tasksLabel = new Label(parent, SWT.NONE);
		tasksLabel.setText("Plan / Todos");
		tasks = new StyledText(parent, SWT.BORDER | SWT.V_SCROLL | SWT.READ_ONLY | SWT.WRAP);
		GridData tasksData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		tasksData.heightHint = 96;
		tasks.setLayoutData(tasksData);
		tasks.setText("No active plan.");

		input = new Text(parent, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		GridData inputData = new GridData(SWT.FILL, SWT.CENTER, true, false);
		inputData.heightHint = 64;
		input.setLayoutData(inputData);
		input.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
					if ((e.stateMask & SWT.SHIFT) == 0) {
						e.doit = false;
						sendPrompt();
					}
				}
			}
		});

		session = new CursorSession(this::appendTranscript, this::setStatus, this::setModes, this::setTasks);
	}

	private void connectOrDisconnect() {
		if (session.isConnected()) {
			session.stop();
			connect.setText("Connect");
			return;
		}
		Job job = new Job("Connect Cursor Agent") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					session.start(LaunchFactory.fromPreferences());
					asyncUi(() -> connect.setText("Disconnect"));
					return Status.OK_STATUS;
				} catch (Exception e) {
					return new Status(IStatus.ERROR, CursorPlugin.PLUGIN_ID, e.getMessage(), e);
				}
			}
		};
		job.setUser(true);
		job.schedule();
	}

	private void sendPrompt() {
		String text = input.getText().trim();
		if (text.isEmpty()) {
			return;
		}
		input.setText("");
		Job job = new Job("Cursor prompt") {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					if (!session.isConnected()) {
						session.start(LaunchFactory.fromPreferences());
						asyncUi(() -> connect.setText("Disconnect"));
					}
					session.prompt(PromptContext.collect(text, getSite().getWorkbenchWindow()), text);
					return Status.OK_STATUS;
				} catch (Exception e) {
					asyncUi(() -> MessageDialog.openError(getSite().getShell(), "Cursor",
							e.getMessage() == null ? e.toString() : e.getMessage()));
					return new Status(IStatus.ERROR, CursorPlugin.PLUGIN_ID, e.getMessage(), e);
				}
			}
		};
		job.setUser(true);
		job.schedule();
	}

	private void newSession() {
		if (!session.isConnected()) {
			connectOrDisconnect();
			return;
		}
		tasks.setText("No active plan.");
		runJob("New Cursor session", () -> session.newSession(LaunchFactory.workspaceDirectory().getAbsolutePath()));
	}

	private void runJob(String name, Runnable action) {
		Job job = new Job(name) {
			@Override
			protected IStatus run(IProgressMonitor monitor) {
				try {
					action.run();
					return Status.OK_STATUS;
				} catch (Exception e) {
					asyncUi(() -> MessageDialog.openError(getSite().getShell(), "Cursor",
							e.getMessage() == null ? e.toString() : e.getMessage()));
					return new Status(IStatus.ERROR, CursorPlugin.PLUGIN_ID, e.getMessage(), e);
				}
			}
		};
		job.setUser(true);
		job.schedule();
	}

	private void setModes(List<SessionMode> modes) {
		asyncUi(() -> {
			availableModes = new ArrayList<>(modes);
			if (availableModes.isEmpty()) {
				mode.setItems("Agent");
				mode.select(0);
				mode.setEnabled(false);
				return;
			}
			mode.setItems(availableModes.stream().map(SessionMode::name).toArray(String[]::new));
			mode.select(0);
			mode.setEnabled(true);
		});
	}

	private void setTasks(String text) {
		asyncUi(() -> {
			if (!tasks.isDisposed()) {
				tasks.setText(text == null || text.isBlank() ? "No active plan." : text);
			}
		});
	}

	private void appendTranscript(String text) {
		asyncUi(() -> {
			if (transcript.isDisposed()) {
				return;
			}
			transcript.append(text);
			transcript.setTopIndex(transcript.getLineCount() - 1);
		});
	}

	private void setStatus(String text) {
		asyncUi(() -> {
			if (!status.isDisposed()) {
				status.setText(text);
			}
		});
	}

	private void asyncUi(Runnable runnable) {
		Display display = Display.getDefault();
		if (display.isDisposed()) {
			return;
		}
		display.asyncExec(() -> {
			if (!display.isDisposed()) {
				runnable.run();
			}
		});
	}

	@Override
	public void setFocus() {
		input.setFocus();
	}

	@Override
	public void dispose() {
		if (session != null) {
			session.close();
		}
		super.dispose();
	}
}
