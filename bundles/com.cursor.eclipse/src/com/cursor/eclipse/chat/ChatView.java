package com.cursor.eclipse.chat;

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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.agent.CursorSession;
import com.cursor.eclipse.agent.LaunchFactory;

public class ChatView extends ViewPart {

	public static final String ID = "com.cursor.eclipse.chat.ChatView";

	private StyledText transcript;
	private Text input;
	private Label status;
	private Button connect;
	private Button send;
	private Button cancel;
	private CursorSession session;

	@Override
	public void createPartControl(Composite parent) {
		parent.setLayout(new GridLayout(1, false));

		Composite toolbar = new Composite(parent, SWT.NONE);
		toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
		toolbar.setLayout(new GridLayout(4, false));

		connect = new Button(toolbar, SWT.PUSH);
		connect.setText("Connect");
		connect.addListener(SWT.Selection, event -> connectOrDisconnect());

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

		session = new CursorSession(this::appendTranscript, this::setStatus);
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
					session.prompt(text);
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
