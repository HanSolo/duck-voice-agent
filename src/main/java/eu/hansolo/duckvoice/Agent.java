package eu.hansolo.duckvoice;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.List;


/**
 * The tool-calling loop of the agent-plain example, but with a conversation that lasts over several utterances (the example starts fresh with every question, which
 * cannot work for "Betty Davis." as the answer to "Which owner?"). Not thread-safe: one utterance at a time.
 */
public final class Agent {
    private final Llm              llm;
    private final ToolBackend      backend;
    private final int              maxRounds;
    private final List<JsonObject> history = new ArrayList<>();


    public Agent(final Llm llm, final ToolBackend backend, final int maxRounds) {
        this.llm       = llm;
        this.backend   = backend;
        this.maxRounds = maxRounds;
    }


    /** Handles one utterance. A failing model request is returned as {@code error} and the conversation is left as it was before the utterance. */
    public Reply say(final String utterance) {
        int        mark  = history.size();
        List<Call> calls = new ArrayList<>();
        history.add(Json.createObjectBuilder().add("role", "user").add("content", utterance).build());
        try {
            JsonArray tools = backend.tools();
            for (int round = 1; round <= maxRounds; round++) {
                JsonArray reply = llm.complete(tools, Json.createArrayBuilder(history).build());
                history.add(Json.createObjectBuilder().add("role", "assistant").add("content", reply).build());
                JsonArrayBuilder results = Json.createArrayBuilder();
                boolean          asked   = false;
                for (JsonValue value : reply) {
                    JsonObject block = value.asJsonObject();
                    if (!"tool_use".equals(block.getString("type", ""))) { continue; }
                    asked = true;
                    JsonObject          input   = block.containsKey("input") ? block.getJsonObject("input") : Json.createObjectBuilder().build();
                    ToolBackend.Outcome outcome = backend.call(block.getString("name"), input);
                    calls.add(new Call(block.getString("name"), input, outcome.text(), outcome.isError()));
                    results.add(Json.createObjectBuilder().add("type", "tool_result").add("tool_use_id", block.getString("id")).add("content", outcome.text()).add("is_error", outcome.isError()));
                }
                if (!asked) { return new Reply(text(reply), false, calls, null); }
                history.add(Json.createObjectBuilder().add("role", "user").add("content", results).build());
            }
            return new Reply("I could not finish that. Please try again.", true, calls, null);
        } catch (RuntimeException e) {
            while (history.size() > mark) { history.remove(history.size() - 1); }
            return new Reply("", false, calls, e.getMessage());
        }
    }

    private static String text(final JsonArray blocks) {
        StringBuilder text = new StringBuilder();
        for (JsonValue value : blocks) {
            JsonObject block = value.asJsonObject();
            if ("text".equals(block.getString("type", ""))) {
                if (!text.isEmpty()) { text.append('\n'); }
                text.append(block.getString("text", ""));
            }
        }
        return text.toString();
    }


    /** One tool call the model asked for. */
    public record Call(String name, JsonObject arguments, String result, boolean isError) {}

    /** What to say, whether the round limit cut the answer short, every tool call of the utterance in order, and an error text if the model request failed. */
    public record Reply(String text, boolean truncated, List<Call> calls, String error) {}
}
