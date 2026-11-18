package eu.hansolo.duckvoice;

import jakarta.json.JsonArray;


/** One request to a language model. Messages and content blocks use the shape of the Anthropic Messages API. Copied from the agent-plain example. */
public interface Llm {

    /**
     * @param tools    the tool definitions ({@code name}, {@code description}, {@code input_schema}); may be empty
     * @param messages the conversation so far: {@code role} and {@code content} (a string or an array of blocks)
     * @return the content blocks of the model's reply: {@code text} and {@code tool_use} blocks
     */
    JsonArray complete(JsonArray tools, JsonArray messages);
}
