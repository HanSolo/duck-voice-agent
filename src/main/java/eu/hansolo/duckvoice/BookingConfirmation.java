package eu.hansolo.duckvoice;

import eu.hansolo.mcp.client.Elicitation;
import jakarta.json.Json;
import jakarta.json.JsonObject;


/**
 * Answers the server's {@code confirm} question. Hard rule of the demo: the answer comes from the user only, so this class has no reference to the model. Only a clear yes
 * from the {@link Asker} becomes {@code accept({"confirm": true})}; anything else, including a form it does not understand, becomes {@code decline}.
 */
public final class BookingConfirmation implements Elicitation.Handler {
    private final Asker asker;


    public BookingConfirmation(final Asker asker) {
        this.asker = asker;
    }


    @Override public Elicitation.Response elicit(final Elicitation.Request request) {
        if (request.mode() != Elicitation.Mode.FORM) { return Elicitation.Response.decline(); }
        JsonObject answer = Json.createObjectBuilder().add("confirm", true).build();
        if (!request.problems(answer).isEmpty()) { return Elicitation.Response.decline(); }
        return asker.askYesNo(request.message()) ? Elicitation.Response.accept(answer) : Elicitation.Response.decline();
    }


    /** Where the answer comes from: the console now, the microphone in step 2. */
    @FunctionalInterface
    public interface Asker {

        /** Shows the message, waits for the user and returns true only for a clear yes. */
        boolean askYesNo(String message);
    }
}
