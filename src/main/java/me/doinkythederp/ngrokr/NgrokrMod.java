package me.doinkythederp.ngrokr;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.text.Text;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.github.alexdlaird.ngrok.NgrokClient;
import com.github.alexdlaird.ngrok.conf.JavaNgrokConfig;
import com.github.alexdlaird.ngrok.installer.NgrokInstaller;
import com.github.alexdlaird.ngrok.protocol.CreateTunnel;
import com.github.alexdlaird.ngrok.protocol.Proto;
import com.github.alexdlaird.ngrok.protocol.Tunnel;

public class NgrokrMod implements ModInitializer {
    // This logger is used to write text to the console and the log file.
    // It is considered best practice to use your mod id as the logger's name.
    // That way, it's clear which mod wrote info, warnings, and errors.
    public static final Logger LOGGER = LoggerFactory.getLogger("ngrokr");

    public static final Text checkboxMessage = Text.translatable("lanServer.publish");
    public static Optional<CheckboxWidget> publishCheckbox = Optional.empty();

    public static boolean ngrokInstalled = false;
    private static Path ngrokPath;
    private static Path configPath;

    private static Optional<NgrokClient> ngrokClient = Optional.empty();
    private static Optional<Integer> ngrokPort = Optional.empty();
    private static Optional<String> ngrokToken = Optional.empty();

    @Override
    public void onInitialize() {
        // This code runs as soon as Minecraft is in a mod-load-ready state.
        // However, some things (like resources) may still be uninitialized.
        // Proceed with mild caution.

        ngrokPath = FabricLoader.getInstance().getGameDir().resolve("ngrokr").resolve("ngrok");
        configPath = FabricLoader.getInstance().getConfigDir().resolve("NgrokrAuthToken.txt");

        if (ngrokPath.toFile().exists()) {
            ngrokInstalled = true;
            LOGGER.info("Ngrok is already installed.", ngrokPath);
            startNgrokClient();
        } else {
            LOGGER.info("Installing ngrok to `{}`…", ngrokPath);
            new Thread(() -> {
                var ngrokInstaller = new NgrokInstaller();
                try {
                    ngrokInstaller.installNgrok(ngrokPath);
                    ngrokInstalled = true;
                    LOGGER.info("Finished installing ngrok.");
                    startNgrokClient();
                } catch (Exception e) {
                    LOGGER.error("Failed to install ngrok: {}", e.getMessage());
                }
            }).start();
        }
    }

    private static Optional<String> loadToken() {
        String relativeConfigPathString = FabricLoader.getInstance().getGameDir().relativize(configPath).toString();
        if (configPath.toFile().exists()) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(configPath.toString()));
                try {
                    LOGGER.debug("Loaded token from config file @ {}", relativeConfigPathString);
                    return Optional.of(br.readLine());
                } finally {
                    br.close();
                }
            } catch (Exception e) {
                LOGGER.error("Failed to read config file: {}", e.getMessage());
                return Optional.empty();
            }
        }
        LOGGER.warn("Config file not present @ {}", relativeConfigPathString);
        return Optional.empty();
    }

    public static Optional<String> getNgrokToken() {
        return ngrokToken;
    }

    public static void setNgrokToken(String ngrokToken) {
        try {
            var writer = new FileWriter(configPath.toString());
            try {
                writer.write(ngrokToken);
                writer.write(System.lineSeparator());
            } finally {
                writer.close();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to write config file: {}", e.getMessage());
        }
        NgrokrMod.ngrokToken = Optional.of(ngrokToken);
        startNgrokClient();
    }

    private static void startNgrokClient() {
        loadToken();
        if (!ngrokToken.isPresent()) {
            LOGGER.debug("No ngrok token found, publishing LAN servers is not available.");
            return;
        }
        if (ngrokClient.isPresent()) {
            LOGGER.debug("Stopping ngrok client!");
            disconnectTunnel();
            ngrokClient.get().kill();
        }

        var config = new JavaNgrokConfig.Builder()
                .withAuthToken(ngrokToken.get())
                .withNgrokPath(ngrokPath)
                .build();
        ngrokClient = Optional.of(new NgrokClient.Builder()
                .withJavaNgrokConfig(config)
                .build());

        if (ngrokPort.isPresent()) {
            int port = ngrokPort.get();
            ngrokPort = Optional.empty();
            publishPort(port);
        }
    }

    public static Tunnel publishPort(int port) throws IllegalStateException {
        if (ngrokPort.isPresent()) {
            throw new IllegalStateException("Already publishing to port " + ngrokPort.get());
        }
        if (!ngrokClient.isPresent()) {
            throw new IllegalStateException("Auth token not available");
        }
        ngrokPort = Optional.of(port);
        var tunnel = new CreateTunnel.Builder()
                .withProto(Proto.TCP)
                .withAddr(port)
                .build();
        return ngrokClient.get().connect(tunnel);
    }

    public static void disconnectTunnel() {
        ngrokClient.get().getTunnels().forEach(tunnel -> {
            LOGGER.debug("Disconnecting tunnel {}", tunnel.getName());
            ngrokClient.get().disconnect(tunnel.getPublicUrl());
        });
        ngrokPort = Optional.empty();
    }

    /**
     * Checks if the LAN server should be published using ngrok after
     * <code>MinecraftServer.openToLan</code> is called.
     *
     * @return Returns true if the publish checkbox was present and checked.
     */
    public static boolean lanServersShouldPublish() {
        return NgrokrMod.publishCheckbox.isPresent() && NgrokrMod.publishCheckbox.get().isChecked();
    }
}
