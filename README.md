# duck-voice-agent, step 1 (typed input)

## Run
1. Client, once: `cd jakarta-mcp-client && mvn -q -DskipTests install`
2. Duck clinic running on Payara Micro. Endpoint in the zip you sent: `http://localhost:8080/duck-clinic/mcp` (`@ApplicationPath("")` is still there).
3. Here:
```
mvn -q package
export ANTHROPIC_API_KEY=...  ANTHROPIC_MODEL=...
# optional: MCP_URL=http://localhost:8080/duck-clinic/api/mcp
java -jar target/duck-voice-agent.jar
```
Tool calls print to stderr as `[tool] ...`.

## Test cases
1. Accept: "Book Daisy for next Tuesday, annual checkup." Answer the model's questions, type `yes` at CONFIRM. Expect `[tool] book_visit ... -> Booked visit ...`.
2. Decline: same with another duck or date, answer `no`. Expect `Not booked: the user did not confirm.`, then ask "show visits of <duck>": no new visit.
3. Duplicate: book the same duck and date again. Expect the refusal before any CONFIRM line.
4. Slow answer: wait several minutes at CONFIRM, then `yes`. Tells us whether requestState expires.

## Step 2: voice (macOS 26 or newer)
`MODEL=<name> ./duck-voice.sh voice` builds `swift/` and opens a window. Press Talk (or Space), speak, press Done. The agent answers aloud. At the confirmation it reads the booking back and waits for a spoken yes or no, or the on-screen buttons. Only a clear yes ("yes", "yeah", "yep", "yes please", "book it", "correct", "confirm") books; anything else is a no.
The Java process runs with `--json` and speaks JSON lines on stdin and stdout, see `Protocol.java`. The first run asks for microphone access for your terminal and downloads the on-device speech model.
