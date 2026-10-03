package com.example.discordbridge.commands;

import com.example.discordbridge.config.BridgeConfig;
import com.example.discordbridge.discord.DiscordClient;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import meteordevelopment.meteorclient.commands.Command;
import net.minecraft.commands.SharedSuggestionProvider;

public class DiscordCommand extends Command {
    public DiscordCommand() {
        super("discord", "Configure the Discord chat bridge.");
    }

    @Override
    public void build(LiteralArgumentBuilder<SharedSuggestionProvider> builder) {
        // .discord webhook <url>
        builder.then(literal("webhook")
            .then(argument("url", StringArgumentType.greedyString()).executes(ctx -> {
                String url = StringArgumentType.getString(ctx, "url").trim();
                if (!DiscordClient.isValidWebhook(url)) {
                    error("That is not a valid Discord webhook URL.");
                    return SINGLE_SUCCESS;
                }
                BridgeConfig cfg = BridgeConfig.get();
                cfg.webhookUrl = url;
                cfg.save();
                info("Webhook saved.");
                return SINGLE_SUCCESS;
            })));

        // .discord token <token>
        builder.then(literal("token")
            .then(argument("token", StringArgumentType.greedyString()).executes(ctx -> {
                BridgeConfig cfg = BridgeConfig.get();
                cfg.botToken = StringArgumentType.getString(ctx, "token").trim();
                cfg.save();
                DiscordClient.get().restartPolling();
                info("Bot token saved.");
                return SINGLE_SUCCESS;
            })));

        // .discord channel <id>
        builder.then(literal("channel")
            .then(argument("id", StringArgumentType.word()).executes(ctx -> {
                String id = StringArgumentType.getString(ctx, "id").trim();
                if (!id.matches("\\d{5,25}")) {
                    error("The channel ID must be numeric. Enable Developer Mode in Discord, right click the channel, Copy Channel ID.");
                    return SINGLE_SUCCESS;
                }
                BridgeConfig cfg = BridgeConfig.get();
                cfg.channelId = id;
                cfg.save();
                DiscordClient.get().restartPolling();
                info("Channel ID saved.");
                return SINGLE_SUCCESS;
            })));

        // .discord interval <ms>
        builder.then(literal("interval")
            .then(argument("ms", IntegerArgumentType.integer(1000, 60000)).executes(ctx -> {
                BridgeConfig cfg = BridgeConfig.get();
                cfg.pollIntervalMs = IntegerArgumentType.getInteger(ctx, "ms");
                cfg.save();
                info("Poll interval set to " + cfg.pollIntervalMs + " ms.");
                return SINGLE_SUCCESS;
            })));

        // .discord clear <webhook|token|all>
        builder.then(literal("clear")
            .then(literal("webhook").executes(ctx -> {
                BridgeConfig cfg = BridgeConfig.get();
                cfg.webhookUrl = "";
                cfg.save();
                info("Webhook cleared.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("token").executes(ctx -> {
                BridgeConfig cfg = BridgeConfig.get();
                cfg.botToken = "";
                cfg.save();
                DiscordClient.get().stopPolling();
                info("Bot token cleared.");
                return SINGLE_SUCCESS;
            }))
            .then(literal("all").executes(ctx -> {
                BridgeConfig cfg = BridgeConfig.get();
                cfg.webhookUrl = "";
                cfg.botToken = "";
                cfg.channelId = "";
                cfg.save();
                DiscordClient.get().stopPolling();
                info("All Discord settings cleared.");
                return SINGLE_SUCCESS;
            })));

        // .discord status
        builder.then(literal("status").executes(ctx -> {
            BridgeConfig cfg = BridgeConfig.get();
            info("Webhook: " + (cfg.webhookUrl.isBlank() ? "not set" : "set"));
            info("Bot token: " + (cfg.botToken.isBlank() ? "not set" : "set"));
            info("Channel ID: " + (cfg.channelId.isBlank() ? "not set" : cfg.channelId));
            info("Sending: " + (cfg.canSend() ? (cfg.webhookUrl.isBlank() ? "via bot" : "via webhook") : "not configured"));
            info("Receiving: " + (cfg.canReceive() ? "configured" : "not configured")
                + ", polling " + (DiscordClient.get().isPolling() ? "active" : "inactive"));
            return SINGLE_SUCCESS;
        }));

        // .discord test
        builder.then(literal("test").executes(ctx -> {
            if (!BridgeConfig.get().canSend()) {
                error("Nothing configured to send with. Use .discord webhook <url> or .discord token + channel.");
                return SINGLE_SUCCESS;
            }
            DiscordClient.get().send("Test message from Meteor Discord Bridge")
                .thenAccept(ok -> mc.execute(() -> {
                    if (ok) info("Test message sent.");
                    else error("Test message failed, see the error above or latest.log.");
                }));
            return SINGLE_SUCCESS;
        }));
    }
}
