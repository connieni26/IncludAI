import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;

public class Main {

    // Read the API key from an environment variable — never hardcode it.
    private static final String API_KEY = System.getenv("ANTHROPIC_API_KEY");
    private static final String ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
    private static final HttpClient httpClient = HttpClient.newHttpClient();

    public static void main(String[] args) throws IOException {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.out.println("WARNING: ANTHROPIC_API_KEY is not set. /api/transition will fail until it is.");
        }

        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/transition", new TransitionHandler());
        server.createContext("/", new StaticFileHandler());

        server.setExecutor(null); // default executor
        server.start();
        System.out.println("Transition Translator running at http://localhost:" + port);
    }

    /** Serves index.html, style.css, script.js from the current directory. */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/")) path = "/index.html";

            Path filePath = Path.of("." + path);
            if (!Files.exists(filePath)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            String contentType = "text/plain";
            if (path.endsWith(".html")) contentType = "text/html";
            else if (path.endsWith(".css")) contentType = "text/css";
            else if (path.endsWith(".js")) contentType = "application/javascript";

            byte[] bytes = Files.readAllBytes(filePath);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    /** Handles POST /api/transition — calls Claude to generate a transition script. */
    static class TransitionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            // CORS headers so the frontend can call this easily during dev
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

            if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            try {
                // Read the request body (JSON from the frontend)
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                Map<String, String> fields = parseSimpleJson(body);

                String currentActivity = fields.getOrDefault("currentActivity", "");
                String nextActivity = fields.getOrDefault("nextActivity", "");
                String notes = fields.getOrDefault("notes", "");

                String rawScript = callClaude(currentActivity, nextActivity, notes);
                String responseJson = buildStructuredJson(rawScript);
                byte[] responseBytes = responseJson.getBytes(StandardCharsets.UTF_8);

                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            } catch (Exception e) {
                e.printStackTrace();
                String errorJson = "{\"error\": " + jsonEscape(e.getMessage()) + "}";
                byte[] errorBytes = errorJson.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, errorBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(errorBytes);
                }
            }
        }

        /** Calls the Anthropic API and returns the generated transition script text. */
        private String callClaude(String currentActivity, String nextActivity, String notes) throws Exception {
            String systemPrompt =
                "You write transition scripts for neurodivergent K-12 students moving from one activity " +
                "to another. Respond in EXACTLY this plain-text format, nothing else, no markdown, no extra " +
                "commentary before or after:\n\n" +
                "MINUTES: <number of minutes until the transition starts, digits only>\n" +
                "REASON: <one short sentence if anything is different from the student's usual routine today, " +
                "otherwise leave this line blank after the colon>\n" +
                "GREETING: <one short warm sentence introducing the transition>\n" +
                "STEP: [icon] <step text>\n" +
                "STEP: [icon] <step text>\n" +
                "(3 to 5 STEP lines total, each on its own line)\n" +
                "CLOSING: <one short sentence confirming the transition is complete and naming the next activity>\n\n" +
                "For [icon], choose exactly one word from this list that best matches each step: " +
                "book, pencil, clock, chair, backpack, hand, star, check, walk, sit, listen. " +
                "Keep every line concise, literal, and predictable. You do not know who, if anyone, is " +
                "physically present with the student — do not assume a teacher, parent, or any other person " +
                "is in the room, and do not invent actions, promises, or rewards from other people (for " +
                "example, do not say a teacher will give a warning, a sticker, or anything else) unless the " +
                "student's own notes explicitly mention that person and what they do. Every step should " +
                "describe only what the student themselves will do, see, or notice, using neutral phrasing " +
                "like 'you'll notice' or 'you'll get' rather than referring to what a specific other person " +
                "will do.";

            String userPrompt = "Current activity: " + currentActivity + "\n" +
                "Next activity: " + nextActivity + "\n" +
                "What helps this student with transitions: " + notes;

            String requestBody = "{"
                + "\"model\": \"claude-sonnet-4-6\","
                + "\"max_tokens\": 300,"
                + "\"system\": " + jsonEscape(systemPrompt) + ","
                + "\"messages\": [{\"role\": \"user\", \"content\": " + jsonEscape(userPrompt) + "}]"
                + "}";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", API_KEY)
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Anthropic API error (" + response.statusCode() + "): " + response.body());
            }

            // Minimal extraction of the "text" field from the response JSON.
            return extractFirstTextField(response.body());
        }

        /** Extremely small helper: parses a flat JSON object of string fields. Good enough for our simple frontend payload. */
        private Map<String, String> parseSimpleJson(String json) {
            Map<String, String> map = new HashMap<>();
            json = json.trim();
            if (json.startsWith("{")) json = json.substring(1);
            if (json.endsWith("}")) json = json.substring(0, json.length() - 1);

            int i = 0;
            while (i < json.length()) {
                int keyStart = json.indexOf('"', i);
                if (keyStart == -1) break;
                int keyEnd = json.indexOf('"', keyStart + 1);
                String key = json.substring(keyStart + 1, keyEnd);

                int colon = json.indexOf(':', keyEnd);
                int valStart = json.indexOf('"', colon);
                int valEnd = valStart + 1;
                StringBuilder val = new StringBuilder();
                while (valEnd < json.length() && json.charAt(valEnd) != '"') {
                    if (json.charAt(valEnd) == '\\' && valEnd + 1 < json.length()) {
                        val.append(json.charAt(valEnd + 1));
                        valEnd += 2;
                    } else {
                        val.append(json.charAt(valEnd));
                        valEnd++;
                    }
                }
                map.put(key, val.toString());
                i = valEnd + 1;
            }
            return map;
        }

        /** Pulls the value of the first "text": "..." field out of the Anthropic response JSON. */
        private String extractFirstTextField(String json) {
            String marker = "\"text\":";
            int idx = json.indexOf(marker);
            if (idx == -1) return "(no text found in response)";
            int start = json.indexOf('"', idx + marker.length()) + 1;
            StringBuilder sb = new StringBuilder();
            int i = start;
            while (i < json.length() && json.charAt(i) != '"') {
                if (json.charAt(i) == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == 'n') sb.append('\n');
                    else sb.append(next);
                    i += 2;
                } else {
                    sb.append(json.charAt(i));
                    i++;
                }
            }
            return sb.toString();
        }

        /**
         * Parses the model's structured plain-text response (MINUTES/REASON/GREETING/STEP/CLOSING
         * lines) into a JSON object the frontend can render as a step-by-step UI.
         */
        private String buildStructuredJson(String raw) {
            // Defensive cleanup in case the model still emits stray markdown.
            raw = raw.replace("**", "").replace("__", "").replace("##", "");

            String minutes = "0";
            String reason = "";
            String greeting = "";
            String closing = "";
            java.util.List<String[]> steps = new java.util.ArrayList<>(); // [icon, text]

            for (String rawLine : raw.split("\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("MINUTES:")) {
                    String digits = line.substring("MINUTES:".length()).trim().replaceAll("[^0-9]", "");
                    minutes = digits.isEmpty() ? "0" : digits;
                } else if (line.startsWith("REASON:")) {
                    reason = line.substring("REASON:".length()).trim();
                } else if (line.startsWith("GREETING:")) {
                    greeting = line.substring("GREETING:".length()).trim();
                } else if (line.startsWith("CLOSING:")) {
                    closing = line.substring("CLOSING:".length()).trim();
                } else if (line.startsWith("STEP:")) {
                    String rest = line.substring("STEP:".length()).trim();
                    String icon = "check";
                    String text = rest;
                    if (rest.startsWith("[")) {
                        int close = rest.indexOf(']');
                        if (close != -1) {
                            icon = rest.substring(1, close).trim().toLowerCase();
                            text = rest.substring(close + 1).trim();
                        }
                    }
                    steps.add(new String[]{icon, text});
                }
            }

            StringBuilder stepsJson = new StringBuilder("[");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) stepsJson.append(",");
                stepsJson.append("{\"icon\": ").append(jsonEscape(steps.get(i)[0]))
                    .append(", \"text\": ").append(jsonEscape(steps.get(i)[1])).append("}");
            }
            stepsJson.append("]");

            return "{"
                + "\"minutes\": " + minutes + ","
                + "\"reason\": " + jsonEscape(reason) + ","
                + "\"greeting\": " + jsonEscape(greeting) + ","
                + "\"steps\": " + stepsJson + ","
                + "\"closing\": " + jsonEscape(closing)
                + "}";
        }

        /** Escapes a string for safe embedding in JSON. */
        private String jsonEscape(String s) {
            if (s == null) s = "";
            StringBuilder sb = new StringBuilder("\"");
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                        else sb.append(c);
                }
            }
            sb.append("\"");
            return sb.toString();
        }
    }
}