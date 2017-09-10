import java.util.Arrays;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Stream;
import javax.mail.Address;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.NoSuchProviderException;
import javax.mail.Session;
import javax.mail.Store;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Created by François Martin on 10.09.2017.
 */
public class main_Handler {

  private static final Logger LOGGER =
      LogManager.getLogger(main_Handler.class.getName());

  public static void main(String[] args) {
    String host = args[0];
    String username = args[1];
    String password = args[2];
    String provider = "imap";

    Properties prop = new Properties();
    prop.setProperty("mail.imap.ssl.enable", "true");

    moveSpam(host, username, password, provider, prop);
  }

  private static void moveSpam(String host, String username, String password, String provider, Properties prop) {
    LOGGER.trace("Trying to connect to host: " + host + " with user: " + username);
    try {
      //Connect to the server
      Session session = Session.getInstance(prop);
      //session.setDebug(true);
      Store store = session.getStore(provider);
      store.connect(host, username, password);
      LOGGER.trace("Connected!");

      //open the folders
      Folder junk = store.getFolder("Junk");
      Folder inbox = store.getFolder("Inbox");
      junk.open(Folder.READ_WRITE);
      inbox.open(Folder.READ_WRITE);

      boolean successful = moveMessages(junk, inbox, null);
      LOGGER.info("Success: " + successful);

      store.close();
    } catch (NoSuchProviderException nspe) {
      LOGGER.error("NoSuchProviderException: " + nspe.getMessage());
    } catch (MessagingException me) {
      LOGGER.error("MessagingException: " + me.getMessage());
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

    int newToCount = to.getMessageCount();
    if(newToCount != toCount + messages.length) {
      LOGGER.warn("Target folder used to have " + toCount + " messages, now has " + newToCount + " messages but should have " + (toCount + messages.length) + " messages.");
      return false;
    } else {
      int newFromCount = deleteMessages(from, messages);
      if (newFromCount != fromCount - messages.length) {
        LOGGER.error("Source folder used to have " + fromCount + " messages, now has " + newFromCount + " messages but should have " + (fromCount - messages.length) + " messages.");
        return false;
      }
    }

    return true;
  }

  /**
   *
   * @param folder
   * @param messages
   * @return the amount of messages that are now present after deletion.
   * @throws MessagingException
   */
  private static int deleteMessages(Folder folder, Message[] messages) throws MessagingException {
    for (Message message : messages) {
      message.setFlag(Flags.Flag.DELETED, true);
    }
    return folder.expunge().length;
  }

}
