package martinfrancois;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetup;
import com.sun.mail.imap.IMAPFolder;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Runs the mailbox handling against real IMAP and SMTP servers.
 *
 * <p>GreenMail speaks the actual protocols over a socket, so every assertion here goes through
 * the {@code com.sun.mail} implementation: folder open, APPEND, COPY, the DELETED flag, EXPUNGE,
 * MIME multipart assembly and SMTP delivery. Mocking JavaMail would make these tests pass no
 * matter which version of JavaMail is on the classpath, which is exactly what they are meant to
 * detect.
 */
public class EmailHandlerMailboxTest {

  private static final String BIND_ADDRESS = "127.0.0.1";
  private static final String PASSWORD = "secret";
  // EmailHandler authenticates SMTP with the account's own address, so the GreenMail
  // login has to be the address rather than a separate user name.
  private static final String MAILBOX = "junk@localhost";
  private static final String RECIPIENT = "postmaster@localhost";
  private static final String JUNK = "Junk";
  private static final String INBOX = "Inbox";

  private GreenMail greenMail;
  private Store store;
  private Properties smtpProperties;
  private Session smtpSession;

  @Before
  public void startMailServers() throws Exception {
    greenMail = new GreenMail(new ServerSetup[] {
        new ServerSetup(0, BIND_ADDRESS, ServerSetup.PROTOCOL_IMAP),
        new ServerSetup(0, BIND_ADDRESS, ServerSetup.PROTOCOL_SMTP)});
    greenMail.start();
    greenMail.setUser(MAILBOX, PASSWORD);
    greenMail.setUser(RECIPIENT, PASSWORD);

    int imapPort = greenMail.getImap().getPort();
    assertTrue("GreenMail did not pick a dynamic IMAP port", imapPort > 0);
    Properties imapProperties = new Properties();
    imapProperties.setProperty("mail.imap.host", BIND_ADDRESS);
    imapProperties.setProperty("mail.imap.port", Integer.toString(imapPort));
    store = Session.getInstance(imapProperties).getStore("imap");
    store.connect(BIND_ADDRESS, imapPort, MAILBOX, PASSWORD);

    // Same properties EmailHandler.connect() builds for SMTP, plus the port GreenMail
    // happened to pick and a relaxed trust setting for its self signed STARTTLS certificate.
    smtpProperties = new Properties();
    smtpProperties.put("mail.smtp.starttls.enable", "true");
    smtpProperties.put("mail.smtp.ssl.trust", "*");
    smtpProperties.put("mail.smtp.host", BIND_ADDRESS);
    smtpProperties.put("mail.smtp.port", Integer.toString(greenMail.getSmtp().getPort()));
    smtpSession = Session.getInstance(smtpProperties);

    Folder junk = store.getFolder(JUNK);
    if (!junk.exists()) {
      assertTrue("could not create the Junk folder", junk.create(Folder.HOLDS_MESSAGES));
    }
  }

  @After
  public void stopMailServers() {
    try {
      if (store != null && store.isConnected()) {
        store.close();
      }
    } catch (MessagingException ignored) {
      // the servers are going away anyway
    }
    if (greenMail != null) {
      greenMail.stop();
    }
  }

  private EmailHandler.Settings settings(String recipient) {
    EmailHandler.Connection imap = new EmailHandler.Connection(BIND_ADDRESS, MAILBOX, PASSWORD);
    imap.store = store;
    EmailHandler.Connection smtp = new EmailHandler.Connection(BIND_ADDRESS, MAILBOX, PASSWORD);
    smtp.prop = smtpProperties;
    smtp.session = smtpSession;
    return new EmailHandler.Settings(imap, smtp, recipient);
  }

  private void fillJunk(int count) throws MessagingException {
    Folder junk = store.getFolder(JUNK);
    junk.open(Folder.READ_WRITE);
    try {
      Message[] messages = new Message[count];
      for (int i = 0; i < count; i++) {
        MimeMessage message = new MimeMessage(smtpSession);
        message.setFrom(new InternetAddress("spammer" + i + "@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress(MAILBOX));
        message.setSubject("Cheap pills " + i);
        message.setSentDate(new Date());
        message.setText("unsolicited body " + i);
        message.saveChanges();
        messages[i] = message;
      }
      junk.appendMessages(messages);
    } finally {
      junk.close(false);
    }
  }

  private static List<String> subjectsOf(Folder folder) throws MessagingException {
    List<String> subjects = new ArrayList<String>();
    for (Message message : folder.getMessages()) {
      subjects.add(message.getSubject());
    }
    return subjects;
  }

  private List<MimeMessage> forwardedMessages() throws MessagingException {
    List<MimeMessage> forwarded = new ArrayList<MimeMessage>();
    for (MimeMessage message : greenMail.getReceivedMessages()) {
      if (message.getSubject() != null && message.getSubject().startsWith("[SPAM] ")) {
        forwarded.add(message);
      }
    }
    return forwarded;
  }

  @Test
  public void opensAnExistingFolderReadWrite() throws Exception {
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);

    assertNotNull(junk);
    assertTrue(junk.isOpen());
    assertEquals(Folder.READ_WRITE, junk.getMode());
  }

  @Test
  public void movesEveryJunkMessageIntoTheInbox() throws Exception {
    fillJunk(3);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    IMAPFolder inbox = EmailHandler.getFolder(store, INBOX);
    int inboxBefore = inbox.getMessageCount();

    assertTrue(EmailHandler.moveMessages(junk, inbox, null, settings("")));

    assertEquals(0, junk.getMessageCount());
    assertEquals(inboxBefore + 3, inbox.getMessageCount());
    List<String> delivered = subjectsOf(inbox);
    assertTrue(delivered.toString(), delivered.contains("Cheap pills 0"));
    assertTrue(delivered.toString(), delivered.contains("Cheap pills 2"));
  }

  @Test
  public void movesOnlyTheMessagesItWasGiven() throws Exception {
    fillJunk(3);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    IMAPFolder inbox = EmailHandler.getFolder(store, INBOX);
    Message[] onlyTheFirst = {junk.getMessages()[0]};

    assertTrue(EmailHandler.moveMessages(junk, inbox, onlyTheFirst, settings("")));

    assertEquals(2, junk.getMessageCount());
    assertEquals(1, inbox.getMessageCount());
    assertEquals("Cheap pills 0", inbox.getMessages()[0].getSubject());
    assertFalse(subjectsOf(junk).contains("Cheap pills 0"));
  }

  @Test
  public void forwardsEveryMovedMessageToTheRecipient() throws Exception {
    fillJunk(2);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    IMAPFolder inbox = EmailHandler.getFolder(store, INBOX);

    assertTrue(EmailHandler.moveMessages(junk, inbox, null, settings(RECIPIENT)));

    List<MimeMessage> forwarded = forwardedMessages();
    assertEquals(forwarded.toString(), 2, forwarded.size());
    MimeMessage first = forwarded.get(0);
    assertEquals(MAILBOX, first.getFrom()[0].toString());
    assertEquals(RECIPIENT, first.getRecipients(Message.RecipientType.TO)[0].toString());
    assertTrue(first.getSubject(), first.getSubject().startsWith("[SPAM] Cheap pills "));
    // The original body is carried over as a MIME body part, not dropped.
    assertTrue(GreenMailUtil.getBody(first).contains("unsolicited body"));
    // Forwarding does not stop the move: the junk folder is still emptied.
    assertEquals(0, junk.getMessageCount());
  }

  @Test
  public void reportsAFailureWhenTheRecipientAddressIsUnusable() throws Exception {
    fillJunk(1);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    Message message = junk.getMessages()[0];
    LogFile stackTraces = LogFile.mark(LogFile.STACK_TRACES);

    assertFalse(EmailHandler.forwardMessage(message, settings("this is not an address")));

    String trace = stackTraces.appended();
    assertTrue(trace, trace.contains("javax.mail.internet.AddressException"));
  }

  @Test
  public void keepsTheJunkMessagesWhenForwardingFails() throws Exception {
    fillJunk(2);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    IMAPFolder inbox = EmailHandler.getFolder(store, INBOX);

    assertFalse(EmailHandler.moveMessages(junk, inbox, null, settings("not an address")));

    // The safety property this program lives or dies by: nothing is deleted from the source
    // folder unless every earlier step reported success.
    assertEquals(2, junk.getMessageCount());
    List<String> stillThere = subjectsOf(junk);
    assertTrue(stillThere.toString(), stillThere.contains("Cheap pills 0"));
    assertTrue(stillThere.toString(), stillThere.contains("Cheap pills 1"));
  }

  @Test
  public void reportsFailureWhenADeletionCannotBeConfirmed() throws Exception {
    fillJunk(2);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    IMAPFolder inbox = EmailHandler.getFolder(store, INBOX);
    Message[] messagesFromAnotherFolder = junk.getMessages();

    // deleteMessages documents that the messages have to belong to the folder. Handing it
    // messages from elsewhere means the expunge cannot change the folder's count, so the
    // retry loop has to run out and report a failure rather than claim success.
    long startedAt = System.currentTimeMillis();
    assertFalse(EmailHandler.deleteMessages(inbox, messagesFromAnotherFolder));

    assertTrue("the retry loop should have polled, not returned immediately",
        System.currentTimeMillis() - startedAt >= 5_000L);
    assertEquals(0, inbox.getMessageCount());
  }

  @Test
  public void deletesMessagesFromTheFolderTheyBelongTo() throws Exception {
    fillJunk(2);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    Message[] all = junk.getMessages();

    assertTrue(EmailHandler.deleteMessages(junk, all));

    assertEquals(0, junk.getMessageCount());
  }

  @Test
  public void copyMessagesLeavesTheSourceFolderUntouched() throws Exception {
    fillJunk(2);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);
    IMAPFolder inbox = EmailHandler.getFolder(store, INBOX);

    assertTrue(EmailHandler.copyMessages(junk, inbox, junk.getMessages()));

    assertEquals(2, junk.getMessageCount());
    assertEquals(2, inbox.getMessageCount());
  }

  @Test
  public void checkAmountAgreesWithTheServerCount() throws Exception {
    fillJunk(2);
    IMAPFolder junk = EmailHandler.getFolder(store, JUNK);

    assertTrue(EmailHandler.checkAmount(junk, 2));
  }

  @Test
  public void listsTheMailboxesOnTheServer() throws Exception {
    PrintStream original = System.out;
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    System.setOut(new PrintStream(captured, true));
    try {
      EmailHandler.printFolderList(store);
    } finally {
      System.setOut(original);
    }

    String listed = captured.toString("UTF-8");
    assertTrue(listed, listed.contains(">> INBOX"));
    assertTrue(listed, listed.contains(">> " + JUNK));
  }
}
