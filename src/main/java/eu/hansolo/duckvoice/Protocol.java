package eu.hansolo.duckvoice;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;


/**
 * The JSON-lines link to the Swift front end, one JSON object per line.
 * <pre>
 * Swift to Java: {"type":"utterance","text":"..."}  {"type":"answer","yes":true|false}  {"type":"quit"}
 * Java to Swift: {"type":"ready"}  {"type":"say","text":"..."}  {"type":"confirm","message":"..."}
 *                {"type":"tool","name":"...","arguments":"...","result":"...","isError":false}  {"type":"error","message":"..."}
 * </pre>
 * Only {@code answer} with {@code yes:true} confirms a booking. While a confirmation is open, any other message (a new utterance, quit, end of input) counts as "no".
 * Not thread-safe: the confirmation runs on the thread that reads the utterances, because the MCP client calls the handler on the calling thread.
 */
public final class Protocol {
    private final BufferedReader in;
    private final PrintStream    out;
    private boolean              closed;


    public Protocol(final BufferedReader in, final PrintStream out) {
        this.in  = in;
        this.out = out;
    }


    public boolean isClosed() { return closed; }

    public void send(final String type, final String... keyValues) {
        JsonObjectBuilder message = Json.createObjectBuilder().add("type", type);
        for (int i = 0; i + 1 < keyValues.length; i += 2) { message.add(keyValues[i], keyValues[i + 1]); }
        write(message.build());
    }

    public void sendTool(final Agent.Call call) {
        write(Json.createObjectBuilder().add("type", "tool").add("name", call.name()).add("arguments", call.arguments().toString()).add("result", call.result()).add("isError", call.isError()).build());
    }

    /** The next message from the front end, or null at the end of input or after {@code quit}. Lines that are not JSON objects are reported as errors and skipped. */
    public JsonObject next() {
        while (!closed) {
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                line = null;
            }
            if (line == null) { closed = true; return null; }
            if (line.isBlank()) { continue; }
            try (var reader = Json.createReader(new StringReader(line))) {
                JsonObject message = reader.readObject();
                if ("quit".equals(message.getString("type", ""))) { closed = true; return null; }
                return message;
            } catch (JsonException | IllegalStateException e) {
                send("error", "message", "Not a JSON object: " + line);
            }
        }
        return null;
    }

    /** Sends the confirmation text and blocks until the front end answers. Only {"type":"answer","yes":true} returns true. */
    public boolean askYesNo(final String message) {
        send("confirm", "message", message);
        JsonObject answer = next();
        return answer != null && "answer".equals(answer.getString("type", "")) && answer.getBoolean("yes", false);
    }

    private void write(final JsonObject message) {
        out.println(message);
        out.flush();
    }
}
