package com.example.discordbridge.modules;

import com.example.discordbridge.config.BridgeConfig;
import com.example.discordbridge.discord.DiscordClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;
import java.util.Locale;

public class DiscordBridge extends Module {
    /** Prefisso dei messaggi che stampiamo noi: serve a non rimandarli su Discord (loop). */
    private static final String PREFIX = "[Discord] ";

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> toDiscord = sgGeneral.add(new BoolSetting.Builder()
        .name("minecraft-to-discord")
        .description("Forward in-game chat messages to Discord.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> fromDiscord = sgGeneral.add(new BoolSetting.Builder()
        .name("discord-to-minecraft")
        .description("Print Discord messages in the in-game chat (requires bot token + channel ID).")
        .defaultValue(true)
        .onChanged(v -> applyPolling(isActive()))
        .build());

    private final Setting<List<String>> ignoreContaining = sgGeneral.add(new StringListSetting.Builder()
        .name("ignore-containing")
        .description("Chat lines containing any of these texts are NOT sent to Discord.")
        .defaultValue(List.of())
        .build());

    public DiscordBridge() {
        super(Categories.Misc, "discord-bridge", "Bridges Minecraft chat and a Discord channel. Configure with .discord");
    }

    @Override
    public void onActivate() {
        DiscordClient.get().setErrorHandler(msg -> mc.execute(() -> warning(msg)));

        if (toDiscord.get() && !BridgeConfig.get().canSend()) {
            warning("No webhook / bot configured. Use .discord webhook <url> or .discord token + .discord channel");
        }
        applyPolling(true);
    }

    @Override
    public void onDeactivate() {
        DiscordClient.get().stopPolling();
    }

    private void applyPolling(boolean active) {
        DiscordClient client = DiscordClient.get();
        if (!active || !fromDiscord.get()) {
            client.stopPolling();
            return;
        }
        if (!BridgeConfig.get().canReceive()) {
            warning("Receiving needs a bot: use .discord token <token> and .discord channel <id>");
            return;
        }
        client.startPolling(this::printIncoming);
    }

    // ---------------------------------------------------- Minecraft -> Discord

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!toDiscord.get()) return;

        String text = event.getMessage().getString().trim();
        if (text.isEmpty() || text.startsWith(PREFIX) || text.startsWith("[Meteor]")) return;

        String lower = text.toLowerCase(Locale.ROOT);
        for (String s : ignoreContaining.get()) {
            if (!s.isBlank() && lower.contains(s.toLowerCase(Locale.ROOT))) return;
        }

        DiscordClient.get().send(text);
    }

    // ---------------------------------------------------- Discord -> Minecraft

    /** Chiamato dal thread di polling. */
    private void printIncoming(DiscordClient.IncomingMessage msg) {
        // rimuove codici colore e caratteri di controllo, comprime gli spazi, limita la lunghezza
        String content = msg.content().replaceAll("[\\p{Cntrl}\u00a7]+", " ").replaceAll("\\s+", " ").trim();
        if (content.length() > 256) content = content.substring(0, 253) + "...";
        if (content.isEmpty()) return;

        String author = msg.author().replaceAll("[\\p{Cntrl}\u00a7]+", " ").trim();
        final String finalContent = content;

        mc.execute(() -> {
            if (mc.player == null) return;

            MutableComponent line = Component.literal(PREFIX).withStyle(ChatFormatting.BLUE);
            line.append(Component.literal(author).withStyle(ChatFormatting.AQUA));
            line.append(Component.literal(": ").withStyle(ChatFormatting.GRAY));
            line.append(Component.literal(finalContent).withStyle(ChatFormatting.WHITE));

            // Solo stampa locale: non viene mai inviato al server
            mc.gui.getChat().addMessage(line);
        });
    }
}
