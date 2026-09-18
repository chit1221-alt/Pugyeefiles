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

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import fi.iki.elonen.NanoHTTPD;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** JSON-only Streamable HTTP, bound to loopback for a separately managed HTTPS tunnel. */
public final class McpHttpServer extends NanoHTTPD {
  public static final int PORT = 8787;
  private static final int MAX_BODY = 65536;
  private final OAuthManager auth;
  private final OwnerCredentials owner;
  private final McpProtocol protocol;
  private final String loginTemplate;
  private final String consentTemplate;
  private long rateWindow;
  private int requests;
  private long loginWindow;
  private int loginFailures;

  public McpHttpServer(
      int port,
      OAuthManager auth,
      OwnerCredentials owner,
      McpProtocol protocol,
      String loginTemplate,
      String consentTemplate) {
    super("127.0.0.1", port);
    this.auth = auth;
    this.owner = owner;
    this.protocol = protocol;
    this.loginTemplate = loginTemplate;
    this.consentTemplate = consentTemplate;
    setAsyncRunner(new BoundedRunner());
  }

  @Override
  public Response serve(IHTTPSession session) {
    try {
      if (!allowed()) return json(429, "{\"error\":\"rate_limited\"}");
      String host = session.getHeaders().get("host");
      String authority = URI.create(auth.origin()).getRawAuthority();
      if (!authority.equalsIgnoreCase(host)
          && !("127.0.0.1:" + getListeningPort()).equals(host)
          && !("localhost:" + getListeningPort()).equals(host))
        return json(403, "{\"error\":\"invalid_host\"}");
      String origin = session.getHeaders().get("origin");
      String path = session.getUri();
      Method method = session.getMethod();
      if (method == Method.GET) {
        if (path.equals("/.well-known/oauth-protected-resource")
            || path.equals("/.well-known/oauth-protected-resource/mcp"))
          return json(200, auth.protectedMetadata());
        if (path.equals("/.well-known/oauth-authorization-server"))
          return json(200, auth.metadata());
        if (path.equals("/health"))
          return json(200, "{\"name\":\"PugyeeFiles\",\"status\":\"running\"}");
        if (path.equals("/authorize")) {
          Map<String, String> arguments = form(session.getQueryParameterString());
          auth.validateAuthorization(arguments);
          return login(arguments, false);
        }
      }
      if (path.equals("/authorize") && method == Method.POST) {
        requireType(session, "application/x-www-form-urlencoded");
        Map<String, String> arguments = form(body(session));
        String profile = OAuthManager.required(arguments, "profile");
        String password = OAuthManager.required(arguments, "password");
        arguments.remove("profile");
        arguments.remove("password");
        auth.validateAuthorization(arguments);
        if (!allowedLoginAttempt()) return json(429, "{\"error\":\"rate_limited\"}");
        if (!owner.authenticate(profile, password)) {
          return login(arguments, true);
        }
        resetLoginFailures();
        return consent(auth.authorize(arguments));
      }
      if (path.equals("/register") && method == Method.POST) {
        requireType(session, "application/json");
        return json(201, auth.register(Json.parse(body(session)).getAsJsonObject()));
      }
      if (path.equals("/token") && method == Method.POST) {
        requireType(session, "application/x-www-form-urlencoded");
        try {
          return json(200, auth.token(form(body(session))));
        } catch (IllegalArgumentException e) {
          return json(400, "{\"error\":\"invalid_grant\"}");
        }
      }
      if (path.equals("/consent") && method == Method.POST) {
        // Consent carries no owner credential. The phone UI must approve this exact random ticket.
        if (origin != null && !auth.origin().equals(origin))
          return json(403, "{\"error\":\"invalid_origin\"}");
        requireType(session, "application/x-www-form-urlencoded");
        String ticket = OAuthManager.required(form(body(session)), "request");
        String redirect = auth.complete(ticket);
        if (redirect == null) {
          for (OAuthManager.Pending p : auth.pending()) if (p.id.equals(ticket)) return consent(p);
          return json(400, "{\"error\":\"expired_or_denied_request\"}");
        }
        Response response = json(303, "{}");
        response.addHeader("Location", redirect);
        return response;
      }
      if (!path.equals("/mcp")) return json(404, "{\"error\":\"not_found\"}");
      Boolean canConvert = auth.authenticate(session.getHeaders().get("authorization"));
      if (canConvert == null) {
        Response response = json(401, "{\"error\":\"unauthorized\"}");
        response.addHeader(
            "WWW-Authenticate",
            "Bearer resource_metadata=\""
                + auth.origin()
                + "/.well-known/oauth-protected-resource\", scope=\""
                + auth.scopes()
                + "\"");
        return response;
      }
      if (method != Method.POST) {
        Response response = json(405, "{}");
        response.addHeader("Allow", "POST");
        return response;
      }
      String version = session.getHeaders().get("mcp-protocol-version");
      if (version != null && !McpProtocol.supports(version))
        return json(400, "{\"error\":\"unsupported_protocol_version\"}");
      String accept = session.getHeaders().get("accept");
      if (accept == null
          || !accept.contains("application/json")
          || !accept.contains("text/event-stream"))
        return json(406, "{\"error\":\"accept_json_and_event_stream\"}");
      requireType(session, "application/json");
      JsonObject reply;
      try {
        reply = protocol.handle(Json.parse(body(session)), canConvert);
      } catch (RuntimeException e) {
        return json(400, McpProtocol.error(JsonNull.INSTANCE, -32700, "Invalid JSON"));
      }
      return reply == null ? json(202, "") : json(200, reply);
    } catch (BodyException e) {
      return json(e.status, "{\"error\":\"invalid_body\"}");
    } catch (IOException | RuntimeException e) {
      // Never echo provider paths, authorization codes, access tokens or exception details.
      return json(400, "{\"error\":\"invalid_request\"}");
    }
  }

  private synchronized boolean allowed() {
    long now = System.nanoTime();
    if (now - rateWindow > TimeUnit.MINUTES.toNanos(1)) {
      rateWindow = now;
      requests = 0;
    }
    return ++requests <= 600;
  }

  private Response consent(OAuthManager.Pending p) {
    String page =
        consentTemplate
            .replace("{{CODE}}", escape(p.code()))
            .replace("{{REQUEST}}", escape(p.id))
            .replace("{{REDIRECT}}", escape(p.redirect))
            .replace("{{SCOPE}}", escape(p.convert ? "files.read files.convert" : "files.read"))
            .replace("{{NAME}}", escape(p.name));
    Response response = response(200, "text/html; charset=utf-8", page);
    response.addHeader(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none';"
            + " base-uri 'none'");
    return response;
  }

  private Response login(Map<String, String> arguments, boolean failed) {
    StringBuilder fields = new StringBuilder();
    for (String name :
        new String[] {
          "client_id",
          "redirect_uri",
          "response_type",
          "code_challenge_method",
          "code_challenge",
          "resource",
          "scope",
          "state"
        }) {
      String value = arguments.get(name);
      if (value != null)
        fields
            .append("<input type=\"hidden\" name=\"")
            .append(name)
            .append("\" value=\"")
            .append(escape(value))
            .append("\">");
    }
    String page =
        loginTemplate
            .replace("{{FIELDS}}", fields.toString())
            .replace("{{ERROR}}", failed ? "Incorrect profile name or password." : "");
    Response response = response(failed ? 401 : 200, "text/html; charset=utf-8", page);
    response.addHeader(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none';"
            + " base-uri 'none'");
    return response;
  }

  private synchronized boolean allowedLoginAttempt() {
    long now = System.nanoTime();
    if (now - loginWindow > TimeUnit.MINUTES.toNanos(1)) {
      loginWindow = now;
      loginFailures = 0;
    }
    return ++loginFailures <= 10;
  }

  private synchronized void resetLoginFailures() {
    loginFailures = 0;
    loginWindow = System.nanoTime();
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static void requireType(IHTTPSession session, String expected) throws BodyException {
    String contentType = session.getHeaders().get("content-type");
    if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase(expected))
      throw new BodyException(415);
  }

  private static String body(IHTTPSession session) throws IOException, BodyException {
    if (session.getHeaders().containsKey("transfer-encoding")) throw new BodyException(411);
    String length = session.getHeaders().get("content-length");
    if (length == null || !length.matches("[0-9]{1,8}")) throw new BodyException(411);
    int size = Integer.parseInt(length);
    if (size > MAX_BODY) throw new BodyException(413);
    byte[] bytes = new byte[size];
    int position = 0;
    while (position < size) {
      int count = session.getInputStream().read(bytes, position, size - position);
      if (count < 0) throw new EOFException();
      position += count;
    }
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString();
  }

  static Map<String, String> form(String encoded) throws IOException {
    Map<String, String> result = new HashMap<>();
    if (encoded == null || encoded.isEmpty()) return result;
    for (String part : encoded.split("&")) {
      String[] pair = part.split("=", 2);
      String key = URLDecoder.decode(pair[0], "UTF-8");
      if (result.containsKey(key) || result.size() >= 20)
        throw new IllegalArgumentException("Duplicate or excess parameters");
      result.put(key, pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : "");
    }
    return result;
  }

  private static Response json(int status, JsonObject body) {
    return json(status, Json.CODEC.toJson(body));
  }

  private static Response json(int status, String body) {
    return response(status, "application/json", body);
  }

  private static Response response(int status, String type, String body) {
    Response response = newFixedLengthResponse(Response.Status.lookup(status), type, body);
    response.addHeader("Cache-Control", "no-store");
    response.addHeader("X-Content-Type-Options", "nosniff");
    response.addHeader("X-Frame-Options", "DENY");
    response.addHeader("Referrer-Policy", "no-referrer");
    response.closeConnection(true);
    return response;
  }

  private static final class BodyException extends Exception {
    final int status;

    BodyException(int status) {
      this.status = status;
    }
  }

  private static final class BoundedRunner implements AsyncRunner {
    private final Set<ClientHandler> handlers =
        Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final ThreadPoolExecutor executor =
        new ThreadPoolExecutor(
            4,
            4,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),
            runnable -> {
              Thread t = new Thread(runnable, "pugyee-mcp");
              t.setDaemon(true);
              return t;
            });

    @Override
    public void exec(ClientHandler handler) {
      handlers.add(handler);
      try {
        executor.execute(handler);
      } catch (RejectedExecutionException e) {
        handler.close();
        handlers.remove(handler);
      }
    }

    @Override
    public void closed(ClientHandler handler) {
      handlers.remove(handler);
    }

    @Override
    public void closeAll() {
      for (ClientHandler handler : handlers) handler.close();
      handlers.clear();
      executor.shutdownNow();
    }
  }
}
