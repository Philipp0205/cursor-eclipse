package com.cursor.eclipse.chat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationAdapter;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.ProgressAdapter;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

import com.google.gson.Gson;

/**
 * One {@link Browser} rendering the whole conversation, updated by replacing
 * individual blocks by id.
 *
 * <p>Using a single native control keeps long transcripts cheap on GTK, where a
 * widget per message quickly exhausts native handles.
 */
final class ConversationBrowser extends Composite {

	private static final Gson JSON = new Gson();

	private final Browser browser;
	private final List<String> pending = new ArrayList<>();
	private boolean loaded;

	ConversationBrowser(Composite parent) {
		super(parent, SWT.NONE);
		setLayout(new FillLayout());
		Browser created = null;
		try {
			created = new Browser(this, SWT.NONE);
		} catch (Error | RuntimeException unavailable) {
			created = null;
		}
		browser = created;
		if (browser == null) {
			Text fallback = new Text(this, SWT.MULTI | SWT.WRAP | SWT.READ_ONLY | SWT.V_SCROLL);
			fallback.setText("The Cursor chat needs the SWT Browser, which could not be created.\n\n"
					+ "On Linux install WebKitGTK (for example libwebkit2gtk-4.1-0) and restart Eclipse.");
			return;
		}
		browser.setJavascriptEnabled(true);
		browser.addProgressListener(new ProgressAdapter() {
			@Override
			public void completed(ProgressEvent event) {
				loaded = true;
				for (String script : List.copyOf(pending)) {
					browser.execute(script);
				}
				pending.clear();
			}
		});
		browser.addLocationListener(new LocationAdapter() {
			@Override
			public void changing(LocationEvent event) {
				if (event.location == null) {
					return;
				}
				if (event.location.matches("https?://.*")) {
					event.doit = false;
					openExternally(event.location);
				} else if (loaded && !event.location.startsWith("about:blank")) {
					event.doit = false;
				}
			}
		});
		browser.setText(page());
	}

	/** Adds or replaces the block with this id. */
	void put(String id, String html) {
		execute("put(" + JSON.toJson(ConversationHtml.id(id)) + "," + JSON.toJson(html) + ")");
	}

	void remove(String id) {
		execute("removeBlock(" + JSON.toJson(ConversationHtml.id(id)) + ")");
	}

	void clear() {
		execute("reset()");
	}

	boolean isAvailable() {
		return browser != null;
	}

	private void execute(String script) {
		if (isDisposed() || browser == null || browser.isDisposed()) {
			return;
		}
		if (loaded) {
			browser.execute(script);
		} else {
			pending.add(script);
		}
	}

	private static void openExternally(String location) {
		try {
			PlatformUI.getWorkbench().getBrowserSupport().getExternalBrowser().openURL(URI.create(location).toURL());
		} catch (Exception ignored) {
			// A failed external browser must never break the chat.
		}
	}

	private static String page() {
		try {
			return resource("resources/chat.html").replace("__CSS__", resource("resources/chat.css"));
		} catch (IOException e) {
			throw new IllegalStateException("Missing packaged chat resources", e);
		}
	}

	private static String resource(String path) throws IOException {
		try (InputStream stream = ConversationBrowser.class.getClassLoader().getResourceAsStream(path)) {
			if (stream == null) {
				throw new IOException("Missing " + path);
			}
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
