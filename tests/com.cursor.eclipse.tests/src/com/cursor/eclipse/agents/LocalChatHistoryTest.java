package com.cursor.eclipse.agents;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.Test;

public class LocalChatHistoryTest {

	private static final int PAGE_SIZE = 4096;

	@Test
	public void readsChatsNewestFirst() throws IOException {
		Path root = Files.createTempDirectory("cursor-chats");
		writeChat(root, "11111111-aaaa", "Rename the login service", "/tmp/project-a");
		writeChat(root, "22222222-bbbb", "Add a test for the parser", "/tmp/project-b");
		Files.setLastModifiedTime(root.resolve("11111111-aaaa").resolve("store.db"),
				java.nio.file.attribute.FileTime.fromMillis(1_000L));

		List<LocalChat> chats = LocalChatHistory.read(root);

		assertEquals(2, chats.size());
		assertEquals("22222222-bbbb", chats.get(0).id());
		assertEquals("Add a test for the parser", chats.get(0).title());
		assertEquals("/tmp/project-b", chats.get(0).workspace().getAbsolutePath());
		assertEquals("11111111-aaaa", chats.get(1).id());
	}

	@Test
	public void fallsBackToTheChatIdWhenTheStoreIsUnreadable() throws IOException {
		Path root = Files.createTempDirectory("cursor-chats");
		Path chat = Files.createDirectory(root.resolve("33333333-cccc"));
		Files.write(chat.resolve("store.db"), new byte[64]);

		List<LocalChat> chats = LocalChatHistory.read(root);

		assertEquals(1, chats.size());
		assertEquals("Chat 33333333", chats.get(0).title());
		assertNull(chats.get(0).workspace());
	}

	@Test
	public void skipsFoldersWithoutAStore() throws IOException {
		Path root = Files.createTempDirectory("cursor-chats");
		Files.createDirectory(root.resolve("44444444-dddd"));

		assertTrue(LocalChatHistory.read(root).isEmpty());
	}

	@Test
	public void readsChatsNestedBelowWorkspaceFolders() throws IOException {
		Path root = Files.createTempDirectory("cursor-chats");
		Path workspace = Files.createDirectories(root.resolve("home-dev-project"));
		writeChat(workspace, "66666666-ffff", "Fix the nested session listing", "/home/dev/project");

		List<LocalChat> chats = LocalChatHistory.read(root);

		assertEquals(1, chats.size());
		assertEquals("66666666-ffff", chats.get(0).id());
		assertEquals("Fix the nested session listing", chats.get(0).title());
		assertEquals("/home/dev/project", chats.get(0).workspace().getAbsolutePath());
	}

	@Test
	public void readsNothingWhenTheStoreIsMissing() {
		assertTrue(LocalChatHistory.read(Path.of("/does/not/exist")).isEmpty());
	}

	@Test
	public void ignoresThePromptEnvelopeWhenPickingATitle() {
		byte[] store = store("Fix the flaky upload test", "/home/dev/repo");

		assertEquals("Fix the flaky upload test", LocalChatHistory.titleOf(store));
		assertEquals("/home/dev/repo", LocalChatHistory.workspaceOf(store));
	}

	@Test
	public void readsAChatThatIsStillOnlyInTheWriteAheadLog() throws IOException {
		Path root = Files.createTempDirectory("cursor-chats");
		Path chat = Files.createDirectory(root.resolve("55555555-eeee"));
		Files.write(chat.resolve("store.db"), header());
		Files.write(chat.resolve("store.db-wal"), log("Deploy the staging cluster", "/home/dev/live"));

		List<LocalChat> chats = LocalChatHistory.read(root);

		assertEquals(1, chats.size());
		assertEquals("Deploy the staging cluster", chats.get(0).title());
		assertEquals("/home/dev/live", chats.get(0).workspace().getAbsolutePath());
	}

	@Test
	public void readsThePageSizeFromTheSqliteHeader() {
		byte[] store = store("Prompt text here", "/tmp/x");

		assertEquals(PAGE_SIZE, LocalChatHistory.pageSize(store));
		assertEquals(PAGE_SIZE, LocalChatHistory.pageSize(new byte[8]));
	}

	private static void writeChat(Path root, String id, String prompt, String workspace) throws IOException {
		Path chat = Files.createDirectory(root.resolve(id));
		Files.write(chat.resolve("store.db"), store(prompt, workspace));
	}

	/**
	 * A store that mimics what the CLI leaves on disk: a SQLite header page, then
	 * a data page holding the context blob first and, at a higher offset, the
	 * opening prompt that SQLite wrote before it.
	 */
	private static byte[] store(String prompt, String workspace) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		bytes.writeBytes(header());
		bytes.writeBytes(dataPage(prompt, workspace));
		return bytes.toByteArray();
	}

	/**
	 * A write-ahead log the CLI has not checkpointed yet: the schema page first,
	 * then the page that holds the conversation.
	 */
	private static byte[] log(String prompt, String workspace) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] logHeader = new byte[32];
		logHeader[10] = (byte) (PAGE_SIZE >> 8);
		logHeader[11] = (byte) PAGE_SIZE;
		bytes.writeBytes(logHeader);
		bytes.writeBytes(frame(1, header()));
		bytes.writeBytes(frame(2, dataPage(prompt, workspace)));
		return bytes.toByteArray();
	}

	private static byte[] frame(int pageNumber, byte[] page) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] frameHeader = new byte[24];
		frameHeader[2] = (byte) (pageNumber >> 8);
		frameHeader[3] = (byte) pageNumber;
		bytes.writeBytes(frameHeader);
		bytes.writeBytes(page);
		return bytes.toByteArray();
	}

	private static byte[] header() {
		byte[] header = new byte[PAGE_SIZE];
		byte[] magic = "SQLite format 3\u0000".getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(magic, 0, header, 0, magic.length);
		header[16] = (byte) (PAGE_SIZE >> 8);
		header[17] = (byte) PAGE_SIZE;
		byte[] schema = "CREATE TABLE blobs (id INTEGER PRIMARY KEY, data BLOB)"
				.getBytes(StandardCharsets.ISO_8859_1);
		System.arraycopy(schema, 0, header, 100, schema.length);
		return header;
	}

	/** The context blob first, then the opening prompt at a higher offset. */
	private static byte[] dataPage(String prompt, String workspace) {
		byte[] page = new byte[PAGE_SIZE];
		byte[] context = ("<user_info>\\nWorkspace Path: " + workspace + "\\nShell: bash\\n</user_info>")
				.getBytes(StandardCharsets.UTF_8);
		System.arraycopy(context, 0, page, 512, context.length);
		byte[] first = ("\u0007" + prompt + "\u0000").getBytes(StandardCharsets.UTF_8);
		System.arraycopy(first, 0, page, PAGE_SIZE - first.length - 8, first.length);
		return page;
	}
}
