package com.cursor.eclipse.prefs;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.jface.preference.BooleanFieldEditor;
import org.eclipse.jface.preference.DirectoryFieldEditor;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.cursor.eclipse.CursorPlugin;

public class CursorPreferencePage extends FieldEditorPreferencePage implements IWorkbenchPreferencePage {

	public CursorPreferencePage() {
		super(GRID);
		setPreferenceStore(CursorPlugin.getDefault().getPreferenceStore());
		setDescription("Leave the agent binary empty to search PATH and ~/.local/bin/agent.\n"
				+ "Sign in once by running 'agent login' in a terminal, or set an API key below.");
	}

	@Override
	public void init(IWorkbench workbench) {
		// The preference store is supplied by the constructor.
	}

	@Override
	protected void createFieldEditors() {
		addField(new StringFieldEditor(PreferenceConstants.AGENT_PATH, "Agent binary:", getFieldEditorParent()));
		addField(new StringFieldEditor(PreferenceConstants.AGENT_ARGS, "Arguments:", getFieldEditorParent()));
		addField(new MaskedStringFieldEditor(PreferenceConstants.API_KEY, "API key:", getFieldEditorParent()));
		addField(new DirectoryFieldEditor(PreferenceConstants.DEFAULT_CWD, "Default working directory:",
				getFieldEditorParent()));
		addField(new BooleanFieldEditor(PreferenceConstants.AUTO_START, "Connect when the Cursor view opens",
				getFieldEditorParent()));
	}

	/** Hides the stored key while it is displayed. */
	private static final class MaskedStringFieldEditor extends StringFieldEditor {

		private MaskedStringFieldEditor(String name, String labelText, Composite parent) {
			super(name, labelText, parent);
		}

		@Override
		protected void doFillIntoGrid(Composite parent, int numColumns) {
			super.doFillIntoGrid(parent, numColumns);
			getTextControl().setEchoChar('\u2022');
		}
	}
}
