/*
 * Copyright (C) 2014-2026 Arpit Khurana <arpitkh96@gmail.com>, Vishal Nehra <vishalmeham2@gmail.com>,
 * Emmanuel Messulam<emmanuelbendavid@gmail.com>, Raymond Lai <airwave209gt at gmail.com> and Contributors.
 *
 * This file is part of Amaze File Manager.
 *
 * Amaze File Manager is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package uk.pugyee.mcp;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Single-owner authorization. Only the non-exported phone UI can approve a pending request. */
public final class OAuthManager {
  private static final String CHATGPT_CLIENT_ID = "ChatGPT";
  private static final String CHATGPT_CLIENT_NAME = "ChatGPT";
  private static final String CHATGPT_REDIRECT =
      "https://chatgpt.com/connector_platform_oauth_redirect";

  public interface Clock {
    long now();
  }

  public interface ClientStorage {
    void save(String clients);
  }

  private static final long MINUTE = 60_000L;
  private final String origin;
  private final boolean conversions;
  private final Clock clock;
  private final ClientStorage storage;
  private final SecureRandom random = new SecureRandom();
  private final Map<String, JsonObject> clients = new HashMap<>();
  private final Map<String, Pending> pending = new HashMap<>();
  private final Map<String, Grant> codes = new HashMap<>();
  private final Map<String, Grant> access = new HashMap<>();
  private final Map<String, Grant> refresh = new HashMap<>();

  public OAuthManager(
      String origin, boolean conversions, String savedClients, ClientStorage storage) {
    this(origin, conversions, savedClients, storage, System::currentTimeMillis);
  }

  OAuthManager(
      String origin, boolean conversions, String savedClients, ClientStorage storage, Clock clock) {
    this.origin = validateOrigin(origin);
    this.conversions = conversions;
    this.storage = storage;
    this.clock = clock;
    if (savedClients != null && !savedClients.isEmpty()) {
      for (JsonElement element : Json.parse(savedClients).getAsJsonArray()) {
        JsonObject client = element.getAsJsonObject();
        if (clients.size() >= 50) break;
        clients.put(Json.string(client, "client_id"), client);
      }
    }
  }

  public static String validateOrigin(String value) {
    URI uri = URI.create(value);
    if (!"https".equals(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) {
      throw new IllegalArgumentException("Enter an HTTPS address without a path");
    }
    return "https://" + uri.getRawAuthority();
  }

  public String origin() {
    return origin;
  }

  public String resource() {
    return origin + "/mcp";
  }

  public String scopes() {
    return conversions ? "files.read files.convert" : "files.read";
  }

  public JsonObject protectedMetadata() {
    JsonObject result = new JsonObject();
    result.addProperty("resource", resource());
    result.add("authorization_servers", Json.CODEC.toJsonTree(new String[] {origin}));
    result.add("scopes_supported", Json.CODEC.toJsonTree(scopes().split(" ")));
    result.add("bearer_methods_supported", Json.parse("[\"header\"]"));
    return result;
  }

  public JsonObject metadata() {
    JsonObject result = new JsonObject();
    result.addProperty("issuer", origin);
    result.addProperty("authorization_endpoint", origin + "/authorize");
    result.addProperty("token_endpoint", origin + "/token");
    result.addProperty("registration_endpoint", origin + "/register");
    result.addProperty("authorization_response_iss_parameter_supported", true);
    result.add("response_types_supported", Json.parse("[\"code\"]"));
    result.add("grant_types_supported", Json.parse("[\"authorization_code\",\"refresh_token\"]"));
    result.add("token_endpoint_auth_methods_supported", Json.parse("[\"none\"]"));
    result.add("code_challenge_methods_supported", Json.parse("[\"S256\"]"));
    result.add("scopes_supported", Json.CODEC.toJsonTree(scopes().split(" ")));
    return result;
  }

  public synchronized JsonObject register(JsonObject input) {
    if (clients.size() >= 50) throw new IllegalArgumentException("Client limit reached");
    if (input.has("token_endpoint_auth_method")
        && !Json.string(input, "token_endpoint_auth_method").equals("none"))
      throw new IllegalArgumentException("Public clients only");
    JsonArray redirects = input.getAsJsonArray("redirect_uris");
    if (redirects == null || redirects.size() == 0 || redirects.size() > 5)
      throw new IllegalArgumentException("Invalid redirects");
    for (JsonElement redirect : redirects) {
      if (!redirect.isJsonPrimitive() || !redirect.getAsJsonPrimitive().isString())
        throw new IllegalArgumentException();
      URI uri = URI.create(redirect.getAsString());
      if (!"https".equals(uri.getScheme())
          || uri.getHost() == null
          || uri.getFragment() != null
          || uri.getUserInfo() != null
          || redirect.getAsString().length() > 2048)
        throw new IllegalArgumentException("HTTPS redirect required");
    }
    String name = input.has("client_name") ? Json.string(input, "client_name") : "MCP client";
    if (name.length() > 80) throw new IllegalArgumentException("Client name too long");
    JsonObject result = new JsonObject();
    result.addProperty("client_id", secret());
    result.addProperty("client_name", name);
    result.add("redirect_uris", redirects.deepCopy());
    result.addProperty("token_endpoint_auth_method", "none");
    result.add("grant_types", Json.parse("[\"authorization_code\",\"refresh_token\"]"));
    result.add("response_types", Json.parse("[\"code\"]"));
    clients.put(Json.string(result, "client_id"), result);
    storage.save(Json.CODEC.toJson(clients.values()));
    return result.deepCopy();
  }

  public synchronized Pending authorize(Map<String, String> args) {
    cleanup();
    if (pending.size() >= 20 || codes.size() >= 50 || refresh.size() >= 50)
      throw new IllegalArgumentException("Too many requests");
    Authorization input = authorization(args);
    Pending request =
        new Pending(
            secret(),
            input.name,
            input.redirect,
            input.clientId,
            input.challenge,
            input.state,
            input.convert,
            clock.now() + 5 * MINUTE);
    pending.put(request.id, request);
    return request;
  }

  public synchronized void validateAuthorization(Map<String, String> args) {
    cleanup();
    authorization(args);
  }

  private Authorization authorization(Map<String, String> args) {
    String clientId = required(args, "client_id");
    JsonObject client = clients.get(clientId);
    String redirect = required(args, "redirect_uri");
    if (client == null && CHATGPT_CLIENT_ID.equals(clientId) && CHATGPT_REDIRECT.equals(redirect)) {
      client = manualChatGptClient();
      clients.put(clientId, client);
      storage.save(Json.CODEC.toJson(clients.values()));
    }
    if (client == null
        || !client.getAsJsonArray("redirect_uris").contains(Json.CODEC.toJsonTree(redirect)))
      throw new IllegalArgumentException("Unregistered redirect");
    if (!"code".equals(required(args, "response_type"))
        || !"S256".equals(required(args, "code_challenge_method")))
      throw new IllegalArgumentException("PKCE S256 required");
    String challenge = required(args, "code_challenge");
    if (!challenge.matches("[A-Za-z0-9_-]{43}"))
      throw new IllegalArgumentException("Invalid challenge");
    if (!resource().equals(required(args, "resource")))
      throw new IllegalArgumentException("Invalid resource");
    String scope = args.containsKey("scope") ? args.get("scope") : scopes();
    boolean read = false;
    boolean convert = false;
    for (String part : scope.split(" ")) {
      if (part.equals("files.read")) read = true;
      else if (part.equals("files.convert") && conversions) convert = true;
      else throw new IllegalArgumentException("Invalid scope");
    }
    if (!read) throw new IllegalArgumentException("Read scope required");
    String state = required(args, "state");
    return new Authorization(
        Json.string(client, "client_name"), redirect, clientId, challenge, state, convert);
  }

  public synchronized List<Pending> pending() {
    cleanup();
    return new ArrayList<>(pending.values());
  }

  public synchronized void approve(String requestId) {
    cleanup();
    Pending request = pending.get(requestId);
    if (request != null) request.approved = true;
  }

  public synchronized void deny(String requestId) {
    pending.remove(requestId);
  }

  public synchronized String complete(String requestId) {
    cleanup();
    Pending request = pending.get(requestId);
    if (request == null || !request.approved) return null;
    pending.remove(requestId);
    String code = secret();
    codes.put(
        code,
        new Grant(
            request.clientId,
            request.redirect,
            request.challenge,
            request.convert,
            clock.now() + MINUTE));
    return request.redirect
        + (request.redirect.contains("?") ? "&" : "?")
        + "code="
        + code
        + "&state="
        + encode(request.state)
        + "&iss="
        + encode(origin);
  }

  public synchronized JsonObject token(Map<String, String> args) {
    cleanup();
    String client = required(args, "client_id");
    if (!clients.containsKey(client) || !resource().equals(required(args, "resource")))
      throw new IllegalArgumentException("Invalid client or resource");
    Grant grant;
    if ("authorization_code".equals(required(args, "grant_type"))) {
      grant = codes.get(required(args, "code"));
      String verifier = required(args, "code_verifier");
      if (grant == null
          || !grant.client.equals(client)
          || !grant.redirect.equals(required(args, "redirect_uri"))
          || !verifier.matches("[A-Za-z0-9._~-]{43,128}")
          || !equal(grant.challenge, challenge(verifier)))
        throw new IllegalArgumentException("Invalid grant");
      codes.remove(args.get("code"));
    } else if ("refresh_token".equals(args.get("grant_type"))) {
      grant = refresh.get(required(args, "refresh_token"));
      if (grant == null || !grant.client.equals(client))
        throw new IllegalArgumentException("Invalid grant");
      refresh.remove(args.get("refresh_token"));
    } else throw new IllegalArgumentException("Unsupported grant");
    if (access.size() >= 100 || refresh.size() >= 50)
      throw new IllegalArgumentException("Token limit reached");
    String token = secret();
    String nextRefresh = secret();
    access.put(token, new Grant(client, "", "", grant.convert, clock.now() + 60 * MINUTE));
    refresh.put(
        nextRefresh, new Grant(client, "", "", grant.convert, clock.now() + 24 * 60 * MINUTE));
    JsonObject result = new JsonObject();
    result.addProperty("access_token", token);
    result.addProperty("token_type", "Bearer");
    result.addProperty("expires_in", 3600);
    result.addProperty("refresh_token", nextRefresh);
    result.addProperty("scope", grant.convert ? "files.read files.convert" : "files.read");
    return result;
  }

  /** Null is unauthenticated; Boolean indicates the conversion grant. */
  public synchronized Boolean authenticate(String header) {
    cleanup();
    if (header == null || !header.startsWith("Bearer ")) return null;
    Grant grant = access.get(header.substring(7));
    return grant == null ? null : grant.convert;
  }

  public synchronized void revokeAll() {
    pending.clear();
    codes.clear();
    access.clear();
    refresh.clear();
  }

  private void cleanup() {
    long now = clock.now();
    Iterator<Pending> p = pending.values().iterator();
    while (p.hasNext()) if (p.next().expires <= now) p.remove();
    clean(codes, now);
    clean(access, now);
    clean(refresh, now);
  }

  private static void clean(Map<String, Grant> map, long now) {
    Iterator<Grant> i = map.values().iterator();
    while (i.hasNext()) if (i.next().expires <= now) i.remove();
  }

  private String secret() {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return base64url(bytes);
  }

  private static JsonObject manualChatGptClient() {
    JsonObject result = new JsonObject();
    result.addProperty("client_id", CHATGPT_CLIENT_ID);
    result.addProperty("client_name", CHATGPT_CLIENT_NAME);
    result.add("redirect_uris", Json.CODEC.toJsonTree(new String[] {CHATGPT_REDIRECT}));
    result.addProperty("token_endpoint_auth_method", "none");
    result.add("grant_types", Json.parse("[\"authorization_code\",\"refresh_token\"]"));
    result.add("response_types", Json.parse("[\"code\"]"));
    return result;
  }

  static String challenge(String verifier) {
    try {
      return base64url(
          MessageDigest.getInstance("SHA-256")
              .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean equal(String a, String b) {
    return MessageDigest.isEqual(
        a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
  }

  private static String base64url(byte[] bytes) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    StringBuilder out = new StringBuilder();
    int bits = 0, buffer = 0;
    for (byte b : bytes) {
      buffer = (buffer << 8) | (b & 255);
      bits += 8;
      while (bits >= 6) {
        bits -= 6;
        out.append(alphabet.charAt((buffer >> bits) & 63));
      }
    }
    if (bits > 0) out.append(alphabet.charAt((buffer << (6 - bits)) & 63));
    return out.toString();
  }

  static String required(Map<String, String> args, String name) {
    String value = args.get(name);
    if (value == null || value.isEmpty() || value.length() > 2048)
      throw new IllegalArgumentException("Missing or invalid " + name);
    return value;
  }

  private static String encode(String text) {
    try {
      return URLEncoder.encode(text, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  public static final class Pending {
    public final String id, name, redirect;
    public final boolean convert;
    private final String clientId, challenge, state;
    private final long expires;
    private boolean approved;

    private Pending(
        String id,
        String name,
        String redirect,
        String clientId,
        String challenge,
        String state,
        boolean convert,
        long expires) {
      this.id = id;
      this.name = name;
      this.redirect = redirect;
      this.clientId = clientId;
      this.challenge = challenge;
      this.state = state;
      this.convert = convert;
      this.expires = expires;
    }

    public String code() {
      return id.substring(0, 8);
    }
  }

  private static final class Authorization {
    final String name, redirect, clientId, challenge, state;
    final boolean convert;

    Authorization(
        String name,
        String redirect,
        String clientId,
        String challenge,
        String state,
        boolean convert) {
      this.name = name;
      this.redirect = redirect;
      this.clientId = clientId;
      this.challenge = challenge;
      this.state = state;
      this.convert = convert;
    }
  }

  private static final class Grant {
    final String client, redirect, challenge;
    final boolean convert;
    final long expires;

    Grant(String client, String redirect, String challenge, boolean convert, long expires) {
      this.client = client;
      this.redirect = redirect;
      this.challenge = challenge;
      this.convert = convert;
      this.expires = expires;
    }
  }
}
