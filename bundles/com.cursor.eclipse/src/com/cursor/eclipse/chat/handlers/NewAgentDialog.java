package com.cursor.eclipse.chat.handlers;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;

/**
 * Composer-first new-agent dialog. It keeps the choices needed for a parallel
 * run together instead of asking a sequence of unrelated modal questions.
 */
final class NewAgentDialog extends TitleAreaDialog {

	enum Target {
		LOCAL("Local workspace"), CLOUD("Cursor Cloud (uses account quota)");

		private final String label;

		Target(String label) {
			this.label = label;
		}
	}

	record Result(String name, String prompt, Target target, int copies, boolean isolated) {
	}

	private Text name;
	private Text prompt;
	private Combo target;
	private Spinner copies;
	private Button isolated;
	private Result result;

	NewAgentDialog(Shell parentShell) {
		super(parentShell);
	}

	@Override
	public void create() {
		super.create();
		setTitle("New Agent");
		setMessage("Start one agent, or fan the same task out to several agents in parallel.");
		validate();
	}

	@Override
	protected Control createDialogArea(Composite parent) {
		Composite area = (Composite) super.createDialogArea(parent);
		Composite form = new Composite(area, SWT.NONE);
		form.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
		form.setLayout(new GridLayout(2, false));

		label(form, "Name");
		name = new Text(form, SWT.BORDER);
		name.setMessage("agent");
		name.setText("agent");
		name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		label(form, "Run in");
		target = new Combo(form, SWT.DROP_DOWN | SWT.READ_ONLY);
		target.setItems(java.util.Arrays.stream(Target.values()).map(value -> value.label).toArray(String[]::new));
		target.select(0);
		target.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

		label(form, "Parallel agents");
		copies = new Spinner(form, SWT.BORDER);
		copies.setMinimum(1);
		copies.setMaximum(8);
		copies.setSelection(1);
		copies.setToolTipText("Run the same task in up to eight independent agent views");

		new Label(form, SWT.NONE);
		isolated = new Button(form, SWT.CHECK);
		isolated.setText("Use a separate Git worktree for each local agent");
		isolated.setSelection(true);

		Label promptLabel = label(form, "Task");
		promptLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
		prompt = new Text(form, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
		prompt.setMessage("Ask Cursor to build, plan, fix…");
		GridData promptData = new GridData(SWT.FILL, SWT.FILL, true, true);
		promptData.heightHint = 110;
		prompt.setLayoutData(promptData);

		name.addModifyListener(event -> validate());
		prompt.addModifyListener(event -> validate());
		target.addListener(SWT.Selection, event -> {
			boolean local = selectedTarget() == Target.LOCAL;
			isolated.setEnabled(local);
			copies.setToolTipText(local ? "Run the same task in up to eight independent agent views"
					: "Launch independent Cloud Agent requests; each request uses account quota");
			validate();
		});
		copies.addModifyListener(event -> validate());
		return area;
	}

	private static Label label(Composite parent, String text) {
		Label label = new Label(parent, SWT.NONE);
		label.setText(text + ":");
		return label;
	}

	private Target selectedTarget() {
		int index = target == null ? 0 : target.getSelectionIndex();
		return index < 0 ? Target.LOCAL : Target.values()[index];
	}

	private void validate() {
		Button ok = getButton(IDialogConstants.OK_ID);
		if (ok == null || name == null || prompt == null) {
			return;
		}
		String error = null;
		if (name.getText().isBlank()) {
			error = "Enter a name.";
		} else if (prompt.getText().isBlank()) {
			error = "Enter a task for the agent.";
		}
		setErrorMessage(error);
		ok.setEnabled(error == null);
		ok.setText(copies.getSelection() == 1 ? "Start Agent" : "Start " + copies.getSelection() + " Agents");
	}

	@Override
	protected void okPressed() {
		Target selected = selectedTarget();
		result = new Result(name.getText().trim(), prompt.getText().trim(), selected, copies.getSelection(),
				selected == Target.LOCAL && isolated.getSelection());
		super.okPressed();
	}

	Result result() {
		return result;
	}
}
