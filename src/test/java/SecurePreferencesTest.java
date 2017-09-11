import static org.junit.Assert.assertEquals;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
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
    pref.saveSecretKey();
    pref.savePref(key, dataToEncrypt);
    assertEquals(dataToEncrypt, pref.loadPref(key));
  }
}

