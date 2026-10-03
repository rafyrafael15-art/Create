package com.example.discordbridge;

import com.example.discordbridge.commands.DiscordCommand;
import com.example.discordbridge.config.BridgeConfig;
import com.example.discordbridge.modules.DiscordBridge;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;

public class DiscordBridgeAddon extends MeteorAddon {
    public static final Logger LOG = LoggerFactory.getLogger("Discord Bridge");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Discord Bridge");

        // Permette a Orbit di trovare i metodi @EventHandler del package
        MeteorClient.EVENT_BUS.registerLambdaFactory(getPackage(),
            (lookupInMethod, klass) -> (MethodHandles.Lookup) lookupInMethod.invoke(null, klass, MethodHandles.lookup()));

        BridgeConfig.get(); // carica il file di configurazione

        Modules.get().add(new DiscordBridge());
        Commands.add(new DiscordCommand());
    }

    @Override
    public String getPackage() {
        return "com.example.discordbridge";
    }
}
