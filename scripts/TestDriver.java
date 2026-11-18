import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


/**
 * Runs the four step-1 test cases against the agent process: accept, decline then list_visits, duplicate date, slow answer. It types into the agent like a user and waits for
 * the "> " prompt or the CONFIRM prompt. Usage: {@code java TestDriver.java <slowSeconds> <agent command...>}. Single-file source launch, so no build step.
 */
public class TestDriver {
    private static final String        CONFIRM = "Type 'yes' to book, anything else cancels: ";
    private static final int           PROMPT = 1, CONFIRMING = 2, ENDED = 3, TIMEOUT = 4;
    private static final StringBuffer  OUT = new StringBuffer();
    private static final List<String>  RESULTS = new ArrayList<>();
    private static volatile boolean    eof;
    private static Writer              toAgent;
    private static int                 timeoutSeconds = 240;
    private static boolean             failed;


    record Turn(String segment, boolean confirmationAsked, int end) { }


    public static void main(final String[] args) throws Exception {
        int          slow = Integer.parseInt(args[0]);
        List<String> cmd  = Arrays.asList(args).subList(1, args.length);
        Process      proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        toAgent = new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8);
        Thread reader = new Thread(() -> {
            try (Reader r = new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8)) {
                int c;
                while ((c = r.read()) != -1) { OUT.append((char) c); System.out.print((char) c); System.out.flush(); }
            } catch (IOException ignored) { }
            eof = true;
        });
        reader.setDaemon(true);
        reader.start();

        int       base = 30 + new Random().nextInt(300);
        LocalDate d1   = LocalDate.now().plusDays(base), d2 = d1.plusDays(1), d3 = d1.plusDays(2);

        if (await(0) != PROMPT) { finish(proc, "The agent did not show its prompt. Is the model reachable?"); return; }

        header("1 accept: book Daisy on " + d1);
        Turn t = turn("Book Daisy, the duck of Betty Davis, on " + d1 + " for an annual checkup. No vet preference.", "yes", 0);
        check("confirmation was asked", t.confirmationAsked());
        check("book_visit answered 'Booked visit'", t.segment().contains("Booked visit"));

        header("2 decline: book Huey on " + d2 + ", answer no, then list visits");
        t = turn("Book Huey, the duck of Harold Davis, on " + d2 + " because of a sore wing. No vet preference.", "no", 0);
        check("confirmation was asked", t.confirmationAsked());
        check("server answered 'Not booked: the user did not confirm'", t.segment().contains("Not booked"));
        t = turn("List all visits of Huey.", null, 0);
        check("list_visits was called", t.segment().contains("[tool] list_visits"));
        check("no visit on " + d2 + " exists", !t.segment().contains(d2.toString()));

        header("3 duplicate: book Daisy on " + d1 + " again");
        t = turn("Book Daisy, the duck of Betty Davis, on " + d1 + " for an annual checkup. No vet preference.", "no", 0);
        check("no confirmation was asked", !t.confirmationAsked());
        check("server refused with 'already has a visit'", t.segment().contains("already has a visit"));

        header("4 slow answer: book Daisy on " + d3 + ", wait " + slow + " s before yes");
        t = turn("Book Daisy, the duck of Betty Davis, on " + d3 + " for a beak check. No vet preference.", "yes", slow);
        check("confirmation was asked", t.confirmationAsked());
        check("book_visit answered 'Booked visit' after the wait (requestState still valid)", t.segment().contains("Booked visit"));

        finish(proc, null);
    }


    /** Types one utterance; if the agent asks for the confirmation, waits {@code waitSeconds}, then types {@code answer}. */
    private static Turn turn(final String text, final String answer, final int waitSeconds) throws Exception {
        int mark = OUT.length();
        send(text);
        int end = await(mark);
        boolean asked = false;
        if (end == CONFIRMING) {
            asked = true;
            if (waitSeconds > 0) { Thread.sleep(waitSeconds * 1000L); }
            send(answer == null ? "no" : answer);
            end = await(OUT.length());
        }
        return new Turn(OUT.substring(mark), asked, end);
    }

    private static void send(final String line) throws IOException {
        toAgent.write(line + "\n");
        toAgent.flush();
    }

    private static int await(final int mark) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < end) {
            String segment = OUT.substring(mark);
            if (segment.endsWith(CONFIRM)) { return CONFIRMING; }
            if (segment.endsWith("\n> ") || segment.equals("> ")) { return PROMPT; }
            if (eof) { return ENDED; }
            Thread.sleep(100);
        }
        return TIMEOUT;
    }

    private static void header(final String text) {
        System.out.println("\n\n===== CASE " + text + " =====");
    }

    private static void check(final String what, final boolean ok) {
        String line = (ok ? "PASS  " : "FAIL  ") + what;
        RESULTS.add(line);
        if (!ok) { failed = true; }
        System.out.println("\n--> " + line);
    }

    private static void finish(final Process proc, final String problem) throws Exception {
        if (problem != null) { check(problem, false); }
        try { send("quit"); } catch (IOException ignored) { }
        proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        proc.destroy();
        System.out.println("\n\n===== SUMMARY =====");
        RESULTS.forEach(System.out::println);
        System.exit(failed ? 1 : 0);
    }
}
