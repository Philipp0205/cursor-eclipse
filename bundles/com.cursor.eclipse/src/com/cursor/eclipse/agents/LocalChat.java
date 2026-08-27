package com.cursor.eclipse.agents;

import java.io.File;
import java.time.Instant;

/**
 * A Cursor CLI chat that already exists on this machine.
 *
 * @param id        the chat id, which is also the ACP session id used to resume
 * @param workspace the folder the chat ran in, or {@code null} when unknown
 * @param title     a short label for the chat
 * @param modified  when the chat store was last written
 */
public record LocalChat(String id, File workspace, String title, Instant modified) {
}
