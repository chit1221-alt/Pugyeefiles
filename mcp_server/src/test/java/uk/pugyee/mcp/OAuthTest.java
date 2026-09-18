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

import java.util.HashMap;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonObject;

public class OAuthTest {
  private OAuthManager auth;
  private String client;
  private String saved;
  private long now;
  private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk";

  @Before
  public void setUp() {
    now = 1000;
    auth = new OAuthManager("https://phone.example", true, "[]", json -> saved = json, () -> now);
    client =
        auth.register(
                Json.parse(
                        "{\"client_name\":\"ChatGPT\",\"redirect_uris\":[\"https://chatgpt.com/callback\"]}")
                    .getAsJsonObject())
            .get("client_id")
            .getAsString();
  }

  @Test
  public void pkceMatchesRfc7636Vector() {
    assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OAuthManager.challenge(VERIFIER));
  }

  @Test
  public void codeIsBoundToVerifierClientRedirectAndResource() throws Exception {
    Map<String, String> valid = exchange();
    for (String key : new String[] {"code_verifier", "client_id", "redirect_uri", "resource"}) {
      Map<String, String> wrong = new HashMap<>(valid);
      wrong.put(key, "wrong");
      assertThrows(IllegalArgumentException.class, () -> auth.token(wrong));
    }
    JsonObject tokens = auth.token(valid);
    assertNotNull(auth.authenticate("Bearer " + tokens.get("access_token").getAsString()));
    assertThrows(IllegalArgumentException.class, () -> auth.token(valid));
  }

  @Test
  public void refreshRotatesAndIsClientBound() throws Exception {
    JsonObject tokens = auth.token(exchange());
    Map<String, String> refresh = new HashMap<>();
    refresh.put("grant_type", "refresh_token");
    refresh.put("client_id", "wrong");
    refresh.put("resource", auth.resource());
    refresh.put("refresh_token", tokens.get("refresh_token").getAsString());
    assertThrows(IllegalArgumentException.class, () -> auth.token(refresh));
    refresh.put("client_id", client);
    JsonObject renewed = auth.token(refresh);
    assertNotEquals(tokens.get("refresh_token"), renewed.get("refresh_token"));
    assertThrows(IllegalArgumentException.class, () -> auth.token(refresh));
  }

  @Test
  public void accessExpiresAndStopRevokesRefreshToo() throws Exception {
    JsonObject tokens = auth.token(exchange());
    String bearer = "Bearer " + tokens.get("access_token").getAsString();
    assertNotNull(auth.authenticate(bearer));
    now += 3600001;
    assertNull(auth.authenticate(bearer));
    Map<String, String> refresh = new HashMap<>();
    refresh.put("grant_type", "refresh_token");
    refresh.put("client_id", client);
    refresh.put("resource", auth.resource());
    refresh.put("refresh_token", tokens.get("refresh_token").getAsString());
    auth.revokeAll();
    assertThrows(IllegalArgumentException.class, () -> auth.token(refresh));
  }

  @Test
  public void requestAndCodeExpire() throws Exception {
    OAuthManager.Pending pending = auth.authorize(authorization());
    now += 300001;
    auth.approve(pending.id);
    assertNull(auth.complete(pending.id));
    Map<String, String> exchange = exchange();
    now += 60001;
    assertThrows(IllegalArgumentException.class, () -> auth.token(exchange));
  }

  @Test
  public void registrationSurvivesRestartButAccessDoesNot() throws Exception {
    JsonObject token = auth.token(exchange());
    OAuthManager restarted = new OAuthManager(auth.origin(), true, saved, json -> {}, () -> now);
    assertNull(restarted.authenticate("Bearer " + token.get("access_token").getAsString()));
    assertNotNull(restarted.authorize(authorization()));
  }

  @Test
  public void registrationRejectsUnsafeCallbacksAndHostRejectsInvalidOrigins() {
    for (String redirect :
        new String[] {
          "http://example.com",
          "javascript:alert(1)",
          "https://example.com/#fragment",
          "https://user@example.com/callback"
        }) {
      JsonObject client = new JsonObject();
      client.add("redirect_uris", Json.CODEC.toJsonTree(new String[] {redirect}));
      assertThrows(IllegalArgumentException.class, () -> auth.register(client));
    }
    for (String url :
        new String[] {
          "http://phone.example",
          "https://phone.example/mcp",
          "https://user:pass@phone.example",
          "https://phone.example?x=1",
          "https://phone.example/#frag"
        }) {
      assertThrows(IllegalArgumentException.class, () -> OAuthManager.validateOrigin(url));
    }
  }

  private Map<String, String> authorization() {
    Map<String, String> args = new HashMap<>();
    args.put("client_id", client);
    args.put("redirect_uri", "https://chatgpt.com/callback");
    args.put("response_type", "code");
    args.put("code_challenge_method", "S256");
    args.put("code_challenge", OAuthManager.challenge(VERIFIER));
    args.put("resource", auth.resource());
    args.put("scope", "files.read files.convert");
    args.put("state", "test");
    return args;
  }

  private Map<String, String> exchange() throws Exception {
    OAuthManager.Pending pending = auth.authorize(authorization());
    auth.approve(pending.id);
    String redirect = auth.complete(pending.id);
    assertNull(auth.complete(pending.id));
    Map<String, String> response = McpHttpServer.form(new java.net.URI(redirect).getQuery());
    Map<String, String> exchange = new HashMap<>();
    exchange.put("grant_type", "authorization_code");
    exchange.put("client_id", client);
    exchange.put("redirect_uri", "https://chatgpt.com/callback");
    exchange.put("resource", auth.resource());
    exchange.put("code", response.get("code"));
    exchange.put("code_verifier", VERIFIER);
    return exchange;
  }
}
