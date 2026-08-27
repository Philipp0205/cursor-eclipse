package com.cursor.eclipse.agents;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.cursor.eclipse.CursorPlugin;
import com.cursor.eclipse.prefs.PreferenceConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Lists the Cloud Agents of the signed-in account through the Cursor API. */
public final class CloudAgents {

	private static final String API = "https://api.cursor.com";
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

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
		if (apiKey == null || apiKey.isBlank()) {
			throw new NotAuthorizedException("Add a Cursor API key to list cloud agents");
		}
		HttpClient client = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
		try {
			return parse(get(client, apiKey, API + "/v1/agents?limit=" + limit));
		} catch (FallbackException e) {
			// Keys issued before the v1 beta only answer on the legacy endpoint.
			return parse(get(client, apiKey, API + "/v0/agents?limit=" + limit));
		}
	}

	private static String get(HttpClient client, String apiKey, String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT)
				.header("Authorization", "Bearer " + apiKey).header("Accept", "application/json").GET().build();
		HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
		int status = response.statusCode();
		if (status == 200) {
			return response.body();
		}
		if (status == 401 || status == 403) {
			throw new NotAuthorizedException("Cursor rejected the API key (HTTP " + status + ")");
		}
		if (status == 404 || status == 501) {
			throw new FallbackException();
		}
		throw new IOException("Cursor API returned HTTP " + status);
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
		return new CloudAgent(id, name == null || name.isBlank() ? id : name, string(json, "status"), url, repository,
				instant(string(json, "createdAt")));
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
