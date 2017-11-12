package martinfrancois;

import com.google.common.base.Throwables;
import com.sun.mail.imap.IMAPFolder;
import java.util.Date;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by François Martin on 10.09.2017.
 */
public class EmailHandler {

  public static final int AMOUNT_ARGUMENTS = 3;
  private static final Logger LOGGER =
      LogManager.getLogger(EmailHandler.class.getName());
  private static final Logger LOGGER_EMAILS = LogManager.getLogger("Emails");
  private static final Logger LOGGER_EXCEPTION = LogManager.getLogger("Exception");
  private static final SecurePreferences pref = new SecurePreferences();

  public static void main(String[] args) {
    int numOfAccounts = 0;
    String recipient = "";

    // if no input is given - clear preferences
    if (args.length == 0) {
      pref.resetPrefs();
      LOGGER.info("Preferences cleared");
    }

    if (args.length % AMOUNT_ARGUMENTS == 0) {
      // without recipient
      numOfAccounts = args.length / AMOUNT_ARGUMENTS;
    } else if ((args.length - 1) % AMOUNT_ARGUMENTS == 0) {
      // with recipient
      numOfAccounts = (args.length - 1) / AMOUNT_ARGUMENTS;
      recipient = args[args.length - 1];
    } else {
      // incorrect
      LOGGER.error("Incorrect number of arguments found (" + args.length + ").");
    }

    if (numOfAccounts > 0) {
      LOGGER.trace(numOfAccounts + " accounts");
      for (int i = 0; i < numOfAccounts; i++) {
        LOGGER.trace("Current account: " + (i + 1));

        String hostImap = args[0 + (AMOUNT_ARGUMENTS * i)];
        String hostSmtp = args[1 + (AMOUNT_ARGUMENTS * i)];
        String username = args[2 + (AMOUNT_ARGUMENTS * i)];
        String password = pref.loadPref(username);
        if (password.length() == 0) {
          LOGGER.trace("Password not found");
          System.out.println("Please enter password for user: " + username);
          Scanner in = new Scanner(System.in);
          password = in.nextLine().trim();
          pref.savePref(username, password);
          LOGGER.trace("Password saved");
        } else{
          LOGGER.trace("Password found");
        }

        Connection imap = new Connection(hostImap, username, password);
        Connection smtp = new Connection(hostSmtp, username, password);

        Settings settings = new Settings(imap, smtp, recipient);

        moveSpam(settings);
      }
    }

  }

  private static void moveSpam(Settings settings) {
    LOGGER.info("Trying to connect to host: " + settings.imap.host + " with user: " + settings.imap.username);
    try {
      connect(settings.imap, settings.smtp);

      //open the folders
      IMAPFolder junk = getFolder(settings.imap.store, "Junk");
      IMAPFolder inbox = getFolder(settings.imap.store, "Inbox");
      LOGGER.trace("Folders opened");

      boolean successful = moveMessages(junk, inbox, null, settings);
      LOGGER.info("Success: " + successful);

      settings.imap.store.close();
    } catch (NoSuchProviderException nspe) {
      LOGGER.error("NoSuchProviderException: " + nspe.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(nspe));
    } catch (MessagingException me) {
      LOGGER.error("MessagingException: " + me.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(me));
    }
  }

  private static IMAPFolder getFolder(Store store, String folderName) throws MessagingException {
    IMAPFolder folder = (IMAPFolder) store.getFolder(folderName);
    if (!folder.isOpen()) {
      folder.open(Folder.READ_WRITE);
    }
    return folder;
  }

  private static void connect(Connection imap, Connection smtp) throws MessagingException {
    // connect IMAP
    imap.prop = new Properties();
    imap.session = Session.getInstance(imap.prop);
    if (LOGGER.getLevel().equals(Level.TRACE)) {
      imap.session.setDebug(true);
    }
    imap.store = imap.session.getStore("imaps");
    imap.store.connect(imap.host, imap.username, imap.password);
    LOGGER.info("IMAP connected");

    // connect SMTP
    smtp.prop = new Properties();
    smtp.prop.put("mail.smtp.starttls.enable", "true");
    smtp.prop.put("mail.smtp.host", smtp.host);
    smtp.session = Session.getInstance(smtp.prop);
    if (LOGGER.getLevel().equals(Level.TRACE)) {
      smtp.session.setDebug(true);
    }
  }

  private static void printFolderList(Store store) throws MessagingException {
    System.out.println(store);

    Folder[] f = store.getDefaultFolder().list();
    for (Folder fd : f) {
      System.out.println(">> " + fd.getName());
    }
  }

  /**
   * Folders must be open already!
   * Messages needs to belong to the "from" folder. If it is null, all messages will be used.
   * Returns true if successful, false if unsuccessful.
   */
  private static boolean moveMessages(Folder from, Folder to, Message[] messages, Settings settings) throws MessagingException {
    // get a list of javamail messages as an array of messages
    if (messages == null) {
      LOGGER.trace("messages is null, copying all messages");
      // get all messages
      messages = from.getMessages();
    }
    LOGGER.info("Moving " + messages.length + " messages...");

    if (copyMessages(from, to, messages)) {
      boolean success = true;
      if (settings.recipient.length() > 0) {
        for (Message message : messages) {
          if (success && !forwardMessage(message, settings)) {
            success = false;
          }
        }
      }
      if (success) {
        return deleteMessages(from, messages);
      }
    }
    return false;
  }

  /**
   * Folders must be open already!
   * Messages needs to belong to the "from" folder. If it is null, all messages will be used.
   * Returns true if successful, false if unsuccessful.
   */
  private static boolean copyMessages(Folder from, Folder to, Message[] messages) throws MessagingException {
    // get counts before the operations
    int fromCount = from.getMessageCount();
    int toCount = to.getMessageCount();
    LOGGER.trace("BEFORE - from: " + fromCount + " to: " + toCount);

    // copy the messages to the other folder
    from.copyMessages(messages, to);
    LOGGER.trace("Copied messages");

    // check if the messages have been successfully copied over to the target folder
    if (checkAmount(to, toCount + messages.length)) {
      // copy was successful, delete from source folder
      LOGGER.trace("AFTER - from: " + from.getMessageCount() + " to: " + to.getMessageCount());
      LOGGER.trace("Messages were copied successfully");
      return true;
    } else {
      // copy was not successful, abort
      LOGGER.warn("Target folder used to have " + toCount + " messages, now has " + to.getMessageCount() + " messages but should have " + (toCount + messages.length) + " messages.");
    }
    return false;
  }

  private static boolean checkAmount(Folder folder, int expected) throws MessagingException {
    int threshold = 20;
    int attempt = 0;
    int actual = -1;
    do {
      attempt++;
      actual = folder.getMessageCount();
      LOGGER.trace("Attempt: " + attempt + ", Expected messages: " + expected + " Actual messages: " + actual);
      try {
        TimeUnit.SECONDS.sleep(1);
      } catch (InterruptedException e) {
        // interrupted
      }
    } while (attempt < threshold && (actual != expected));
    return attempt != threshold;
  }

  /**
   * @param folder
   * @param messages
   * @return true if successful
   * @throws MessagingException
   */
  private static boolean deleteMessages(Folder folder, Message[] messages) throws MessagingException {
    LOGGER.trace("Deleting messages...");

    int folderCount = folder.getMessageCount();

    // flag all messages for deletion
    for (Message message : messages) {
      message.setFlag(Flags.Flag.DELETED, true);
    }

    // delete messages
    folder.expunge();

    // check if deletion was successful
    if (checkAmount(folder, folderCount - messages.length)) {
      LOGGER.trace("Deletion successful");
      return true;
    }
    // deletion was not successful
    LOGGER.error("Source folder used to have " + folderCount + " messages, now has " + folder.getMessageCount() + " messages but should have " + (folderCount - messages.length) + " messages.");
    return false;
  }

  private static boolean forwardMessage(Message message, Settings settings) {
    LOGGER.trace("Forwarding Messages...");
    try {
      // Get all the information from the message
      String from = settings.smtp.username;
      String subject = message.getSubject();
      Date sent = message.getSentDate();
      LOGGER.trace("From: " + message.getFrom() + ", Subject: " + subject + ", Date: " + sent);
      LOGGER_EMAILS.info(message.getFrom().toString()); // log all email addresses to a file

      // compose the message to forward
      Message message2 = new MimeMessage(settings.smtp.session);
      message2.setSubject(settings.SUBJECT_PREFIX + subject);
      message2.setFrom(new InternetAddress(from));
      message2.addRecipient(Message.RecipientType.TO,
          new InternetAddress(settings.recipient));
      message2.setSentDate(sent);
      message2.setReplyTo(message.getReplyTo());

      // Create your new message part
      BodyPart messageBodyPart = new MimeBodyPart();
      messageBodyPart.setDataHandler(message.getDataHandler());

      // Create a multi-part to combine the parts
      Multipart multipart = new MimeMultipart();
      multipart.addBodyPart(messageBodyPart);

      // Associate multi-part with message
      message2.setContent(multipart);

      // Send message
      Transport.send(message2, settings.smtp.username, settings.smtp.password);
    } catch (MessagingException e) {
      LOGGER.error("MessagingException: " + e.toString());
      LOGGER_EXCEPTION.debug(Throwables.getStackTraceAsString(e));
      return false;
    }
    LOGGER.trace("Message successfully forwarded");
    return true;
  }

  private static class Connection {
    String host;
    String username;
    String password;
    Properties prop;
    Session session;
    Store store;

    public Connection(String host, String username, String password) {
      this.host = host;
      this.username = username;
      this.password = password;
    }
  }

  private static class Settings {
    private static final String SUBJECT_PREFIX = "[SPAM] ";
    Connection imap;
    Connection smtp;
    IMAPFolder from;
    IMAPFolder to;
    String recipient;

    public Settings(Connection imap, Connection smtp, String recipient) {
      this.imap = imap;
      this.smtp = smtp;
      this.recipient = recipient;
    }
  }


}
