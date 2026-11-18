package eu.hansolo.duckvoice;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.McpClientException;
import eu.hansolo.mcp.client.ToolResult;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;


/** The tools of an MCP server, through an {@link McpClient}. Copied from the agent-plain example; a runtime failure other than McpClientException is also returned to the model. */
public final class McpToolBackend implements ToolBackend {
    private final McpClient client;


    public McpToolBackend(final McpClient client) {
        this.client = client;
    }


    @Override public JsonArray tools() {
        JsonArrayBuilder tools = Json.createArrayBuilder();
        for (JsonObject tool : client.tools()) {
            JsonObject schema = tool.containsKey("inputSchema") ? tool.getJsonObject("inputSchema") : Json.createObjectBuilder().add("type", "object").build();
            tools.add(Json.createObjectBuilder().add("name", tool.getString("name")).add("description", tool.getString("description", "")).add("input_schema", schema));
        }
        return tools.build();
    }

    @Override public Outcome call(final String name, final JsonObject arguments) {
        try {
            ToolResult result = client.callTool(name, arguments);
            if (!result.complete()) { return new Outcome("The tool did not finish (resultType " + result.resultType() + ").", true); }
            return new Outcome(text(result), result.isError());
        } catch (McpClientException e) {
            return new Outcome("The tool call failed: " + e.getMessage(), true);
        } catch (RuntimeException e) {
            return new Outcome("The tool call failed unexpectedly: " + e, true);
        }
    }

    private static String text(final ToolResult result) {
        StringBuilder text = new StringBuilder();
        for (JsonValue block : result.content()) {
            if (block instanceof JsonObject object && "text".equals(object.getString("type", ""))) {
                if (!text.isEmpty()) { text.append('\n'); }
                text.append(object.getString("text", ""));
            }
        }
        if (text.isEmpty() && result.structuredContent() != null) { return result.structuredContent().toString(); }
        return text.toString();
    }
}
