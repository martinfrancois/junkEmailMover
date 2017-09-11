import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Created by François Martin on 11.09.2017.
 */
public class SecurePreferencesTest {
  String dataToEncrypt = "Hello World";
  String key = "key";

  @Test
  public void test() {
    SecurePreferences pref = new SecurePreferences();
    pref.resetSecretKey();
    pref.savePref(key, dataToEncrypt);
    assertEquals(dataToEncrypt, pref.loadPref(key));
  }
}

