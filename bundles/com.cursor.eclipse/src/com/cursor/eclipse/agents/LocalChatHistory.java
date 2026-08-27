package com.cursor.eclipse.agents;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Reads the chats the Cursor CLI has stored on this machine.
 *
 * <p>The CLI keeps one folder per chat under {@code ~/.cursor/chats}, each with a
 * SQLite {@code store.db}. That layout is not part of any public API and
 * {@code agent ls} has no machine-readable output, so this reader only scans the
 * store for two anchors it can recognise (the workspace path the chat ran in and
 * the opening prompt) and falls back to the chat id whenever a store looks
 * unfamiliar. Nothing here throws: an unreadable chat is simply reported with
 * less detail.
 */
public final class LocalChatHistory {

	/** Chat stores grow with the conversation; the opening prompt is at the front. */
	private static final int MAX_SCAN_BYTES = 512 * 1024;
	private static final int MAX_TITLE_LENGTH = 100;
	private static final int MIN_RUN_LENGTH = 4;
	private static final int DEFAULT_PAGE_SIZE = 4096;
	private static final int LOG_HEADER_BYTES = 32;
	private static final int FRAME_HEADER_BYTES = 24;
	private static final String WORKSPACE_ANCHOR = "Workspace Path:";
	private static final String SQLITE_MAGIC = "SQLite format 3";

	/** Text that belongs to the prompt envelope rather than to the prompt. */
	private static final List<String> ENVELOPE_MARKERS = List.of(WORKSPACE_ANCHOR, "OS Version", "Shell:",
			"user_info", "system_reminder", "attached_files", "available_skills", "CREATE TABLE", "sqlite");

	private LocalChatHistory() {
	}

	/** The CLI chat store for the current user. */
	public static Path defaultRoot() {
		return Path.of(System.getProperty("user.home", "."), ".cursor", "chats");
	}

	/** Every stored chat, newest first. */
	public static List<LocalChat> read() {
		return read(defaultRoot());
	}

	/** Every chat stored under {@code chatsRoot}, newest first. */
	public static List<LocalChat> read(Path chatsRoot) {
		if (chatsRoot == null || !Files.isDirectory(chatsRoot)) {
			return List.of();
		}
		List<LocalChat> chats = new ArrayList<>();
		try (Stream<Path> stores = Files.walk(chatsRoot)) {
			stores.filter(path -> Files.isRegularFile(path) && "store.db".equals(path.getFileName().toString()))
					.forEach(store -> {
				LocalChat chat = readChat(store.getParent());
				if (chat != null) {
					chats.add(chat);
				}
			});
		} catch (IOException e) {
			return List.copyOf(chats);
		}
		chats.sort(Comparator.comparing(LocalChat::modified).reversed());
		return List.copyOf(chats);
	}

	static LocalChat readChat(Path directory) {
		Path store = directory.resolve("store.db");
		if (!Files.isRegularFile(store)) {
			return null;
		}
		String id = directory.getFileName().toString();
		byte[] head = readHead(store);
		String workspace = workspaceOf(head);
		String title = titleOf(head);
		Instant modified = lastModified(store);

		// A chat the CLI is still writing keeps its opening rows in the
		// write-ahead log until SQLite checkpoints them into the store.
		Path log = directory.resolve("store.db-wal");
		if (Files.isRegularFile(log)) {
			modified = latest(modified, lastModified(log));
			if (workspace == null || title == null) {
				byte[] pending = readHead(log);
				workspace = workspace == null ? workspaceOf(pending) : workspace;
				title = title == null ? titleOfLog(pending) : title;
			}
		}
		return new LocalChat(id, workspace == null ? null : new File(workspace),
				title == null ? shortId(id) : title, modified);
	}

	private static byte[] readHead(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			return in.readNBytes(MAX_SCAN_BYTES);
		} catch (IOException e) {
			// A chat that is mid-write still deserves a row in the view.
			return new byte[0];
		}
	}

	private static Instant lastModified(Path file) {
		try {
			return Files.getLastModifiedTime(file).toInstant();
		} catch (IOException e) {
			return Instant.EPOCH;
		}
	}

	private static Instant latest(Instant left, Instant right) {
		return left.isAfter(right) ? left : right;
	}

	/** The folder a chat ran in, read from the workspace line the CLI stores. */
	static String workspaceOf(byte[] store) {
		String scanned = new String(store, StandardCharsets.ISO_8859_1);
		int anchor = scanned.indexOf(WORKSPACE_ANCHOR);
		if (anchor < 0) {
			return null;
		}
		int start = anchor + WORKSPACE_ANCHOR.length();
		int end = start;
		while (end < scanned.length() && isTextByte(scanned.charAt(end))) {
			// Long strings are stored with escaped newlines, so "\n" ends the line too.
			if (scanned.charAt(end) == '\\' && end + 1 < scanned.length() && scanned.charAt(end + 1) == 'n') {
				break;
			}
			end++;
		}
		String path = decode(scanned.substring(start, end)).trim();
		return path.isEmpty() ? null : path;
	}

	/**
	 * A title taken from the opening prompt.
	 *
	 * <p>SQLite fills a page from its end downwards, so the first row written to a
	 * page sits at the highest offset in it. Scanning the first page that holds
	 * readable text from the back therefore reaches the oldest prompt first.
	 */
	static String titleOf(byte[] store) {
		int pageSize = pageSize(store);
		for (int page = pageSize; page < store.length; page += pageSize) {
			int end = Math.min(page + pageSize, store.length);
			String candidate = lastCandidate(new String(store, page, end - page, StandardCharsets.ISO_8859_1));
			if (candidate != null) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * The same title, read from a write-ahead log.
	 *
	 * <p>A log is a header followed by frames, each one a frame header and a copy
	 * of a page, written in the order the rows were committed.
	 */
	static String titleOfLog(byte[] log) {
		if (log.length < LOG_HEADER_BYTES) {
			return null;
		}
		int pageSize = readInt(log, 8);
		if (pageSize < 512 || pageSize > 65536) {
			return null;
		}
		int stride = FRAME_HEADER_BYTES + pageSize;
		for (int frame = LOG_HEADER_BYTES; frame + stride <= log.length; frame += stride) {
			// The frame header names the page it carries; page 1 is the schema.
			if (readInt(log, frame) == 1) {
				continue;
			}
			String candidate = lastCandidate(
					new String(log, frame + FRAME_HEADER_BYTES, pageSize, StandardCharsets.ISO_8859_1));
			if (candidate != null) {
				return candidate;
			}
		}
		return null;
	}

	private static String lastCandidate(String page) {
		String best = null;
		int runStart = -1;
		for (int i = 0; i <= page.length(); i++) {
			boolean text = i < page.length() && isTextByte(page.charAt(i));
			if (text && runStart < 0) {
				runStart = i;
			} else if (!text && runStart >= 0) {
				String run = clean(page.substring(runStart, i));
				if (run != null) {
					best = run;
				}
				runStart = -1;
			}
		}
		return best;
	}

	private static String clean(String run) {
		if (run.length() < MIN_RUN_LENGTH) {
			return null;
		}
		String text = decode(run);
		for (String marker : ENVELOPE_MARKERS) {
			if (text.contains(marker)) {
				return null;
			}
		}
		int escapedNewline = text.indexOf("\\n");
		if (escapedNewline > 0) {
			text = text.substring(0, escapedNewline);
		}
		// Rows carry framing bytes around the text; digits and punctuation from
		// that framing are never part of the prompt the user typed.
		text = text.replaceFirst("^[^\\p{L}]+", "").trim();
		if (text.length() < MIN_RUN_LENGTH || !text.codePoints().anyMatch(Character::isLetter)) {
			return null;
		}
		return text.length() > MAX_TITLE_LENGTH ? text.substring(0, MAX_TITLE_LENGTH).trim() + "\u2026" : text;
	}

	static int pageSize(byte[] store) {
		if (store.length < 100 || !new String(store, 0, SQLITE_MAGIC.length(), StandardCharsets.ISO_8859_1)
				.equals(SQLITE_MAGIC)) {
			return DEFAULT_PAGE_SIZE;
		}
		int declared = ((store[16] & 0xff) << 8) | (store[17] & 0xff);
		if (declared == 1) {
			return 65536;
		}
		return declared < 512 ? DEFAULT_PAGE_SIZE : declared;
	}

	private static int readInt(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8)
				| (bytes[offset + 3] & 0xff);
	}

	private static boolean isTextByte(char value) {
		return value >= 0x20 && value != 0x7f;
	}

	private static String decode(String latin1) {
		return new String(latin1.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
	}

	static String shortId(String id) {
		return id.length() <= 8 ? "Chat " + id : "Chat " + id.substring(0, 8);
	}
}
