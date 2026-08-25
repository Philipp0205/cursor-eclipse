package com.cursor.eclipse;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class CursorPlugin extends AbstractUIPlugin {

	public static final String PLUGIN_ID = "com.cursor.eclipse";

	private static CursorPlugin plugin;

	@Override
	public void start(BundleContext context) throws Exception {
		super.start(context);
		plugin = this;
	}

	@Override
	public void stop(BundleContext context) throws Exception {
		plugin = null;
		super.stop(context);
	}

	public static CursorPlugin getDefault() {
		return plugin;
	}

	public static ImageDescriptor getImageDescriptor(String path) {
		return imageDescriptorFromPlugin(PLUGIN_ID, path);
	}

	/** Records a problem in the Eclipse error log. */
	public static void log(String message, Throwable error) {
		CursorPlugin instance = plugin;
		IStatus status = new Status(IStatus.ERROR, PLUGIN_ID, message == null ? "Cursor error" : message, error);
		if (instance != null) {
			instance.getLog().log(status);
		}
	}
}
