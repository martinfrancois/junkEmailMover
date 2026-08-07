# junkEmailMover

Moves messages out of an IMAP `Junk` folder back into `Inbox`, and optionally forwards a copy of
each one to another address with `[SPAM] ` prepended to the subject.

The use case is a mailbox whose server-side spam filter is too aggressive, or one where you want a
second pair of eyes on what the filter caught. Nothing is deleted from `Junk` unless the copy into
`Inbox` and the forward both succeeded.

## Requirements

- Java 8 or newer at runtime.
- An IMAP account reachable over `imaps`, and an SMTP host that accepts STARTTLS.
- The mailbox must have folders literally named `Junk` and `Inbox`.

## Build

```bash
mvn -B verify
```

That runs the tests and enforces the coverage gate. The build produces a self-contained jar with all
dependencies shaded in, replacing the plain artifact:

```
target/junkEmailMover-1.0-SNAPSHOT.jar
```

## Usage

Arguments are supplied in groups of three, one group per account:

```
<imap-host> <smtp-host> <username>
```

An optional final argument, on its own, is the address to forward to:

```bash
# one account, no forwarding
java -jar junkEmailMover.jar imap.example.com smtp.example.com alice@example.com

# one account, forwarding a copy to another address
java -jar junkEmailMover.jar imap.example.com smtp.example.com alice@example.com me@example.net

# two accounts, both forwarding to the same address
java -jar junkEmailMover.jar \
  imap.example.com smtp.example.com alice@example.com \
  imap.example.org smtp.example.org bob@example.org \
  me@example.net
```

Omit the recipient and messages are moved to `Inbox` without being forwarded.

## Passwords

On first run for a given username the program prompts on standard input and stores the password
encrypted in the Java preferences store, so later runs are non-interactive. This makes it suitable
for a cron job or a scheduled task after one manual run per account.

Run with **no arguments** to clear every stored password:

```bash
java -jar junkEmailMover.jar
```

## What it does, in order

1. Connects to IMAP over `imaps` and to SMTP with STARTTLS.
2. Opens `Junk` and `Inbox`.
3. For each message in `Junk`:
   - copies it into `Inbox`
   - if a recipient was given, forwards it with the subject prefixed `[SPAM] `, preserving the
     original sender in `From`, the original `Reply-To`, and the sent date, with the original
     message attached as a MIME part
   - marks it `DELETED` in `Junk` only once both steps have succeeded
4. Expunges `Junk` and closes the store.

If a copy or a forward fails, the message is left in `Junk` and the run reports failure. The program
is written so that a partial failure never loses mail.

## Logging

Log4j 2 writes three streams, configured in `src/main/resources/log4j2.xml`:

| Logger | Contents |
|---|---|
| root | run progress and outcomes |
| `Emails` | one line per message handled |
| `Exception` | full stack traces, kept out of the main log |

Nothing logs passwords or message bodies.

## Development

```bash
mvn -B verify        # tests plus the 80% coverage gate
```

Tests run against real IMAP and SMTP servers provided by GreenMail on loopback, rather than against
mocks, so a dependency upgrade that changes protocol behaviour fails the build rather than shipping.

Dependencies are managed by Renovate. Non-major updates are grouped into a single pull request that
merges automatically once CI passes; major updates arrive separately and require review.
