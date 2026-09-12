package cn.mcxyd.hundredfloor.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

public final class YamlFiles {

    private YamlFiles() {
    }

    public static YamlConfiguration load(Path path) throws IOException, InvalidConfigurationException {
        Objects.requireNonNull(path, "path");
        YamlConfiguration configuration = new YamlConfiguration();
        if (Files.exists(path)) {
            configuration.loadFromString(Files.readString(path, StandardCharsets.UTF_8));
        }
        return configuration;
    }

    public static boolean mergeMissing(YamlConfiguration target, YamlConfiguration defaults) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(defaults, "defaults");
        validateExistingTypes(target, defaults);
        boolean changed = false;
        for (String path : defaults.getKeys(true)) {
            if (!target.contains(path, true)) {
                // Do not copy MemorySection objects from the defaults tree:
                // they retain their original parent and can make a completed
                // configuration share mutable state with the resource tree.
                // Setting leaves lets Bukkit create the required parent
                // sections safely.
                if (!(defaults.get(path) instanceof org.bukkit.configuration.ConfigurationSection)) {
                    target.set(path, defaults.get(path));
                    changed = true;
                }
            }
            if (target.getComments(path).isEmpty() && !defaults.getComments(path).isEmpty()) {
                target.setComments(path, defaults.getComments(path));
                changed = true;
            }
            if (target.getInlineComments(path).isEmpty() && !defaults.getInlineComments(path).isEmpty()) {
                target.setInlineComments(path, defaults.getInlineComments(path));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Rejects an existing value whose shape conflicts with the bundled
     * defaults before completion starts.  Without this check, setting a
     * missing child (for example {@code help.header}) can silently replace a
     * user-provided scalar ({@code help: "..."}) and violate the no-overwrite
     * reload contract.
     */
    private static void validateExistingTypes(YamlConfiguration target, YamlConfiguration defaults) {
        for (String path : defaults.getKeys(true)) {
            if (!target.contains(path, true)) {
                continue;
            }
            Object expected = defaults.get(path);
            Object actual = target.get(path);
            if (!compatible(expected, actual)) {
                throw new ConfigValidationException("配置节点 " + path + " 类型与默认配置不匹配");
            }
        }
    }

    private static boolean compatible(Object expected, Object actual) {
        if (expected == null) {
            return actual == null;
        }
        if (expected instanceof org.bukkit.configuration.ConfigurationSection) {
            return actual instanceof org.bukkit.configuration.ConfigurationSection;
        }
        if (expected instanceof List<?> expectedList) {
            if (!(actual instanceof List<?> actualList)) {
                return false;
            }
            // Lists in the bundled files are either text lore or primitive
            // values.  Validate text lists element-by-element so a malformed
            // lore entry cannot later become an unexpected object.
            if (!expectedList.isEmpty() && expectedList.getFirst() instanceof String) {
                return actualList.stream().allMatch(value -> value == null || value instanceof String);
            }
            return true;
        }
        if (expected instanceof Number) {
            return actual instanceof Number;
        }
        return expected.getClass().isInstance(actual);
    }

    public static void saveAtomically(YamlConfiguration configuration, Path target) throws IOException {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(target, "target");
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, configuration.saveToString(), StandardCharsets.UTF_8);
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            // A failed write/move should not leave a stale completion file that
            // could be mistaken for the active configuration on a later run.
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // Preserve the original write/move result; a leftover .tmp is
                // harmless and will be replaced on the next attempt.
            }
        }
    }
}
