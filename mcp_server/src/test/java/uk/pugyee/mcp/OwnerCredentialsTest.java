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
 */

package uk.pugyee.mcp;

import static org.junit.Assert.*;

import org.junit.Test;

public class OwnerCredentialsTest {
  @Test
  public void hashesAndAuthenticatesWithoutStoringPassword() {
    String stored = OwnerCredentials.hash("correct horse battery staple");
    assertFalse(stored.contains("correct"));
    OwnerCredentials owner = new OwnerCredentials("Chit", stored);
    assertTrue(owner.authenticate("Chit", "correct horse battery staple"));
    assertFalse(owner.authenticate("chit", "correct horse battery staple"));
    assertFalse(owner.authenticate("Chit", "wrong password"));
  }

  @Test
  public void validatesProfilePasswordAndStoredHash() {
    assertTrue(OwnerCredentials.validProfile("Chit"));
    assertFalse(OwnerCredentials.validProfile(""));
    assertFalse(OwnerCredentials.validProfile(" Chit"));
    assertFalse(OwnerCredentials.validProfile("bad\nname"));
    assertTrue(OwnerCredentials.validPassword("12345678"));
    assertFalse(OwnerCredentials.validPassword("1234567"));
    assertThrows(IllegalArgumentException.class, () -> new OwnerCredentials("Chit", "plaintext"));
  }
}
