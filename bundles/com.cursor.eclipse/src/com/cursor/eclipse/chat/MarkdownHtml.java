package com.cursor.eclipse.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, escaping Markdown-to-HTML renderer for chat messages. */
public final class MarkdownHtml {

	private static final Pattern FENCE = Pattern.compile("^\\s*(`{3,})(.*)$");
	private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?(?:\\s*:?-{3,}:?\\s*\\|)+\\s*$");
	private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
	private static final Pattern BULLET = Pattern.compile("^\\s*[-*]\\s+(.*)$");

	private MarkdownHtml() {
	}

	public static String render(String markdown) {
		if (markdown == null || markdown.isEmpty()) {
			return "";
		}
		String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		StringBuilder out = new StringBuilder();
		boolean inCode = false;
		boolean inList = false;
		String fence = "";
		String language = "";

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			Matcher fenceMatch = FENCE.matcher(line);
			if (fenceMatch.matches()) {
				boolean closing = inCode && fenceMatch.group(2).trim().isEmpty()
						&& fenceMatch.group(1).length() >= fence.length();
				if (!inCode || closing) {
					if (inList) {
						out.append("</ul>");
						inList = false;
					}
					if (inCode) {
						out.append("</code></pre>");
						inCode = false;
						fence = "";
					} else {
						language = fenceMatch.group(2).trim().replaceAll("[^A-Za-z0-9_+-]", "");
						fence = fenceMatch.group(1);
						out.append("<pre><code")
								.append(language.isEmpty() ? "" : " class=\"language-" + language + "\"").append('>');
						inCode = true;
					}
					continue;
				}
			}
			if (inCode) {
				out.append(escape(line)).append('\n');
				continue;
			}
			if (i + 1 < lines.length && line.contains("|") && TABLE_DIVIDER.matcher(lines[i + 1]).matches()) {
				if (inList) {
					out.append("</ul>");
					inList = false;
				}
				out.append("<table><thead><tr>");
				for (String cell : cells(line)) {
					out.append("<th>").append(inline(cell)).append("</th>");
				}
				out.append("</tr></thead><tbody>");
				i += 2;
				while (i < lines.length && lines[i].contains("|") && !lines[i].isBlank()) {
					out.append("<tr>");
					for (String cell : cells(lines[i])) {
						out.append("<td>").append(inline(cell)).append("</td>");
					}
					out.append("</tr>");
					i++;
				}
				out.append("</tbody></table>");
				i--;
				continue;
			}
			Matcher heading = HEADING.matcher(line);
			if (heading.matches()) {
				if (inList) {
					out.append("</ul>");
					inList = false;
				}
				int level = heading.group(1).length();
				out.append("<h").append(level).append('>').append(inline(heading.group(2).trim())).append("</h")
						.append(level).append('>');
				continue;
			}
			Matcher bullet = BULLET.matcher(line);
			if (bullet.matches()) {
				if (!inList) {
					out.append("<ul>");
					inList = true;
				}
				String item = bullet.group(1);
				boolean checked = item.matches("^\\[[xX]\\]\\s+.*");
				boolean task = checked || item.matches("^\\[ \\]\\s+.*");
				if (task) {
					item = item.substring(3).trim();
				}
				out.append("<li>").append(task ? (checked ? "&#9745; " : "&#9744; ") : "").append(inline(item))
						.append("</li>");
				continue;
			}
			if (inList) {
				out.append("</ul>");
				inList = false;
			}
			if (line.isBlank()) {
				continue;
			}
			if (line.startsWith("> ")) {
				out.append("<blockquote>").append(inline(line.substring(2))).append("</blockquote>");
			} else {
				out.append("<p>").append(inline(line)).append("</p>");
			}
		}
		if (inList) {
			out.append("</ul>");
		}
		if (inCode) {
			out.append("</code></pre>");
		}
		return out.toString();
	}

	private static List<String> cells(String line) {
		String value = line.trim();
		if (value.startsWith("|")) {
			value = value.substring(1);
		}
		if (value.endsWith("|")) {
			value = value.substring(0, value.length() - 1);
		}
		List<String> cells = new ArrayList<>();
		for (String cell : value.split("\\|", -1)) {
			cells.add(cell.trim());
		}
		return cells;
	}

	private static String inline(String value) {
		String safe = escape(value);
		safe = safe.replaceAll("`([^`]+)`", "<code>$1</code>")
				.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>")
				.replaceAll("~~([^~]+)~~", "<del>$1</del>")
				.replaceAll("(?<!\\*)\\*([^*]+)\\*(?!\\*)", "<em>$1</em>");
		return safe.replaceAll("\\[([^]]+)]\\((https?://[^ )]+)\\)", "<a href=\"$2\">$1</a>");
	}

	public static String escape(String value) {
		if (value == null) {
			return "";
		}
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
				.replace("'", "&#39;");
	}
}
