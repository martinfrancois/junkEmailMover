package martinfrancois;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Base64;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Exercises {@link SecurePreferences} against the real JCE provider and the real
 * {@link Preferences} backing store; nothing here is stubbed.
 */
public class SecurePreferencesEncryptionTest {

  /** The very same node {@link SecurePreferences} writes to. */
  private static final Preferences NODE =
      Preferences.userNodeForPackage(SecurePreferences.class);

  private static final String KEY_PREF = "secretKey";
  private static final String VALUE_SUFFIX = "_VALUE";
  private static final String IV_SUFFIX = "_IV";

  @Before
  @After
  public void clearPreferences() throws BackingStoreException {
    NODE.clear();
  }

  @Test
  public void roundTripsAValueTooLongForASingleBase64Line() {
    StringBuilder password = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      password.append((char) ('a' + (i % 26)));
    }
    String secret = password.toString();

    SecurePreferences prefs = new SecurePreferences();
    prefs.resetSecretKey();
    prefs.savePref("long", secret);

    // Base64 of a >76 byte ciphertext used to be written with embedded line breaks, which
    // java.util.Base64's basic decoder rejects, so the value could be stored but never read.
    assertEquals(-1, NODE.get("long" + VALUE_SUFFIX, "").indexOf('\n'));
    assertEquals(secret, prefs.loadPref("long"));
  }

  @Test
  public void roundTripsThroughAFreshInstanceUsingTheStoredKey() {
    SecurePreferences writer = new SecurePreferences();
    writer.resetSecretKey();
    writer.savePref("shared", "hunter2");

    // A second instance has to rebuild the key from the preferences node.
    assertEquals("hunter2", new SecurePreferences().loadPref("shared"));
  }

  @Test
  public void returnsAnEmptyStringForAKeyThatWasNeverStored() {
    SecurePreferences prefs = new SecurePreferences();
    prefs.resetSecretKey();

    assertEquals("", prefs.loadPref("never-stored"));
  }

  @Test
  public void generatesAFreshKeyWhenTheStoredOneIsUnusable() {
    NODE.put(KEY_PREF, ""); // decodes to a zero length key, which AES rejects

    SecurePreferences prefs = new SecurePreferences();
    prefs.savePref("recovered", "value");

    assertNotEquals("", NODE.get(KEY_PREF, ""));
    assertEquals("value", prefs.loadPref("recovered"));
  }

  @Test
  public void returnsNullWhenTheStoredCiphertextCannotBeDecrypted() {
    SecurePreferences prefs = new SecurePreferences();
    prefs.resetSecretKey();
    prefs.savePref("tampered", "value");

    // Ten bytes is not a whole number of AES blocks, so the cipher fails deterministically
    // rather than depending on whether random bytes happen to carry valid padding.
    NODE.put("tampered" + VALUE_SUFFIX, Base64.getEncoder().encodeToString(new byte[10]));

    assertNull(new SecurePreferences().loadPref("tampered"));
  }

  @Test
  public void storesNothingUsableWhenTheKeyIsTheWrongLength() {
    // A five byte key survives SecretKeySpec but is rejected by Cipher.init.
    NODE.put(KEY_PREF, Base64.getEncoder().encodeToString(new byte[5]));

    SecurePreferences prefs = new SecurePreferences();
    prefs.savePref("broken", "value");

    // Documents today's behaviour: the failure is logged and an empty value is written,
    // which reads back as "not stored" rather than as the plaintext.
    assertEquals("", NODE.get("broken" + VALUE_SUFFIX, "absent"));
    assertEquals("", prefs.loadPref("broken"));
  }

  @Test
  public void resetPrefsWipesStoredValuesAndInstallsANewKey() {
    SecurePreferences prefs = new SecurePreferences();
    prefs.resetSecretKey();
    prefs.savePref("wiped", "value");
    String keyBefore = NODE.get(KEY_PREF, "");
    assertNotEquals("", keyBefore);

    prefs.resetPrefs();

    assertEquals("absent", NODE.get("wiped" + VALUE_SUFFIX, "absent"));
    assertNull(NODE.getByteArray("wiped" + IV_SUFFIX, null));
    String keyAfter = NODE.get(KEY_PREF, "");
    assertNotEquals("", keyAfter);
    assertNotEquals(keyBefore, keyAfter);
  }

  @Test
  public void usesARandomInitialisationVectorPerWrite() {
    SecurePreferences prefs = new SecurePreferences();
    prefs.resetSecretKey();

    prefs.savePref("iv", "value");
    String firstCiphertext = NODE.get("iv" + VALUE_SUFFIX, "");
    byte[] firstIv = NODE.getByteArray("iv" + IV_SUFFIX, null);

    prefs.savePref("iv", "value");
    String secondCiphertext = NODE.get("iv" + VALUE_SUFFIX, "");
    byte[] secondIv = NODE.getByteArray("iv" + IV_SUFFIX, null);

    assertEquals(16, firstIv.length);
    assertNotEquals(Base64.getEncoder().encodeToString(firstIv),
        Base64.getEncoder().encodeToString(secondIv));
    assertNotEquals(firstCiphertext, secondCiphertext);
    assertTrue(firstCiphertext.length() > 0);
    assertEquals("value", prefs.loadPref("iv"));
  }
}
