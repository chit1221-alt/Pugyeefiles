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

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class McpIntegrationTest {
  private static final String ORIGIN = "https://phone.example";
  private static final String REDIRECT = "https://chatgpt.com/connector_platform_oauth_redirect";
  private static final String VERIFIER = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH";
  private static final String PROFILE = "Chit";
  private static final String PASSWORD = "correct horse battery staple";
  private OAuthManager auth;
  private McpHttpServer server;
  private MemoryFiles files;
  private String client;

  @Before
  public void setUp() throws Exception {
    auth = new OAuthManager(ORIGIN, true, "[]", json -> {});
    files = new MemoryFiles();
    files.data.put("notes.md", "# My notes\n\nHello **world**!\n");
    server =
        new McpHttpServer(
            0,
            auth,
            new OwnerCredentials(PROFILE, OwnerCredentials.hash(PASSWORD)),
            new McpProtocol(files, true),
            "<form method=\"post\">{{FIELDS}}<i>{{ERROR}}</i>"
                + "<input name=\"profile\"><input name=\"password\" type=\"password\"></form>",
            "<p>{{CODE}}</p><form>{{REQUEST}}</form><b>{{NAME}}</b>");
    server.start(1000, true);
    Reply registration =
        request(
            "POST",
            "/register",
            "{\"client_name\":\"ChatGPT\",\"redirect_uris\":[\""
                + REDIRECT
                + "\"],\"token_endpoint_auth_method\":\"none\"}",
            null,
            null,
            "application/json");
    assertEquals(201, registration.status);
    client = registration.json().get("client_id").getAsString();
  }

  @After
  public void tearDown() {
    server.stop();
  }

  @Test
  public void realHttpOAuthInitializeListReadAndConvert() throws Exception {
    assertEquals(404, request("GET", "/File%20Manager%202B.apk", null, null, null, null).status);
    Reply challenge = rpc(null, "tools/list", "{}");
    assertEquals(401, challenge.status);
    assertTrue(challenge.authenticate.contains("oauth-protected-resource"));
    assertEquals(
        ORIGIN + "/mcp",
        request("GET", "/.well-known/oauth-protected-resource/mcp", null, null, null, null)
            .json()
            .get("resource")
            .getAsString());
    assertEquals(
        "S256",
        request("GET", "/.well-known/oauth-authorization-server", null, null, null, null)
            .json()
            .getAsJsonArray("code_challenge_methods_supported")
            .get(0)
            .getAsString());
    String token = login("files.read files.convert");
    JsonObject initialized =
        rpc(
                token,
                "initialize",
                "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}")
            .json();
    assertEquals(
        McpProtocol.VERSION,
        initialized.getAsJsonObject("result").get("protocolVersion").getAsString());
    Reply notification =
        request(
            "POST",
            "/mcp",
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
            token,
            null,
            "application/json");
    assertEquals(202, notification.status);
    assertEquals("", notification.body);
    assertEquals(
        3,
        rpc(token, "tools/list", "{}")
            .json()
            .getAsJsonObject("result")
            .getAsJsonArray("tools")
            .size());
    JsonObject listing = call(token, "list_files", "{}");
    assertEquals(
        "notes.md",
        listing
            .getAsJsonObject("structuredContent")
            .getAsJsonArray("entries")
            .get(0)
            .getAsJsonObject()
            .get("path")
            .getAsString());
    JsonObject read =
        call(token, "read_text_file", "{\"path\":\"notes.md\",\"offset\":0,\"limit\":5}");
    assertEquals("# My ", read.getAsJsonObject("structuredContent").get("text").getAsString());
    assertEquals(5, read.getAsJsonObject("structuredContent").get("nextOffset").getAsInt());
    JsonObject converted =
        call(token, "convert_text_file", "{\"source\":\"notes.md\",\"destination\":\"notes.txt\"}");
    assertFalse(converted.get("isError").getAsBoolean());
    assertTrue(files.data.get("notes.txt").contains("Hello world!"));
    assertEquals("# My notes\n\nHello **world**!\n", files.data.get("notes.md"));
    assertEquals(405, request("GET", "/mcp", null, token, null, null).status);
    auth.revokeAll();
    assertEquals(401, rpc(token, "tools/list", "{}").status);
  }

  @Test
  public void noApprovalMeansNoCodeAndNoTokens() throws Exception {
    Map<String, String> authorization = authorization("files.read");
    Reply page = request("GET", "/authorize?" + encode(authorization), null, null, null, null);
    assertEquals(200, page.status);
    assertTrue(page.body.contains("type=\"password\""));
    assertTrue(auth.pending().isEmpty());
    Reply wrong = authorize(authorization, "wrong password");
    assertEquals(401, wrong.status);
    assertTrue(wrong.body.contains("Incorrect profile name or password"));
    assertTrue(auth.pending().isEmpty());
    assertEquals(200, authorize(authorization, PASSWORD).status);
    OAuthManager.Pending pending = auth.pending().get(0);
    Reply waiting =
        request(
            "POST",
            "/consent",
            "request=" + pending.id,
            null,
            ORIGIN,
            "application/x-www-form-urlencoded");
    assertEquals(200, waiting.status);
    assertNull(waiting.location);
    auth.deny(pending.id);
    assertEquals(
        400,
        request(
                "POST",
                "/consent",
                "request=" + pending.id,
                null,
                ORIGIN,
                "application/x-www-form-urlencoded")
            .status);
  }

  @Test
  public void readOnlyClientCannotConvertEvenWhenHostAllowsIt() throws Exception {
    String token = login("files.read");
    assertEquals(
        2,
        rpc(token, "tools/list", "{}")
            .json()
            .getAsJsonObject("result")
            .getAsJsonArray("tools")
            .size());
    assertTrue(
        call(token, "convert_text_file", "{\"source\":\"notes.md\",\"destination\":\"secret.txt\"}")
            .get("isError")
            .getAsBoolean());
    assertFalse(files.data.containsKey("secret.txt"));
  }

  @Test
  public void rejectsTraversalUnknownArgumentsAndOverwriteWithoutTouchingStore() throws Exception {
    String token = login("files.read files.convert");
    for (String path :
        new String[] {
          "../secret.txt",
          "/secret.txt",
          "content://private/a.txt",
          "a/../secret.txt",
          "a\\b.txt",
          "%2e%2e/secret.txt"
        }) {
      JsonObject args = new JsonObject();
      args.addProperty("path", path);
      assertTrue(call(token, "read_text_file", args.toString()).get("isError").getAsBoolean());
    }
    assertEquals(0, files.reads);
    assertTrue(
        call(token, "read_text_file", "{\"path\":\"notes.md\",\"sudo\":true}")
            .get("isError")
            .getAsBoolean());
    assertEquals(0, files.reads);
    assertTrue(
        call(token, "convert_text_file", "{\"source\":\"notes.md\",\"destination\":\"notes.md\"}")
            .get("isError")
            .getAsBoolean());
    assertEquals("# My notes\n\nHello **world**!\n", files.data.get("notes.md"));
  }

  @Test
  public void protocolVersionBadBodiesAndOwnerConsentOriginsAreRejected() throws Exception {
    String token = login("files.read");
    assertEquals(415, request("POST", "/mcp", "{}", token, null, "text/plain").status);
    assertEquals(400, request("POST", "/mcp", "{broken", token, null, "application/json").status);
    String oversized = new String(new char[70000]).replace('\0', 'a');
    assertEquals(413, request("POST", "/mcp", oversized, token, null, "application/json").status);
    assertEquals(
        400,
        request("POST", "/consent", "request=x", null, null, "application/x-www-form-urlencoded")
            .status);
    assertEquals(
        403,
        request(
                "POST",
                "/consent",
                "request=x",
                null,
                "https://evil.example",
                "application/x-www-form-urlencoded")
            .status);
    assertEquals(
        400, request("GET", "/authorize?client_id=x&client_id=y", null, null, null, null).status);
    assertFalse(
        call(token, "read_text_file", "{\"path\":\"notes.md\"}").get("isError").getAsBoolean());
  }

  @Test
  public void maliciousRedirectAndUnrequestedScopesAreRejected() throws Exception {
    Map<String, String> args = authorization("files.read");
    args.put("redirect_uri", "https://evil.example/callback");
    assertEquals(400, request("GET", "/authorize?" + encode(args), null, null, null, null).status);
    args = authorization("files.delete");
    assertEquals(400, request("GET", "/authorize?" + encode(args), null, null, null, null).status);
    args = authorization("files.read");
    args.put("resource", "https://other.example/mcp");
    assertEquals(400, request("GET", "/authorize?" + encode(args), null, null, null, null).status);
    assertTrue(auth.pending().isEmpty());
  }

  private String login(String scope) throws Exception {
    Map<String, String> authorization = authorization(scope);
    assertEquals(
        200, request("GET", "/authorize?" + encode(authorization), null, null, null, null).status);
    assertTrue(auth.pending().isEmpty());
    assertEquals(200, authorize(authorization, PASSWORD).status);
    OAuthManager.Pending pending = auth.pending().get(0);
    auth.approve(pending.id);
    Reply redirect =
        request(
            "POST",
            "/consent",
            "request=" + pending.id,
            null,
            ORIGIN,
            "application/x-www-form-urlencoded");
    assertEquals(303, redirect.status);
    Map<String, String> response = McpHttpServer.form(new URL(redirect.location).getQuery());
    assertEquals("state with & punctuation", response.get("state"));
    assertEquals(ORIGIN, response.get("iss"));
    Map<String, String> exchange = new HashMap<>();
    exchange.put("grant_type", "authorization_code");
    exchange.put("client_id", client);
    exchange.put("redirect_uri", REDIRECT);
    exchange.put("resource", ORIGIN + "/mcp");
    exchange.put("code", response.get("code"));
    exchange.put("code_verifier", VERIFIER);
    Reply token =
        request(
            "POST", "/token", encode(exchange), null, null, "application/x-www-form-urlencoded");
    assertEquals(200, token.status);
    assertEquals(
        400,
        request("POST", "/token", encode(exchange), null, null, "application/x-www-form-urlencoded")
            .status);
    return token.json().get("access_token").getAsString();
  }

  private Reply authorize(Map<String, String> authorization, String password) throws Exception {
    Map<String, String> form = new HashMap<>(authorization);
    form.put("profile", PROFILE);
    form.put("password", password);
    return request(
        "POST", "/authorize", encode(form), null, ORIGIN, "application/x-www-form-urlencoded");
  }

  private Map<String, String> authorization(String scope) {
    Map<String, String> args = new HashMap<>();
    args.put("client_id", client);
    args.put("redirect_uri", REDIRECT);
    args.put("response_type", "code");
    args.put("code_challenge_method", "S256");
    args.put("code_challenge", OAuthManager.challenge(VERIFIER));
    args.put("resource", ORIGIN + "/mcp");
    args.put("scope", scope);
    args.put("state", "state with & punctuation");
    return args;
  }

  private JsonObject call(String token, String name, String args) throws Exception {
    return rpc(token, "tools/call", "{\"name\":\"" + name + "\",\"arguments\":" + args + "}")
        .json()
        .getAsJsonObject("result");
  }

  private Reply rpc(String token, String method, String params) throws Exception {
    return request(
        "POST",
        "/mcp",
        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\",\"params\":" + params + "}",
        token,
        null,
        "application/json");
  }

  private Reply request(
      String method, String path, String body, String token, String origin, String contentType)
      throws Exception {
    HttpURLConnection connection =
        (HttpURLConnection)
            new URL("http://127.0.0.1:" + server.getListeningPort() + path).openConnection();
    connection.setInstanceFollowRedirects(false);
    connection.setRequestMethod(method);
    connection.setReadTimeout(3000);
    connection.setConnectTimeout(3000);
    connection.setRequestProperty("Accept", "application/json, text/event-stream");
    if (token != null) connection.setRequestProperty("Authorization", "Bearer " + token);
    if (origin != null) connection.setRequestProperty("Origin", origin);
    if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
    if (body != null) {
      connection.setDoOutput(true);
      connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
    Reply reply = new Reply();
    reply.status = connection.getResponseCode();
    reply.location = connection.getHeaderField("Location");
    reply.authenticate = connection.getHeaderField("WWW-Authenticate");
    try (InputStream input =
            reply.status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      if (input != null) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      reply.body = new String(out.toByteArray(), StandardCharsets.UTF_8);
    } finally {
      connection.disconnect();
    }
    return reply;
  }

  private static String encode(Map<String, String> args) throws Exception {
    StringBuilder out = new StringBuilder();
    for (Map.Entry<String, String> arg : args.entrySet()) {
      if (out.length() > 0) out.append('&');
      out.append(URLEncoder.encode(arg.getKey(), "UTF-8"))
          .append('=')
          .append(URLEncoder.encode(arg.getValue(), "UTF-8"));
    }
    return out.toString();
  }

  private static final class Reply {
    int status;
    String body, location, authenticate;

    JsonObject json() {
      return Json.parse(body).getAsJsonObject();
    }
  }

  static final class MemoryFiles implements FileStore {
    final Map<String, String> data = new HashMap<>();
    int reads;

    @Override
    public List<Entry> list(String path) {
      List<Entry> entries = new ArrayList<>();
      for (String key : data.keySet()) entries.add(new Entry(key, false, data.get(key).length()));
      return entries;
    }

    @Override
    public String read(String path) throws IOException {
      reads++;
      if (!data.containsKey(path)) throw new IOException();
      return data.get(path);
    }

    @Override
    public synchronized String create(String path, String text, String mime) throws IOException {
      if (data.containsKey(path)) throw new IOException();
      data.put(path, text);
      return path;
    }
  }
}
