package eu.hansolo.duckvoice;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;


/** Where the tools come from. Copied from the agent-plain example. */
public interface ToolBackend {

    /** The tool definitions in the shape the Anthropic Messages API wants: {@code name}, {@code description}, {@code input_schema}. */
    JsonArray tools();

    /** Runs one tool. A failure is an outcome with {@code isError} set, so the model can see it and react. */
    Outcome call(String name, JsonObject arguments);


    record Outcome(String text, boolean isError) {}
}
