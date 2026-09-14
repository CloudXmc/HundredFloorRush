package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class ConfigurationManager {

    private final JavaPlugin plugin;
    /** Game and message configuration are one immutable publication unit. */
    private final AtomicReference<LoadedConfiguration> current = new AtomicReference<>();

    public ConfigurationManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void initialize() {
        try {
            current.set(loadAll());
        } catch (Exception exception) {
            throw new ConfigValidationException("无法载入配置：" + exception.getMessage(), exception);
        }
    }

    public synchronized ReloadResult reload() {
        try {
            LoadedConfiguration loaded = loadAll();
            LoadedConfiguration previousSnapshot = current.get();
            GameConfig previous = previousSnapshot == null ? null : previousSnapshot.gameConfig();
            boolean identityChanged = previous != null
                    && (previous.identityMode() != loaded.gameConfig().identityMode()
                    || previous.offlineNameIgnoreCase() != loaded.gameConfig().offlineNameIgnoreCase());
            // Publish both files together. Readers never observe a new game
            // config paired with an old messages snapshot during reload.
            current.set(loaded);
            return ReloadResult.success(identityChanged);
        } catch (Exception exception) {
            return ReloadResult.failure(Objects.toString(exception.getMessage(), exception.getClass().getSimpleName()));
        }
    }

    public GameConfig game() {
        LoadedConfiguration snapshot = current.get();
        return snapshot == null ? null : snapshot.gameConfig();
    }

    public YamlConfiguration messages() {
        LoadedConfiguration snapshot = current.get();
        return snapshot == null ? null : snapshot.messages();
    }

    public LuckPermsConfig luckPerms() {
        LoadedConfiguration snapshot = current.get();
        return snapshot == null ? LuckPermsConfig.parse(null) : snapshot.luckPerms();
    }

    private LoadedConfiguration loadAll() throws Exception {
        Files.createDirectories(plugin.getDataFolder().toPath());
        YamlConfiguration configYaml = loadAndComplete("config.yml");
        GameConfig parsedGame = GameConfig.parse(configYaml);
        YamlConfiguration parsedMessages = loadAndComplete("messages.yml");
        if (!parsedMessages.isString("prefix") || !parsedMessages.isConfigurationSection("help")) {
            throw new ConfigValidationException("messages.yml 缺少 prefix 或 help 节点");
        }
        return new LoadedConfiguration(parsedGame, parsedMessages, LuckPermsConfig.parse(configYaml));
    }

    private YamlConfiguration loadAndComplete(String resourceName) throws Exception {
        Path path = plugin.getDataFolder().toPath().resolve(resourceName);
        if (!Files.exists(path)) {
            plugin.saveResource(resourceName, false);
        }
        YamlConfiguration current = YamlFiles.load(path);
        YamlConfiguration defaults;
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(plugin.getResource(resourceName)), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        if (YamlFiles.mergeMissing(current, defaults)) {
            YamlFiles.saveAtomically(current, path);
        }
        return current;
    }

    private record LoadedConfiguration(GameConfig gameConfig, YamlConfiguration messages,
                                       LuckPermsConfig luckPerms) {
    }
}
