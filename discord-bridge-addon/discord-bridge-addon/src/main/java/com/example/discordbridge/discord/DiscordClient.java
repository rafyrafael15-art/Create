package com.example.discordbridge.discord;

import com.example.discordbridge.DiscordBridgeAddon;
import com.example.discordbridge.config.BridgeConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Client REST minimale per Discord (nessuna dipendenza esterna).
 * - Invio: webhook oppure bot (POST /channels/{id}/messages)
 * - Ricezione: polling di GET /channels/{id}/messages?after=...
 */
public final class DiscordClient {
    private static final String API = "https://discord.com/api/v10";
    private static final String USER_AGENT = "DiscordBot (https://example.com, 1.0)";
    private static final Pattern WEBHOOK = Pattern.compile(
        "^https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api(?:/v\\d+)?/webhooks/\\d+/[\\w-]+/?(?:\\?.*)?$");

    private static final DiscordClient INSTANCE = new DiscordClient();

    public static DiscordClient get() {
        return INSTANCE;
    }

    /** Accetta solo URL di webhook ufficiali Discord (evita di inviare chat a host arbitrari). */
    public static boolean isValidWebhook(String url) {
        return url != null && WEBHOOK.matcher(url).matches();
    }

    public record IncomingMessage(String author, String content) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private ScheduledExecutorService executor;
    private Consumer<IncomingMessage> listener;
    private String lastMessageId; // null = baseline non ancora fissato
    private volatile Consumer<String> errorHandler = s -> {};
    private volatile String lastError;

    private DiscordClient() {}

    public void setErrorHandler(Consumer<String> handler) {
        this.errorHandler = handler != null ? handler : s -> {};
    }

    public synchronized boolean isPolling() {
        return executor != null;
    }

    // ------------------------------------------------------------------ invio

    public CompletableFuture<Boolean> send(String content) {
        BridgeConfig cfg = BridgeConfig.get();
        if (content == null || content.isBlank()) return CompletableFuture.completedFuture(false);
        final String text = content.length() > 2000 ? content.substring(0, 1997) + "..." : content;

        JsonObject body = new JsonObject();
        body.addProperty("content", text);
        // niente @everyone / @here / ping di ruoli o utenti
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        body.add("allowed_mentions", mentions);

        HttpRequest.Builder rb;
        try {
            if (!cfg.webhookUrl.isBlank()) {
                body.addProperty("username", cfg.webhookUsername);
                rb = HttpRequest.newBuilder(URI.create(cfg.webhookUrl));
            } else if (cfg.hasBot()) {
                rb = HttpRequest.newBuilder(URI.create(API + "/channels/" + cfg.channelId + "/messages"))
                    .header("Authorization", "Bot " + cfg.botToken);
            } else {
                return CompletableFuture.completedFuture(false);
            }
        } catch (IllegalArgumentException e) {
            report("Invalid Discord URL in config: " + e.getMessage());
            return CompletableFuture.completedFuture(false);
        }

        HttpRequest req = rb
            .header("Content-Type", "application/json")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10))
            .POST(BodyPublishers.ofString(body.toString()))
            .build();

        return http.sendAsync(req, BodyHandlers.ofString())
            .thenApply(resp -> {
                int s = resp.statusCode();
                if (s / 100 == 2) {
                    lastError = null;
                    return true;
                }
                report("Discord send failed: HTTP " + s + describe(resp.body()) + hint(s));
                return false;
            })
            .exceptionally(t -> {
                report("Discord send failed: " + t.getMessage());
                return false;
            });
    }

    // -------------------------------------------------------------- ricezione

    public synchronized void startPolling(Consumer<IncomingMessage> newListener) {
        stopPolling();
        listener = newListener;
        lastMessageId = null;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DiscordBridge-Poller");
            t.setDaemon(true);
            return t;
        });
        schedule(executor, 0);
    }

    public synchronized void stopPolling() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        listener = null;
    }

    /** Riavvia il polling (es. dopo aver cambiato token/canale), solo se era attivo. */
    public synchronized void restartPolling() {
        Consumer<IncomingMessage> l = listener;
        if (executor != null && l != null) startPolling(l);
    }

    private void schedule(ScheduledExecutorService ex, long delayMs) {
        try {
            ex.schedule(() -> {
                long next = BridgeConfig.get().pollIntervalMs;
                try {
                    next = Math.max(next, poll());
                } catch (InterruptedException e) {
                    return;
                } catch (Exception e) {
                    report("Discord poll failed: " + e.getMessage());
                    next = Math.max(next, 10_000);
                }
                if (!ex.isShutdown()) schedule(ex, next);
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // executor chiuso
        }
    }

    /** @return millisecondi extra da attendere prima del prossimo poll (0 = usa l'intervallo normale). */
    private long poll() throws IOException, InterruptedException {
        BridgeConfig cfg = BridgeConfig.get();
        if (!cfg.canReceive()) return 5000;

        boolean baseline = lastMessageId == null;
        String url = API + "/channels/" + cfg.channelId + "/messages?limit=" + (baseline ? 1 : 50)
            + (baseline ? "" : "&after=" + lastMessageId);

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("Authorization", "Bot " + cfg.botToken)
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        int status = resp.statusCode();

        if (status == 429) {
            double retry = 5;
            try {
                retry = JsonParser.parseString(resp.body()).getAsJsonObject().get("retry_after").getAsDouble();
            } catch (Exception ignored) {}
            return (long) (retry * 1000) + 250;
        }
        if (status / 100 != 2) {
            report("Discord poll failed: HTTP " + status + describe(resp.body()) + hint(status));
            return 15_000;
        }
        lastError = null;

        JsonArray arr = JsonParser.parseString(resp.body()).getAsJsonArray();

        if (baseline) {
            // Salta la cronologia: da ora in poi stampa solo i messaggi nuovi
            lastMessageId = arr.isEmpty() ? "0" : arr.get(0).getAsJsonObject().get("id").getAsString();
            return 0;
        }

        List<JsonObject> msgs = new ArrayList<>();
        for (JsonElement e : arr) msgs.add(e.getAsJsonObject());
        msgs.sort(Comparator.comparingLong(m -> Long.parseLong(m.get("id").getAsString())));

        Consumer<IncomingMessage> l;
        synchronized (this) {
            l = listener;
        }

        for (JsonObject m : msgs) {
            lastMessageId = m.get("id").getAsString();
            if (l == null) continue;

            int type = m.has("type") ? m.get("type").getAsInt() : 0;
            if (type != 0 && type != 19) continue; // solo messaggi normali e risposte

            JsonObject author = m.has("author") && m.get("author").isJsonObject() ? m.getAsJsonObject("author") : null;
            if (author != null && author.has("bot") && author.get("bot").getAsBoolean()) continue; // anti-loop

            String name = str(author, "global_name");
            if (name.isBlank()) name = str(author, "username");
            if (name.isBlank()) name = "Unknown";

            String content = str(m, "content");
            if (m.has("attachments") && m.get("attachments").isJsonArray() && !m.getAsJsonArray("attachments").isEmpty()) {
                content = (content + " [attachment]").trim();
            }
            if (content.isBlank()) continue;

            l.accept(new IncomingMessage(name, content));
        }
        return 0;
    }

    // ---------------------------------------------------------------- helper

    private void report(String msg) {
        DiscordBridgeAddon.LOG.warn(msg);
        if (!msg.equals(lastError)) { // evita di spammare la chat con lo stesso errore
            lastError = msg;
            errorHandler.accept(msg);
        }
    }

    private static String hint(int status) {
        return switch (status) {
            case 401 -> " (check the bot token)";
            case 403 -> " (bot needs View Channel, Read Message History, Send Messages)";
            case 404 -> " (wrong channel ID / webhook, or bot not in the server)";
            default -> "";
        };
    }

    private static String describe(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("message")) return ": " + o.get("message").getAsString();
        } catch (Exception ignored) {}
        return "";
    }

    private static String str(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        return o.get(key).getAsString();
    }
}
