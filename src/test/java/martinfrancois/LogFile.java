package martinfrancois;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

/**
 * Reads back what log4j2.xml actually wrote to disk.
 *
 * <p>The appender layout, the file names and the routing of the "Exception" logger all live in
 * {@code src/main/resources/log4j2.xml}. Asserting on the files rather than on a captured
 * appender means a Log4j upgrade that can no longer read that configuration fails the build
 * instead of silently logging nowhere.
 */
final class LogFile {

  /** Relative to the surefire working directory, which the build points at {@code target/}. */
  static final String EVENTS = "logs/junkMoverEvents.log";

  static final String STACK_TRACES = "logs/junkMoverStackTrace.log";

  private final File file;
  private final long offset;

  private LogFile(File file, long offset) {
    this.file = file;
    this.offset = offset;
  }

  /**
   * Marks the current end of the given log file. Everything appended after this point is returned
   * by {@link #appended()}, so assertions never accidentally pass on output left behind by an
   * earlier test or an earlier build.
   */
  static LogFile mark(String path) {
    File file = new File(path);
    return new LogFile(file, file.isFile() ? file.length() : 0L);
  }

  String appended() throws IOException {
    if (!file.isFile()) {
      return "";
    }
    byte[] all = Files.readAllBytes(file.toPath());
    int start = (int) Math.min(offset, all.length);
    return new String(all, start, all.length - start, Charset.defaultCharset());
  }
}
