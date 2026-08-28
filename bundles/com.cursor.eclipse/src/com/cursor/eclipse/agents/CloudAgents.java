package com.cursor.eclipse.agents;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.prefs.PreferenceConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** Lists the Cloud Agents of the signed-in account through the Cursor API. */
public final class CloudAgents {

	private static final String API = "https://api.cursor.com";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
	private static final int PAGE_SIZE = 100;
	private static final Gson PRETTY_JSON = new GsonBuilder().setPrettyPrinting().create();

	private CloudAgents() {
	}

	/** Thrown when the API rejects the key, so the view can point at preferences. */
	public static final class NotAuthorizedException extends IOException {

		private static final long serialVersionUID = 1L;

		NotAuthorizedException(String message) {
			super(message);
		}
	}

	/**
	 * The API key to list agents with: the Cursor preference first, then
	 * {@code CURSOR_API_KEY}.
	 *
	 * <p>The API needs a key even when {@code agent login} already signed this
	 * machine in, because the CLI session is not a REST credential.
	 */
	public static String apiKey() {
		CursorPlugin plugin = CursorPlugin.getDefault();
		String preference = plugin == null ? null : plugin.getPreferenceStore().getString(PreferenceConstants.API_KEY);
		if (preference != null && !preference.isBlank()) {
			return preference.trim();
		}
		String environment = System.getenv("CURSOR_API_KEY");
		return environment == null ? "" : environment.trim();
	}

	/** Requests up to {@code limit} agents, newest first. */
	public static List<CloudAgent> list(String apiKey, int limit) throws IOException, InterruptedException {
		return list(apiKey, limit, true);
	}

	/** Requests every agent, newest first, optionally including archived agents. */
	public static List<CloudAgent> list(String apiKey, boolean includeArchived) throws IOException, InterruptedException {
		return list(apiKey, Integer.MAX_VALUE, includeArchived);
	}

	private static List<CloudAgent> list(String apiKey, int limit, boolean includeArchived)
			throws IOException, InterruptedException {
		if (apiKey == null || apiKey.isBlank()) {
			throw new NotAuthorizedException("Add a Cursor API key to list cloud agents");
		}
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		try {
			List<CloudAgent> agents = new ArrayList<>();
			Set<String> cursors = new HashSet<>();
			String cursor = null;
			do {
				int pageSize = Math.min(PAGE_SIZE, limit - agents.size());
				String url = API + "/v1/agents?limit=" + pageSize + "&includeArchived=" + includeArchived
						+ (cursor == null ? "" : "&cursor=" + encode(cursor));
				String body = get(client, apiKey, url, true);
				agents.addAll(enrich(client, apiKey, parse(body)));
				String next = nextCursor(body);
				cursor = next != null && cursors.add(next) ? next : null;
			} while (cursor != null && agents.size() < limit);
			return List.copyOf(agents.size() <= limit ? agents : agents.subList(0, limit));
		} catch (FallbackException e) {
			// Keys issued before the v1 beta only answer on the legacy endpoint.
			return legacyList(client, apiKey, limit, includeArchived);
		}
	}

	private static String get(HttpClient client, String apiKey, String url) throws IOException, InterruptedException {
		return get(client, apiKey, url, false);
	}

	private static String get(HttpClient client, String apiKey, String url, boolean allowFallback)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
				.header("Authorization", authorization(apiKey)).header("Accept", "application/json").GET().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		return body(response, allowFallback);
	}

	private static String post(HttpClient client, String apiKey, String url, String json)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
				.header("Authorization", authorization(apiKey)).header("Accept", "application/json")
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json)).build();
		return body(client.send(request, HttpResponse.BodyHandlers.ofString()), false);
	}

	private static String body(HttpResponse<String> response, boolean allowFallback) throws IOException {
		int status = response.statusCode();
		if (status >= 200 && status < 300) {
			return response.body();
		}
		if (status == 401 || status == 403) {
			throw new NotAuthorizedException("Cursor rejected the API key (HTTP " + status + ")");
		}
		if (allowFallback && (status == 404 || status == 501)) {
			throw new FallbackException();
		}
		throw new IOException("Cursor API returned HTTP " + status + errorSuffix(response.body()));
	}

	private static String authorization(String apiKey) {
		return "Basic " + Base64.getEncoder().encodeToString((apiKey + ":").getBytes(StandardCharsets.UTF_8));
	}

	private static String errorSuffix(String body) {
		if (body == null || body.isBlank()) {
			return "";
		}
		String compact = body.replaceAll("\\s+", " ").trim();
		return ": " + (compact.length() > 200 ? compact.substring(0, 200) + "\u2026" : compact);
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String nextCursor(String body) {
		try {
			JsonElement root = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
			return root.isJsonObject() ? string(root.getAsJsonObject(), "nextCursor") : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static List<CloudAgent> legacyList(HttpClient client, String apiKey, int limit, boolean includeArchived)
			throws IOException, InterruptedException {
		List<CloudAgent> agents = new ArrayList<>();
		Set<String> cursors = new HashSet<>();
		String cursor = null;
		do {
			int pageSize = Math.min(PAGE_SIZE, limit - agents.size());
			String url = API + "/v0/agents?limit=" + pageSize
					+ (cursor == null ? "" : "&cursor=" + encode(cursor));
			String body = get(client, apiKey, url);
			parse(body).stream().filter(agent -> includeArchived || !archived(agent)).forEach(agents::add);
			String next = nextCursor(body);
			cursor = next != null && cursors.add(next) ? next : null;
		} while (cursor != null && agents.size() < limit);
		return List.copyOf(agents.size() <= limit ? agents : agents.subList(0, limit));
	}

	private static boolean archived(CloudAgent agent) {
		return agent.archived();
	}

	private static List<CloudAgent> enrich(HttpClient client, String apiKey, List<CloudAgent> agents)
			throws InterruptedException {
		List<CompletableFuture<CloudAgent>> details = agents.stream()
				.map(agent -> detail(client, apiKey, agent)).toList();
		List<CloudAgent> enriched = new ArrayList<>(agents.size());
		for (int i = 0; i < details.size(); i++) {
			try {
				enriched.add(details.get(i).join());
			} catch (CompletionException e) {
				enriched.add(agents.get(i));
			}
		}
		if (Thread.interrupted()) {
			throw new InterruptedException();
		}
		return enriched;
	}

	private static CompletableFuture<CloudAgent> detail(HttpClient client, String apiKey, CloudAgent fallback) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/v1/agents/" + encode(fallback.id())))
				.timeout(REQUEST_TIMEOUT).header("Authorization", authorization(apiKey))
				.header("Accept", "application/json").GET().build();
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenCompose(response -> {
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return CompletableFuture.completedFuture(fallback);
			}
			CloudAgent parsed = parseAgent(response.body());
			CloudAgent merged = parsed == null ? fallback : merge(fallback, parsed);
			String latestRunId = member(response.body(), "latestRunId");
			return latestRunId == null ? CompletableFuture.completedFuture(merged)
					: runStatus(client, apiKey, merged, latestRunId);
		}).exceptionally(error -> fallback);
	}

	private static CompletableFuture<CloudAgent> runStatus(HttpClient client, String apiKey, CloudAgent agent,
			String runId) {
		HttpRequest request = HttpRequest.newBuilder(
				URI.create(API + "/v1/agents/" + encode(agent.id()) + "/runs/" + encode(runId)))
				.timeout(REQUEST_TIMEOUT).header("Authorization", authorization(apiKey))
				.header("Accept", "application/json").GET().build();
		return client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				return agent;
			}
			String status = member(response.body(), "status");
			return status == null ? agent
					: new CloudAgent(agent.id(), agent.name(), status, agent.url(), agent.repository(),
							agent.created(), agent.archived());
		}).exceptionally(error -> agent);
	}

	private static CloudAgent merge(CloudAgent summary, CloudAgent detail) {
		return new CloudAgent(summary.id(), value(detail.name(), summary.name()), value(detail.status(), summary.status()),
				value(detail.url(), summary.url()), value(detail.repository(), summary.repository()),
				detail.created().equals(Instant.EPOCH) ? summary.created() : detail.created(),
				summary.archived() || detail.archived());
	}

	private static String value(String preferred, String fallback) {
		return preferred == null || preferred.isBlank() ? fallback : preferred;
	}

	private static String member(String body, String name) {
		try {
			JsonElement root = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
			return root.isJsonObject() ? string(root.getAsJsonObject(), name) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Creates a Cloud Agent in the selected repository. */
	public static CloudAgent create(String apiKey, String repository, String prompt)
			throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		JsonObject request = new JsonObject();
		JsonObject promptObject = new JsonObject();
		promptObject.addProperty("text", prompt);
		request.add("prompt", promptObject);
		JsonArray repos = new JsonArray();
		JsonObject repo = new JsonObject();
		repo.addProperty("url", repository);
		repos.add(repo);
		request.add("repos", repos);
		JsonElement root = JsonParser.parseString(post(client, apiKey, API + "/v1/agents", request.toString()));
		if (!root.isJsonObject()) {
			throw new IOException("Cursor returned an invalid Cloud Agent");
		}
		JsonObject object = root.getAsJsonObject();
		JsonObject nested = object(object, "agent");
		CloudAgent created = agent(nested == null ? object : nested);
		if (created == null) {
			throw new IOException("Cursor did not return a Cloud Agent ID");
		}
		return created.repository() == null
				? new CloudAgent(created.id(), created.name(), created.status(), created.url(), repository,
						created.created(), created.archived())
				: created;
	}

	/** Retrieves the messages shown by a cloud agent. */
	public static List<CloudMessage> conversation(String apiKey, String agentId)
			throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		return parseConversation(get(client, apiKey, API + "/v0/agents/" + encode(agentId) + "/conversation"));
	}

	/** Starts a follow-up run and returns its run id when the API supplies one. */
	public static String followUp(String apiKey, String agentId, String prompt)
			throws IOException, InterruptedException {
		return followUp(apiKey, agentId, prompt, null);
	}

	public static String followUp(String apiKey, String agentId, String prompt, String mode)
			throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		JsonObject request = new JsonObject();
		JsonObject promptObject = new JsonObject();
		promptObject.addProperty("text", prompt);
		request.add("prompt", promptObject);
		if ("agent".equals(mode) || "plan".equals(mode)) {
			request.addProperty("mode", mode);
		}
		String response = post(client, apiKey, API + "/v1/agents/" + encode(agentId) + "/runs", request.toString());
		JsonElement root = JsonParser.parseString(response == null || response.isBlank() ? "{}" : response);
		if (!root.isJsonObject()) {
			return null;
		}
		JsonObject run = object(root.getAsJsonObject(), "run");
		return string(run == null ? root.getAsJsonObject() : run, "id");
	}

	/** Retrieves the current state of one cloud run. */
	public static CloudRun run(String apiKey, String agentId, String runId) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		JsonElement root = JsonParser.parseString(get(client, apiKey,
				API + "/v1/agents/" + encode(agentId) + "/runs/" + encode(runId)));
		JsonObject object = root.isJsonObject() ? root.getAsJsonObject() : new JsonObject();
		return new CloudRun(string(object, "status"), string(object, "result"));
	}

	/** Streams one cloud run, including tool calls, until its terminal result. */
	public static CloudRun streamRun(String apiKey, String agentId, String runId, Consumer<CloudToolCall> tools)
			throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		String url = API + "/v1/agents/" + encode(agentId) + "/runs/" + encode(runId) + "/stream";
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30))
				.header("Authorization", authorization(apiKey)).header("Accept", "text/event-stream").GET().build();
		HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
		if (response.statusCode() == 410) {
			response.body().close();
			return run(apiKey, agentId, runId);
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			response.body().close();
			throw new IOException("Cursor stream returned HTTP " + response.statusCode());
		}
		String event = null;
		StringBuilder data = new StringBuilder();
		CloudRun terminal = null;
		try (Stream<String> lines = response.body()) {
			for (String line : (Iterable<String>) lines::iterator) {
				if (line.isEmpty()) {
					terminal = dispatchStreamEvent(event, data.toString(), tools, terminal);
					event = null;
					data.setLength(0);
				} else if (line.startsWith("event:")) {
					event = line.substring(6).trim();
				} else if (line.startsWith("data:")) {
					if (!data.isEmpty()) {
						data.append('\n');
					}
					data.append(line.substring(5).trim());
				}
			}
			terminal = dispatchStreamEvent(event, data.toString(), tools, terminal);
		}
		return terminal == null ? run(apiKey, agentId, runId) : terminal;
	}

	private static CloudRun dispatchStreamEvent(String event, String data, Consumer<CloudToolCall> tools,
			CloudRun current) {
		if (event == null || data.isBlank()) {
			return current;
		}
		if ("tool_call".equals(event)) {
			CloudToolCall tool = parseToolCall(data);
			if (tool != null) {
				tools.accept(tool);
			}
			return current;
		}
		if ("result".equals(event)) {
			try {
				JsonObject result = JsonParser.parseString(data).getAsJsonObject();
				return new CloudRun(string(result, "status"), string(result, "text"));
			} catch (RuntimeException ignored) {
				return current;
			}
		}
		return current;
	}

	static CloudToolCall parseToolCall(String data) {
		try {
			JsonObject call = JsonParser.parseString(data).getAsJsonObject();
			String name = string(call, "name");
			StringBuilder detail = new StringBuilder();
			appendJson(detail, "Command / input", call.get("args"));
			appendJson(detail, "Result", call.get("result"));
			return new CloudToolCall(value(string(call, "callId"), "tool"), value(name, "Tool"),
					toolKind(name), string(call, "status"), detail.isEmpty() ? null : detail.toString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static void appendJson(StringBuilder target, String heading, JsonElement value) {
		if (value == null || value.isJsonNull()) {
			return;
		}
		if (!target.isEmpty()) {
			target.append("\n\n");
		}
		target.append(heading).append(":\n");
		if (value.isJsonPrimitive()) {
			target.append(value.getAsString());
		} else {
			target.append(PRETTY_JSON.toJson(value));
		}
	}

	private static String toolKind(String name) {
		String value = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
		if (value.contains("terminal") || value.contains("command") || value.contains("shell")) {
			return "execute";
		}
		if (value.contains("edit") || value.contains("write") || value.contains("patch")) {
			return "edit";
		}
		if (value.contains("read")) {
			return "read";
		}
		if (value.contains("search") || value.contains("grep")) {
			return "search";
		}
		return "other";
	}

	public static void cancelRun(String apiKey, String agentId, String runId) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		post(client, apiKey, API + "/v1/agents/" + encode(agentId) + "/runs/" + encode(runId) + "/cancel", "{}");
	}

	public record CloudRun(String status, String result) {
		public boolean terminal() {
			String value = status == null ? "" : status.toUpperCase(java.util.Locale.ROOT);
			return value.equals("FINISHED") || value.equals("ERROR") || value.equals("CANCELLED")
					|| value.equals("EXPIRED");
		}
	}

	/** Reads both the v1 ({@code items}) and the legacy v0 ({@code agents}) shape. */
	static List<CloudAgent> parse(String body) {
		JsonElement root = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
		if (!root.isJsonObject()) {
			return List.of();
		}
		JsonObject object = root.getAsJsonObject();
		JsonArray agents = array(object, "items");
		if (agents == null) {
			agents = array(object, "agents");
		}
		if (agents == null) {
			return List.of();
		}
		List<CloudAgent> parsed = new ArrayList<>();
		for (JsonElement element : agents) {
			if (element.isJsonObject()) {
				CloudAgent agent = agent(element.getAsJsonObject());
				if (agent != null) {
					parsed.add(agent);
				}
			}
		}
		return List.copyOf(parsed);
	}

	static List<CloudMessage> parseConversation(String body) {
		JsonElement root = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
		if (!root.isJsonObject()) {
			return List.of();
		}
		JsonArray messages = array(root.getAsJsonObject(), "messages");
		if (messages == null) {
			return List.of();
		}
		List<CloudMessage> parsed = new ArrayList<>();
		for (JsonElement element : messages) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject message = element.getAsJsonObject();
			String text = string(message, "text");
			if (text == null) {
				continue;
			}
			String type = string(message, "type");
			String role = type != null && type.toLowerCase().contains("user") ? "user" : "assistant";
			String id = string(message, "id");
			parsed.add(new CloudMessage(id == null ? "cloud-" + parsed.size() : id, role, text));
		}
		return List.copyOf(parsed);
	}

	private static CloudAgent parseAgent(String body) {
		try {
			JsonElement root = JsonParser.parseString(body == null || body.isBlank() ? "{}" : body);
			return root.isJsonObject() ? agent(root.getAsJsonObject()) : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static CloudAgent agent(JsonObject json) {
		String id = string(json, "id");
		if (id == null) {
			return null;
		}
		String name = string(json, "name");
		JsonObject target = object(json, "target");
		String url = string(json, "url");
		if (url == null && target != null) {
			url = string(target, "url");
		}
		if (url == null) {
			url = "https://cursor.com/agents/" + id;
		}
		JsonObject source = object(json, "source");
		String repository = source == null ? null : string(source, "repository");
		if (repository == null) {
			JsonArray repositories = array(json, "repos");
			if (repositories != null && !repositories.isEmpty() && repositories.get(0).isJsonObject()) {
				repository = string(repositories.get(0).getAsJsonObject(), "url");
			}
		}
		String status = string(json, "status");
		boolean archived = status != null && status.toUpperCase(java.util.Locale.ROOT).contains("ARCHIVED");
		return new CloudAgent(id, name == null || name.isBlank() ? id : name, status, url, repository,
				instant(string(json, "createdAt")), archived);
	}

	private static JsonArray array(JsonObject json, String member) {
		JsonElement element = json.get(member);
		return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
	}

	private static JsonObject object(JsonObject json, String member) {
		JsonElement element = json.get(member);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static String string(JsonObject json, String member) {
		JsonElement element = json.get(member);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	private static Instant instant(String value) {
		if (value == null) {
			return Instant.EPOCH;
		}
		try {
			return Instant.parse(value);
		} catch (RuntimeException e) {
			return Instant.EPOCH;
		}
	}

	private static final class FallbackException extends IOException {

		private static final long serialVersionUID = 1L;
	}
}
