import com.sun.mail.imap.IMAPFolder;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.event.MessageCountAdapter;
import javax.mail.event.MessageCountEvent;
import javax.mail.event.MessageCountListener;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by François Martin on 10.09.2017.
 */
public class main_Handler {

  private static final Logger LOGGER =
      LogManager.getLogger(main_Handler.class.getName());

  public static void main(String[] args) {
    if (args.length % 3 != 0) {
      LOGGER.error(
          "Incorrect number of arguments found (" + args.length + "). " +
          "Please use a combination of host, username and password, separated by a space character. " +
          "Number of arguments must be a multiple of 3");
    } else {
      String host;
      String username;
      String password;
      String provider = "imap";

      int numOfAccounts = args.length / 3;
      LOGGER.trace(numOfAccounts + " accounts");
      for (int i = 0; i < numOfAccounts; i++) {
        LOGGER.trace("Current account: " + (i+1));
        host = args[0+(3*i)];
        username = args[1+(3*i)];
        password = args[2+(3*i)];
        Properties prop = new Properties();
        prop.setProperty("mail.imap.ssl.enable", "true");
        moveSpam(host, username, password, provider, prop);
      }
    }

  }

  private static void moveSpam(String host, String username, String password, String provider, Properties prop) {
    LOGGER.info("Trying to connect to host: " + host + " with user: " + username);
    try {
      //Connect to the server
      Session session = Session.getInstance(prop);
      //session.setDebug(true);
      Store store = session.getStore(provider);
      store.connect(host, username, password);
      LOGGER.trace("Connected!");

      //open the folders
      IMAPFolder junk = (IMAPFolder) store.getFolder("Junk");
      IMAPFolder inbox = (IMAPFolder) store.getFolder("Inbox");
      junk.open(Folder.READ_WRITE);
      inbox.open(Folder.READ_WRITE);
      LOGGER.trace("Folders opened");

      boolean successful = moveMessages(junk, inbox, null);
      LOGGER.info("Success: " + successful);

      store.close();
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
  private static boolean moveMessages(Folder from, Folder to, Message[] messages) throws MessagingException {
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

}
