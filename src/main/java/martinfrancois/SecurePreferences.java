package martinfrancois;

import com.google.common.base.Throwables;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.BASE64Encoder;

/**
 * Created by François Martin on 11.09.2017.
 */
public class SecurePreferences {

  private static final String VALUE_SUFFIX = "_VALUE";
  private static final String IV_SUFFIX = "_IV";
  private static final Logger LOGGER =
      LogManager.getLogger(SecurePreferences.class.getName());
  private static final Logger LOGGER_EXCEPTION = LogManager.getLogger("Exception");
  private static final int AES_KEYLENGTH = 128;    // change this as desired for the security level you want
  private static final String PREF_KEY = "secretKey";
  private static final Preferences prefs = Preferences.userNodeForPackage(SecurePreferences.class);
  private SecretKey secretKey = null;
  private String encrypted = "";
  private byte[] iv = null;

  public String loadPref(String key) {
    loadSecretKey();
    encrypted = prefs.get(key + VALUE_SUFFIX, "");
    if (encrypted.length() == 0) {
      return "";
    }
    iv = prefs.getByteArray(key + IV_SUFFIX, null);
    return decrypt(secretKey, encrypted, iv);
  }

  public void savePref(String key, String value) {
    loadSecretKey();
    encrypt(secretKey, value);
    prefs.put(key + VALUE_SUFFIX, encrypted);
    prefs.putByteArray(key + IV_SUFFIX, iv);
  }

  public void resetPrefs(){
    try {
      prefs.clear();
      resetSecretKey();
    } catch (BackingStoreException e) {
      LOGGER.error("Error while trying to clear preferences: " + e.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(e));
    }

  }

  public void resetSecretKey() {
    SecretKey secretKey = generateSecretKey();
    String encodedKey = Base64.getEncoder().encodeToString(secretKey.getEncoded());
    prefs.put(PREF_KEY, encodedKey);
  }

  private void loadSecretKey() {
    if (secretKey == null) {
      String encodedKey = prefs.get(PREF_KEY, "");
      byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
      // rebuild key using SecretKeySpec
      try {
        secretKey = new SecretKeySpec(decodedKey, 0, decodedKey.length, "AES");
      } catch (IllegalArgumentException e) {
        resetSecretKey();
        loadSecretKey();
      }
    }
  }

  private SecretKey generateSecretKey() {
    KeyGenerator keyGen = null;
    try {
      keyGen = KeyGenerator.getInstance("AES");
    } catch (NoSuchAlgorithmException e) {
      LOGGER.error("Error during generation of key! " + e.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(e));
    }
    keyGen.init(AES_KEYLENGTH);
    return keyGen.generateKey();
  }

  private void encrypt(SecretKey secretKey, String toEncrypt) {
    try {
      iv = new byte[AES_KEYLENGTH / 8];    // Save the IV bytes or send it in plaintext with the encrypted data so you can decrypt the data later
      SecureRandom prng = new SecureRandom();
      prng.nextBytes(iv);
      Cipher aesCipherForEncryption = Cipher.getInstance("AES/CBC/PKCS5PADDING"); // Must specify the mode explicitly as most JCE providers default to ECB mode!!
      aesCipherForEncryption.init(Cipher.ENCRYPT_MODE, secretKey,
          new IvParameterSpec(iv));
      byte[] byteDataToEncrypt = toEncrypt.getBytes();
      byte[] byteCipherText = new byte[0];
      byteCipherText = aesCipherForEncryption
          .doFinal(byteDataToEncrypt);
      encrypted = new BASE64Encoder().encode(byteCipherText);
    } catch (IllegalBlockSizeException | BadPaddingException | InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException | NoSuchAlgorithmException e) {
      LOGGER.error("Error during encryption: " + e.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(e));
    }
  }

  private String decrypt(SecretKey secretKey, String toDecrypt, byte[] iv) {
    try {
      Cipher aesCipherForDecryption = Cipher.getInstance("AES/CBC/PKCS5PADDING"); // Must specify the mode explicitly as most JCE providers default to ECB mode!!
      aesCipherForDecryption.init(Cipher.DECRYPT_MODE, secretKey,
          new IvParameterSpec(iv));
      byte[] byteDecryptedText = aesCipherForDecryption
          .doFinal(Base64.getDecoder().decode(toDecrypt));
      return new String(byteDecryptedText);
    } catch (IllegalBlockSizeException | BadPaddingException | InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException | NoSuchAlgorithmException e) {
      LOGGER.error("Error during decryption: " + e.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(e));
    }
    return null;
  }

}
