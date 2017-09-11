import com.sun.mail.imap.IMAPFolder;
import java.util.Date;
import java.util.Properties;
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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by François Martin on 10.09.2017.
 */
public class main_Handler {

  private static final Logger LOGGER =
      LogManager.getLogger(main_Handler.class.getName());

  public static final int AMOUNT_ARGUMENTS = 4;

  public static void main(String[] args) {
    if (args.length % AMOUNT_ARGUMENTS != 0) {
      LOGGER.error(
          "Incorrect number of arguments found (" + args.length + "). " +
          "Number of arguments must be a multiple of " + AMOUNT_ARGUMENTS);
    } else {


      int numOfAccounts = args.length / AMOUNT_ARGUMENTS;
      LOGGER.trace(numOfAccounts + " accounts");
      for (int i = 0; i < numOfAccounts; i++) {
        LOGGER.trace("Current account: " + (i+1));

        String hostImap = args[0+(AMOUNT_ARGUMENTS*i)];
        String hostSmtp = args[1+(AMOUNT_ARGUMENTS*i)];
        String username = args[2+(AMOUNT_ARGUMENTS*i)];
        String password = args[3+(AMOUNT_ARGUMENTS*i)];

        Connection imap = new Connection(hostImap, username, password);
        Connection smtp = new Connection(hostSmtp, username, password);

        moveSpam(imap, smtp);
      }
    }

  }

  private static class Connection {
    String host;
    String username;
    String password;
    Properties prop;
    Session session;
    Store store;

    public Connection(String host, String username, String password){
      this.host = host;
      this.username = username;
      this.password = password;
    }
  }

  private static void moveSpam(Connection imap, Connection smtp) {
    LOGGER.info("Trying to connect to host: " + host + " with user: " + username);
    try {
      Properties propImap = new Properties();
      propImap.setProperty("mail.imap.ssl.enable", "true");
      Session sessionImap = Session.getInstance(propImap);
      Store storeImap = sessionImap.getStore("imap");
      storeImap.connect(host, username, password);
      LOGGER.info("IMAP connected");

      Properties propSmtp = new Properties();
      propSmtp.put("mail.smtp.starttls.enable", "true");
      propSmtp.put("mail.smtp.host", smtp);
      Session sessionSmtp = Session.getInstance(propSmtp);

      //open the folders
      IMAPFolder junk = (IMAPFolder) storeImap.getFolder("Junk");
      IMAPFolder inbox = (IMAPFolder) storeImap.getFolder("Inbox");
      junk.open(Folder.READ_WRITE);
      inbox.open(Folder.READ_WRITE);
      LOGGER.trace("Folders opened");

      boolean successful = moveMessages(junk, inbox, null, sessionSmtp, username, password);
      LOGGER.info("Success: " + successful);

      storeImap.close();
    } catch (NoSuchProviderException nspe) {
      LOGGER.error("NoSuchProviderException: " + nspe.toString());
    } catch (MessagingException me) {
      LOGGER.error("MessagingException: " + me.toString());
    }
  }

  private static void printFolderList(Store store) throws MessagingException {
    System.out.println(store);

    Folder[] f = store.getDefaultFolder().list();
    for(Folder fd:f)
      System.out.println(">> "+fd.getName());
  }

  /**
   * Folders must be open already!
   * Messages needs to belong to the "from" folder. If it is null, all messages will be used.
   * Returns true if successful, false if unsuccessful.
   */
  private static boolean moveMessages(Folder from, Folder to, Message[] messages, Session session, String username, String password) throws MessagingException {
    // get counts before the operations
    int fromCount = from.getMessageCount();
    int toCount = to.getMessageCount();
    LOGGER.trace("BEFORE - from: " + fromCount + " to: " + toCount);

    // get a list of javamail messages as an array of messages
    if(messages == null) {
      LOGGER.trace("messages is null, moving all messages");
      // get all messages
      messages = from.getMessages();
    }

    // forward messages
    for (Message message : messages) {
      forwardEmail(message, session, username, password);
    }

    // copy the messages to the other folder
    from.copyMessages(messages, to);
    LOGGER.trace("Copied messages");

    // check if the messages have been successfully copied over to the target folder
    if(!checkAmount(to, toCount + messages.length)) {
      // copy was not successful, abort
      LOGGER.warn("Target folder used to have " + toCount + " messages, now has " + to.getMessageCount() + " messages but should have " + (toCount + messages.length) + " messages.");
      return false;
    } else {
      // copy was successful, delete from source folder
      LOGGER.trace("Messages were copied successfully");
      if(deleteMessages(from, messages)) {
        LOGGER.trace("Messages were deleted successfully");
        return true;
      }
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
    } while(attempt < threshold && (actual != expected));
    return attempt != threshold;
  }

  /**
   *
   * @param folder
   * @param messages
   * @return true if successful
   * @throws MessagingException
   */
  private static boolean deleteMessages(Folder folder, Message[] messages) throws MessagingException {
    int folderCount = folder.getMessageCount();

    // flag all messages for deletion
    for (Message message : messages) {
      message.setFlag(Flags.Flag.DELETED, true);
    }

    // delete messages
    folder.expunge();

    // check if deletion was successful
    if (!checkAmount(folder, folderCount - messages.length)) {
      // deletion was not successful
      LOGGER.error("Source folder used to have " + folderCount + " messages, now has " + folder.getMessageCount() + " messages but should have " + (folderCount - messages.length) + " messages.");
      return false;
    }
    return true;
  }

  private static void forwardEmail(Message message, Session session, String username, String password) throws MessagingException {
    // Get all the information from the message
    String from = username;
    String subject = message.getSubject();
    Date sent = message.getSentDate();
    LOGGER.trace("From: " + from + ", Subject: " + subject + ", Date: " + sent);

    // compose the message to forward
    Message message2 = new MimeMessage(session);
    message2.setSubject("[SPAM] " + subject);
    message2.setFrom(new InternetAddress(from));
    message2.addRecipient(Message.RecipientType.TO,
        new InternetAddress("***REMOVED***"));
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
    Transport.send(message2, username, password);
  }


}
