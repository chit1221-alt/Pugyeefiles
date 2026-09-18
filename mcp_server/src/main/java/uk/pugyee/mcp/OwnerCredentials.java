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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password verification for the single owner of a PugyeeFiles host. */
public final class OwnerCredentials {
  private static final String PREFIX = "pbkdf2-sha1";
  private static final int ITERATIONS = 210_000;
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private final String profile;
  private final String passwordHash;

  public OwnerCredentials(String profile, String passwordHash) {
    if (!validProfile(profile) || !validHash(passwordHash))
      throw new IllegalArgumentException("Invalid owner credentials");
    this.profile = profile;
    this.passwordHash = passwordHash;
  }

  public static boolean validProfile(String value) {
    if (value == null || value.length() < 1 || value.length() > 64 || !value.equals(value.trim()))
      return false;
    for (int i = 0; i < value.length(); i++)
      if (Character.isISOControl(value.charAt(i))) return false;
    return true;
  }

  public static boolean validPassword(String value) {
    return value != null && value.length() >= 8 && value.length() <= 128;
  }

  public static String hash(String password) {
    if (!validPassword(password)) throw new IllegalArgumentException("Invalid password");
    byte[] salt = new byte[SALT_BYTES];
    new SecureRandom().nextBytes(salt);
    return PREFIX + "$" + ITERATIONS + "$" + hex(salt) + "$" + hex(derive(password, salt));
  }

  public boolean authenticate(String suppliedProfile, String suppliedPassword) {
    byte[] expectedProfile = profile.getBytes(StandardCharsets.UTF_8);
    byte[] actualProfile =
        suppliedProfile == null ? new byte[0] : suppliedProfile.getBytes(StandardCharsets.UTF_8);
    boolean profileMatches = MessageDigest.isEqual(expectedProfile, actualProfile);
    String[] fields = passwordHash.split("\\$", -1);
    byte[] salt = unhex(fields[2]);
    byte[] expected = unhex(fields[3]);
    byte[] actual = derive(suppliedPassword == null ? "" : suppliedPassword, salt);
    return profileMatches && MessageDigest.isEqual(expected, actual);
  }

  private static boolean validHash(String value) {
    if (value == null) return false;
    String[] fields = value.split("\\$", -1);
    if (fields.length != 4 || !PREFIX.equals(fields[0])) return false;
    try {
      return Integer.parseInt(fields[1]) == ITERATIONS
          && unhex(fields[2]).length == SALT_BYTES
          && unhex(fields[3]).length == HASH_BITS / 8;
    } catch (RuntimeException e) {
      return false;
    }
  }

  private static byte[] derive(String password, byte[] salt) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_BITS);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(e);
    } finally {
      spec.clearPassword();
    }
  }

  private static String hex(byte[] bytes) {
    char[] alphabet = "0123456789abcdef".toCharArray();
    char[] result = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      result[i * 2] = alphabet[(bytes[i] >>> 4) & 15];
      result[i * 2 + 1] = alphabet[bytes[i] & 15];
    }
    return new String(result);
  }

  private static byte[] unhex(String text) {
    if ((text.length() & 1) != 0) throw new IllegalArgumentException();
    byte[] result = new byte[text.length() / 2];
    for (int i = 0; i < result.length; i++) {
      int high = Character.digit(text.charAt(i * 2), 16);
      int low = Character.digit(text.charAt(i * 2 + 1), 16);
      if (high < 0 || low < 0) throw new IllegalArgumentException();
      result[i] = (byte) ((high << 4) | low);
    }
    return result;
  }
}
