package com.cursor.eclipse.chat.handlers;

import java.net.URI;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.browser.IWorkbenchBrowserSupport;
import org.eclipse.ui.handlers.HandlerUtil;

/** Opens a local app or other URL in Eclipse's embedded browser editor. */
public final class OpenPreviewHandler extends AbstractHandler {

	@Override
	public Object execute(ExecutionEvent event) throws org.eclipse.core.commands.ExecutionException {
		var window = HandlerUtil.getActiveWorkbenchWindow(event);
		if (window == null) {
			return null;
		}
		InputDialog dialog = new InputDialog(window.getShell(), "Open app preview", "URL:", "http://localhost:3000",
				value -> {
					try {
						URI uri = URI.create(value);
						return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
								? null : "Only http:// and https:// URLs are supported";
					} catch (Exception e) {
						return "Enter a valid URL";
					}
				});
		if (dialog.open() != Window.OK) {
			return null;
		}
		try {
			PlatformUI.getWorkbench().getBrowserSupport()
					.createBrowser(IWorkbenchBrowserSupport.AS_EDITOR, "cursor-preview", "Cursor Preview",
							"Preview the application changed by Cursor")
					.openURL(URI.create(dialog.getValue()).toURL());
		} catch (Exception e) {
			throw new org.eclipse.core.commands.ExecutionException("Could not open the app preview", e);
		}
		return null;
	}
}
