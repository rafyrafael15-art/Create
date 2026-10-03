package com.example.discordbridge.config;

import com.example.discordbridge.DiscordBridgeAddon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Configurazione salvata in .minecraft/meteor-client/discord-bridge.json */
public class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static BridgeConfig instance;

    public volatile String webhookUrl = "";
    public volatile String webhookUsername = "Minecraft";
    public volatile String botToken = "";
    public volatile String channelId = "";
    public volatile int pollIntervalMs = 3000;

    private static File file() {
        return new File(MeteorClient.FOLDER, "discord-bridge.json");
    }

    public static synchronized BridgeConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    private static BridgeConfig load() {
        File f = file();
        BridgeConfig cfg = null;
        if (f.isFile()) {
            try {
                cfg = GSON.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), BridgeConfig.class);
            } catch (Exception e) {
                DiscordBridgeAddon.LOG.error("Could not read {}", f, e);
            }
        }
        if (cfg == null) {
            cfg = new BridgeConfig();
            cfg.save();
        }
        // normalizza valori mancanti / non validi
        if (cfg.webhookUrl == null) cfg.webhookUrl = "";
        if (cfg.webhookUsername == null || cfg.webhookUsername.isBlank()) cfg.webhookUsername = "Minecraft";
        if (cfg.botToken == null) cfg.botToken = "";
        if (cfg.channelId == null) cfg.channelId = "";
        cfg.pollIntervalMs = Math.max(1000, cfg.pollIntervalMs);
        return cfg;
    }

    public synchronized void save() {
        try {
            File f = file();
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            Files.writeString(f.toPath(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            DiscordBridgeAddon.LOG.error("Could not save config", e);
        }
    }

    public boolean hasBot() {
        return !botToken.isBlank() && channelId.matches("\\d{5,25}");
    }

    /** Si puo' inviare con webhook, oppure con bot token + channel id. */
    public boolean canSend() {
        return !webhookUrl.isBlank() || hasBot();
    }

    /** Ricevere richiede il bot (i webhook sono solo in scrittura). */
    public boolean canReceive() {
        return hasBot();
    }
}
