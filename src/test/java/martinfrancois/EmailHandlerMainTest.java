package martinfrancois;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@link EmailHandler#main(String[])} end to end.
 *
 * <p>The hosts handed to main point at loopback, where nothing answers on the IMAPS port, so every
 * run gets as far as a real Jakarta Mail {@code imaps} connection attempt and then fails. That
 * is deliberate: the assertions are made on what the failure produced, which means Guava's
 * {@code Throwables.getStackTraceAsString} and the Log4j configuration in
 * {@code src/main/resources/log4j2.xml} both have to still work for the test to pass.
 */
public class EmailHandlerMainTest {

  private static final String UNREACHABLE_HOST = "127.0.0.1";
  private static final String VALUE_SUFFIX = "_VALUE";

  private static final Preferences NODE =
      Preferences.userNodeForPackage(SecurePreferences.class);

  private InputStream originalIn;
  private PrintStream originalOut;
  private ByteArrayOutputStream captured;

  @Before
  public void captureConsole() {
    originalIn = System.in;
    originalOut = System.out;
    captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true));
  }

  @After
  public void restoreConsole() throws BackingStoreException {
    System.setIn(originalIn);
    System.setOut(originalOut);
    NODE.clear();
  }

  private void answerPasswordPrompts(int times) {
    StringBuilder answers = new StringBuilder();
    for (int i = 0; i < times; i++) {
      answers.append("s3cret").append(System.lineSeparator());
    }
    System.setIn(new ByteArrayInputStream(answers.toString().getBytes()));
  }

  private String console() throws UnsupportedEncodingException {
    return captured.toString("UTF-8");
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
      count++;
    }
    return count;
  }

  @Test
  public void rejectsAnArgumentCountThatIsNotAWholeNumberOfAccounts() throws IOException {
    LogFile events = LogFile.mark(LogFile.EVENTS);

    EmailHandler.main(new String[] {"imap.example.com", "smtp.example.com"});

    String log = events.appended();
    assertTrue(log, log.contains("Incorrect number of arguments found (2)"));
    // No account was processed, so no connection was attempted.
    assertFalse(log, log.contains("Trying to connect to host"));
  }

  @Test
  public void treatsEveryThreeArgumentsAsOneAccount() throws IOException {
    // main builds a fresh Scanner per account and the first one buffers ahead, so two
    // prompts cannot be answered from a single stream. Seed both passwords first.
    answerPasswordPrompts(1);
    EmailHandler.main(new String[] {UNREACHABLE_HOST, UNREACHABLE_HOST, "first@example.com"});
    answerPasswordPrompts(1);
    EmailHandler.main(new String[] {UNREACHABLE_HOST, UNREACHABLE_HOST, "second@example.com"});

    LogFile events = LogFile.mark(LogFile.EVENTS);
    System.setIn(new ByteArrayInputStream(new byte[0]));
    EmailHandler.main(new String[] {
        UNREACHABLE_HOST, UNREACHABLE_HOST, "first@example.com",
        UNREACHABLE_HOST, UNREACHABLE_HOST, "second@example.com"});

    String log = events.appended();
    assertTrue(log, log.contains("with user: first@example.com"));
    assertTrue(log, log.contains("with user: second@example.com"));
  }

  @Test
  public void treatsATrailingArgumentAsTheForwardingRecipient() throws IOException {
    answerPasswordPrompts(1);
    LogFile events = LogFile.mark(LogFile.EVENTS);

    EmailHandler.main(new String[] {
        UNREACHABLE_HOST, UNREACHABLE_HOST, "fwd@example.com", "postmaster@example.com"});

    String log = events.appended();
    // Four arguments is one account plus a recipient, not an argument count error.
    assertTrue(log, log.contains("with user: fwd@example.com"));
    assertFalse(log, log.contains("Incorrect number of arguments"));
  }

  @Test
  public void promptsOnlyOnceAndReusesTheStoredPassword() throws IOException {
    answerPasswordPrompts(1);
    String[] args = {UNREACHABLE_HOST, UNREACHABLE_HOST, "reuse@example.com"};

    EmailHandler.main(args);
    // The second run gets an empty stdin: had it prompted again, Scanner would have thrown.
    System.setIn(new ByteArrayInputStream(new byte[0]));
    EmailHandler.main(args);

    assertEquals(console(), 1,
        countOf(console(), "Please enter password for user: reuse@example.com"));
    assertNotEquals("", NODE.get("reuse@example.com" + VALUE_SUFFIX, ""));
  }

  @Test
  public void withoutArgumentsClearsEveryStoredCredential() throws IOException {
    answerPasswordPrompts(1);
    EmailHandler.main(new String[] {UNREACHABLE_HOST, UNREACHABLE_HOST, "wipe@example.com"});
    assertNotEquals("", NODE.get("wipe@example.com" + VALUE_SUFFIX, ""));

    LogFile events = LogFile.mark(LogFile.EVENTS);
    EmailHandler.main(new String[0]);

    assertEquals("absent", NODE.get("wipe@example.com" + VALUE_SUFFIX, "absent"));
    assertTrue(events.appended(), events.appended().contains("Preferences cleared"));
  }

  @Test
  public void logsTheFullStackTraceOfAFailedConnection() throws IOException {
    answerPasswordPrompts(1);
    LogFile stackTraces = LogFile.mark(LogFile.STACK_TRACES);

    EmailHandler.main(new String[] {UNREACHABLE_HOST, UNREACHABLE_HOST, "trace@example.com"});

    String trace = stackTraces.appended();
    // Produced by Guava's Throwables and routed by the "Exception" logger in log4j2.xml.
    assertTrue(trace, trace.contains("org.eclipse.angus.mail.util.MailConnectException"));
    assertTrue(trace, trace.contains("java.net.ConnectException"));
    assertTrue(trace, trace.contains("org.eclipse.angus.mail.imap.IMAPStore.protocolConnect"));
    assertTrue(trace, trace.contains("martinfrancois.EmailHandler.moveSpam"));
  }
}
