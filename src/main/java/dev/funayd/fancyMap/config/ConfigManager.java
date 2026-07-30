package dev.funayd.fancyMap.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Owns FancyMap configuration registration, validation, reload and persistence. */
public final class ConfigManager {
    private final JavaPlugin plugin;
    private final Map<String, DoubleRule> doubles = new LinkedHashMap<>();
    private final Map<String, IntegerRule> integers = new LinkedHashMap<>();
    private final Map<String, String> strings = new LinkedHashMap<>();
    private final Map<String, List<String>> stringLists = new LinkedHashMap<>();
    private final Map<String, Boolean> booleans = new LinkedHashMap<>();
    private final Map<String, Double> doubleValues = new LinkedHashMap<>();
    private final Map<String, Integer> integerValues = new LinkedHashMap<>();
    private final Map<String, String> stringValues = new LinkedHashMap<>();
    private final Map<String, List<String>> stringListValues = new LinkedHashMap<>();
    private final Map<String, Boolean> booleanValues = new LinkedHashMap<>();
    private boolean dirty;

    /** Creates a configuration registry for one plugin instance. */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Registers a finite numeric value and writes its default when absent or invalid. */
    public void registerDouble(String path, double defaultValue, boolean positive) {
        if (!Double.isFinite(defaultValue) || (positive && defaultValue <= 0.0D)) {
            throw new IllegalArgumentException("Invalid default config value: " + path);
        }
        doubles.put(path, new DoubleRule(defaultValue, positive));
        doubleValues.put(path, readDouble(path, doubles.get(path)));
    }

    /** Registers a positive integer and writes its default when absent or invalid. */
    public void registerInteger(String path, int defaultValue) {
        if (defaultValue <= 0) {
            throw new IllegalArgumentException("Invalid default config value: " + path);
        }
        integers.put(path, new IntegerRule(defaultValue));
        integerValues.put(path, readInteger(path, integers.get(path)));
    }

    /** Registers a text value and writes its default when absent or non-textual. */
    public void registerString(String path, String defaultValue) {
        strings.put(path, defaultValue);
        stringValues.put(path, readString(path, defaultValue));
    }

    /** Registers text lines and writes their defaults when absent or invalid. */
    public void registerStringList(String path, List<String> defaultValue) {
        List<String> safeDefault = List.copyOf(defaultValue);
        stringLists.put(path, safeDefault);
        stringListValues.put(path, readStringList(path, safeDefault));
    }

    /** Registers a boolean value and writes its default when absent or invalid. */
    public void registerBoolean(String path, boolean defaultValue) {
        booleans.put(path, defaultValue);
        booleanValues.put(path, readBoolean(path, defaultValue));
    }

    /** Reloads the Bukkit YAML file and revalidates every registered setting. */
    public void reload() {
        plugin.reloadConfig();
        dirty = false;
        doubles.forEach((path, rule) -> doubleValues.put(path, readDouble(path, rule)));
        integers.forEach((path, rule) -> integerValues.put(path, readInteger(path, rule)));
        strings.forEach((path, value) -> stringValues.put(path, readString(path, value)));
        stringLists.forEach((path, value) -> stringListValues.put(
                path,
                readStringList(path, value)
        ));
        booleans.forEach((path, value) -> booleanValues.put(path, readBoolean(path, value)));
        save();
    }

    /** Persists any registered defaults or validated replacements written during this cycle. */
    public void save() {
        if (dirty) {
            plugin.saveConfig();
            dirty = false;
        }
    }

    /** @return a registered numeric value */
    public double getDouble(String path) {
        return required(doubleValues, path);
    }

    /** @return a registered positive integer value */
    public int getInteger(String path) {
        return required(integerValues, path);
    }

    /** @return a registered text value */
    public String getString(String path) {
        return required(stringValues, path);
    }

    /** @return registered text lines */
    public List<String> getStringList(String path) {
        return required(stringListValues, path);
    }

    /** @return a registered boolean value */
    public boolean getBoolean(String path) {
        return required(booleanValues, path);
    }

    /** Updates one registered numeric setting and persists it immediately. */
    public boolean setDouble(String path, double value) {
        DoubleRule rule = doubles.get(path);
        if (rule == null || !valid(value, rule)) {
            return false;
        }
        doubleValues.put(path, value);
        plugin.getConfig().set(path, value);
        dirty = true;
        save();
        return true;
    }

    /** Updates one registered boolean setting and persists it immediately. */
    public boolean setBoolean(String path, boolean value) {
        if (!booleans.containsKey(path)) {
            return false;
        }
        booleanValues.put(path, value);
        plugin.getConfig().set(path, value);
        dirty = true;
        save();
        return true;
    }

    /** Returns the immediate child keys of a YAML section. */
    public List<String> sectionKeys(String path) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(path);
        return section == null ? List.of() : List.copyOf(section.getKeys(false));
    }

    /** Returns a dynamic feature section, or {@code null} when it has not been created. */
    public ConfigurationSection section(String path) {
        return plugin.getConfig().getConfigurationSection(path);
    }

    /** Updates a dynamic configuration value; callers save after a related batch is complete. */
    public void set(String path, Object value) {
        plugin.getConfig().set(path, value);
        dirty = true;
    }

    /** Returns the logger shared by configuration consumers. */
    public Logger logger() {
        return plugin.getLogger();
    }

    /** Checks whether a raw path is currently present in the Bukkit YAML file. */
    public boolean contains(String path) {
        return plugin.getConfig().contains(path);
    }

    /** Copies a legacy value only when the replacement path has not been configured yet. */
    public void migrate(String legacyPath, String replacementPath) {
        if (!contains(replacementPath) && contains(legacyPath)) {
            plugin.getConfig().set(replacementPath, plugin.getConfig().get(legacyPath));
            dirty = true;
        }
    }

    private double readDouble(String path, DoubleRule rule) {
        Object raw = plugin.getConfig().get(path);
        double value = raw instanceof Number number ? number.doubleValue() : rule.defaultValue();
        if (!valid(value, rule)) {
            value = rule.defaultValue();
        }
        if (!(raw instanceof Number) || Double.compare(value, ((Number) raw).doubleValue()) != 0) {
            plugin.getConfig().set(path, value);
            dirty = true;
        }
        return value;
    }

    private String readString(String path, String defaultValue) {
        Object raw = plugin.getConfig().get(path);
        if (raw instanceof String value && !value.isBlank()) {
            return value;
        }
        plugin.getConfig().set(path, defaultValue);
        dirty = true;
        return defaultValue;
    }

    /** Reads textual YAML lines while preserving deliberately empty lore lists. */
    private List<String> readStringList(String path, List<String> defaultValue) {
        Object raw = plugin.getConfig().get(path);
        if (raw instanceof List<?> rawList) {
            List<String> values = new ArrayList<>(rawList.size());
            for (Object entry : rawList) {
                if (!(entry instanceof String value)) {
                    plugin.getConfig().set(path, defaultValue);
                    dirty = true;
                    return defaultValue;
                }
                values.add(value);
            }
            return List.copyOf(values);
        }
        plugin.getConfig().set(path, defaultValue);
        dirty = true;
        return defaultValue;
    }

    /** Reads one strictly positive integer and replaces invalid YAML values. */
    private int readInteger(String path, IntegerRule rule) {
        Object raw = plugin.getConfig().get(path);
        int value = raw instanceof Number number ? number.intValue() : rule.defaultValue();
        if (!(raw instanceof Number) || value <= 0
                || ((Number) raw).doubleValue() != value) {
            value = rule.defaultValue();
            plugin.getConfig().set(path, value);
            dirty = true;
        }
        return value;
    }

    private boolean readBoolean(String path, boolean defaultValue) {
        Object raw = plugin.getConfig().get(path);
        if (raw instanceof Boolean value) {
            return value;
        }
        plugin.getConfig().set(path, defaultValue);
        dirty = true;
        return defaultValue;
    }

    private boolean valid(double value, DoubleRule rule) {
        return Double.isFinite(value) && (!rule.positive() || value > 0.0D);
    }

    private <T> T required(Map<String, T> values, String path) {
        T value = values.get(path);
        if (value == null) {
            throw new IllegalArgumentException("Unregistered config path: " + path);
        }
        return value;
    }

    private record DoubleRule(double defaultValue, boolean positive) {
    }

    private record IntegerRule(int defaultValue) {
    }
}
