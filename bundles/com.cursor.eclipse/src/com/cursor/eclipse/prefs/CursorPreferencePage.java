package com.cursor.eclipse.prefs;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.cursor.eclipse.CursorPlugin;

public class CursorPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public CursorPreferencePage() {
		super(GRID);
		setPreferenceStore(CursorPlugin.getDefault().getPreferenceStore());
		setDescription("Leave the agent path empty to search PATH and ~/.local/bin/agent. Run `agent login` once in a terminal.");
	}

	@Override
	public void init(IWorkbench workbench) {
		// nothing extra
	}

	@Override
	protected void createFieldEditors() {
		addField(new StringFieldEditor(PreferenceConstants.AGENT_PATH, "Agent binary (optional):",
				getFieldEditorParent()));
		addField(new StringFieldEditor(PreferenceConstants.AGENT_ARGS, "Arguments:", getFieldEditorParent()));
		addField(new StringFieldEditor(PreferenceConstants.API_KEY, "API key (optional):", getFieldEditorParent()) {
			@Override
			protected void doFillIntoGrid(Composite parent, int numColumns) {
				super.doFillIntoGrid(parent, numColumns);
				getTextControl().setEchoChar('*');
			}
		});
	}
}
