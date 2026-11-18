package eu.hansolo.duckvoice;

import eu.hansolo.mcp.client.Elicitation;
import eu.hansolo.mcp.client.McpClient;

import jakarta.json.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Set;


/**
 * Console agent for the duck clinic. Environment: {@code ANTHROPIC_API_KEY} and {@code ANTHROPIC_MODEL} (required), {@code ANTHROPIC_BASE_URL} (default api.anthropic.com),
 * {@code MCP_URL} (default {@code http://localhost:8080/duck-clinic/mcp}), {@code AGENT_MAX_ROUNDS} (default 8), {@code ANTHROPIC_MAX_TOKENS} (default 1024).
 * Tool calls are printed to stderr. With {@code --json} it speaks the JSON-lines {@link Protocol} on stdin and stdout, for the Swift front end.
 */
public final class Main {
    private static final Set<String> YES = Set.of("y", "yes", "yeah", "yep", "ja");


    private Main() { }


    public static void main(final String[] args) throws IOException {
        String key   = required("ANTHROPIC_API_KEY");
        String model = required("ANTHROPIC_MODEL");
        String base  = env("ANTHROPIC_BASE_URL", "https://api.anthropic.com");
        String url   = env("MCP_URL", "http://localhost:8080/duck-clinic/mcp");

        boolean        json     = args.length > 0 && args[0].equals("--json");
        BufferedReader in       = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Protocol       protocol = json ? new Protocol(in, System.out) : null;
        if (json) { System.setOut(System.err); }   // stdout carries protocol lines only

        BookingConfirmation confirmation = new BookingConfirmation(json ? protocol::askYesNo : message -> {
            System.out.println();
            System.out.println("CONFIRM: " + message);
            System.out.print("Type 'yes' to book, anything else cancels: ");
            System.out.flush();
            try {
                String line = in.readLine();
                return line != null && YES.contains(line.trim().toLowerCase(Locale.ROOT));
            } catch (IOException e) {
                return false;
            }
        });

        McpClient client = McpClient.builder(URI.create(url)).clientInfo("duck-voice-agent", "0.1.0").elicitation(confirmation, Elicitation.Mode.FORM).build();
        Llm       llm    = new AnthropicLlm(base, key, model, Integer.parseInt(env("ANTHROPIC_MAX_TOKENS", "1024")), systemPrompt(LocalDate.now()));
        Agent     agent  = new Agent(llm, new McpToolBackend(client), Integer.parseInt(env("AGENT_MAX_ROUNDS", "8")));

        if (json) {
            runJson(agent, protocol);
            return;
        }
        System.out.println("Duck clinic agent. Type 'quit' to leave.");
        while (true) {
            System.out.print("> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null || line.trim().equalsIgnoreCase("quit")) { break; }
            if (line.isBlank()) { continue; }
            Agent.Reply reply = agent.say(line.trim());
            for (Agent.Call call : reply.calls()) {
                String result = call.result().length() > 300 ? call.result().substring(0, 300) + "..." : call.result();
                System.err.println("[tool] " + call.name() + " " + call.arguments() + (call.isError() ? " ERROR " : " -> ") + result);
            }
            System.out.println(reply.error() != null ? "[model error] " + reply.error() : reply.text());
        }
    }

    private static void runJson(final Agent agent, final Protocol protocol) {
        protocol.send("ready");
        while (true) {
            JsonObject message = protocol.next();
            if (message == null) { break; }
            if (!"utterance".equals(message.getString("type", ""))) { continue; }
            String text = message.getString("text", "").trim();
            if (text.isEmpty()) { continue; }
            Agent.Reply reply = agent.say(text);
            for (Agent.Call call : reply.calls()) { protocol.sendTool(call); }
            if (reply.error() != null) { protocol.send("error", "message", String.valueOf(reply.error())); }
            else if (!reply.text().isBlank()) { protocol.send("say", "text", reply.text()); }
            else { protocol.send("say", "text", "I have nothing to say to that."); }
        }
    }

    static String systemPrompt(final LocalDate today) {
        DayOfWeek day = today.getDayOfWeek();
        return """
               You are the booking assistant of the Duck Clinic. Today is %s, %s. Resolve relative dates ("next Tuesday", "tomorrow") against this date and use yyyy-MM-dd with the tools.

               The clinic treats ducks. Owners have ducks, and vets have specialties. A visit has a date and a reason, no time slot. Replies are read aloud later, so keep them short, plain and without lists or markdown.

               Rules:
               - Never guess ids. Look up the owner with find_owners and take the duck id from its result. If more than one owner or duck matches, ask which one, naming them.
               - Before you call book_visit you need the duck, the date and the reason from the user. Ask for what is missing, one thing at a time.
               - book_visit makes the system ask the user for the final confirmation by itself. Do not ask for a yes yourself after that and never claim anything about the confirmation. Say a visit is booked only if the tool result says "Booked". If the result says the user did not confirm, say nothing was booked.
               - If a tool fails, say so plainly and what you would try next.
               """.formatted(today, day.getDisplayName(TextStyle.FULL, Locale.ENGLISH));
    }

    private static String env(final String name, final String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String required(final String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Set the environment variable " + name); }
        return value.trim();
    }
}
