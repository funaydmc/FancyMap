package dev.funayd.fancyMap.input;

import dev.funayd.fancyMap.config.ConfigManager;
import dev.funayd.fancyMap.input.NormalizedInput.Key;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;

/** Runs configured commands for clean key taps and chords while a map is active. */
public final class KeyActionBindings {
    private static final String CONFIG_PATH = "actions";
    private static final Map<String, String> DEFAULT_ACTIONS = Map.of(
            "shift", "fancymap",
            "space", "fancymap waypoint list",
            "f", "fancymap waypoint tp %fancymap_hovering_waypoint%"
    );

    private final ConfigManager config;
    private final BiConsumer<Player, String> actionExecutor;
    private final ConcurrentMap<UUID, ConcurrentMap<Chord, TapState>> activeTaps =
            new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Set<Key>> pressedInputs = new ConcurrentHashMap<>();
    private volatile List<Binding> actions = List.of();

    /** Creates and loads bindings backed by the shared configuration manager. */
    public KeyActionBindings(ConfigManager config, BiConsumer<Player, String> actionExecutor) {
        this.config = config;
        this.actionExecutor = actionExecutor;
        verifyChordParser();
        reload();
    }

    /** Reloads dynamic {@code actions.<key-or-chord>: <command>} entries from YAML. */
    public void reload() {
        registerDefaults();
        Map<Chord, String> loaded = new LinkedHashMap<>();
        for (String rawKey : config.sectionKeys(CONFIG_PATH)) {
            String path = CONFIG_PATH + "." + rawKey;
            config.registerString(path, "");
            Chord chord = Chord.parse(rawKey);
            String command = config.getString(path).strip();
            if (chord == null || command.isEmpty()) {
                config.logger().warning("Ignoring invalid FancyMap action: " + rawKey);
                continue;
            }
            if (loaded.putIfAbsent(chord, stripSlash(command)) != null) {
                config.logger().warning("Ignoring duplicate FancyMap action: " + rawKey);
            }
        }
        actions = loaded.entrySet().stream()
                .map(entry -> new Binding(entry.getKey(), entry.getValue()))
                .toList();
        activeTaps.clear();
        pressedInputs.clear();
        config.save();
    }

    /** Adds each built-in action only when it is missing from the active YAML file. */
    private void registerDefaults() {
        for (Map.Entry<String, String> action : DEFAULT_ACTIONS.entrySet()) {
            config.registerString(CONFIG_PATH + "." + action.getKey(), action.getValue());
        }
        config.save();
    }

    /** Handles one normalized client input event. */
    public void handle(Player player, NormalizedInput input) {
        if (input.movement() != null) {
            handleHeldKeys(player, input.heldKeys());
        }
        if (input.instantKey() != null) {
            handleInstantKey(player, input.instantKey());
        }
    }

    private void handleHeldKeys(Player player, Set<Key> pressed) {
        ConcurrentMap<Chord, TapState> taps = activeTaps.computeIfAbsent(
                player.getUniqueId(), ignored -> new ConcurrentHashMap<>()
        );
        pressedInputs.put(player.getUniqueId(), pressed);
        List<Binding> completed = new ArrayList<>();

        for (Binding binding : actions) {
            TapState state = taps.get(binding.chord());
            if (state == null) {
                continue;
            }
            if (state == TapState.SUPPRESSED) {
                if (binding.chord().fullyReleased(pressed)) {
                    taps.remove(binding.chord(), state);
                }
                continue;
            }
            if (binding.chord().matches(pressed)) {
                continue;
            }
            if (binding.chord().releasedTo(pressed)) {
                taps.remove(binding.chord(), state);
                completed.add(binding);
            } else {
                taps.replace(binding.chord(), TapState.CLEAN, TapState.SUPPRESSED);
            }
        }

        for (Binding binding : actions) {
            if (!binding.chord().matches(pressed)) {
                continue;
            }
            TapState state = completed.stream().anyMatch(completedBinding ->
                    binding.chord().isProperSubsetOf(completedBinding.chord())
            ) ? TapState.SUPPRESSED : TapState.CLEAN;
            taps.putIfAbsent(binding.chord(), state);
        }

        for (Binding binding : completed) {
            actionExecutor.accept(player, binding.command());
        }
        if (taps.isEmpty()) {
            activeTaps.remove(player.getUniqueId(), taps);
        }
    }

    /** Cancels all in-progress action chords for one player. */
    public void cancel(Player player) {
        activeTaps.remove(player.getUniqueId());
        pressedInputs.remove(player.getUniqueId());
    }

    /** Runs actions matching one instant key plus the last normalized held-key state. */
    private void handleInstantKey(Player player, Key key) {
        Set<Key> pressed = EnumSet.noneOf(Key.class);
        pressed.addAll(pressedInputs.getOrDefault(player.getUniqueId(), Set.of()));
        pressed.add(key);
        Set<Key> trigger = Set.copyOf(pressed);
        for (Binding binding : actions) {
            if (binding.chord().matches(trigger)) {
                actionExecutor.accept(player, binding.command());
            }
        }
    }

    /** Verifies the parser that protects every configured chord at startup. */
    private static void verifyChordParser() {
        Chord chord = Chord.parse("space+w");
        Chord offHand = Chord.parse("f");
        NormalizedInput shifted = NormalizedInput.movement(
                new MovementInput(false, false, false, false, false, true)
        );
        if (chord == null || chord.keys().size() != 2 || offHand == null
                || Chord.parse("space+space") != null
                || !shifted.heldKeys().contains(Key.SHIFT)
                || NormalizedInput.press(Key.F).instantKey() != Key.F) {
            throw new IllegalStateException("Invalid key chord parser");
        }
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    /** Immutable parsed chord; all listed keys must be held and no others may be held. */
    private record Chord(Set<Key> keys) {
        private static Chord parse(String raw) {
            Set<Key> keys = EnumSet.noneOf(Key.class);
            for (String token : raw.split("\\+")) {
                Key key = Key.from(token.trim());
                if (key == null || !keys.add(key)) {
                    return null;
                }
            }
            return keys.isEmpty() ? null : new Chord(Set.copyOf(keys));
        }

        private boolean matches(Set<Key> pressed) {
            return keys.equals(pressed);
        }

        private boolean releasedTo(Set<Key> pressed) {
            return keys.containsAll(pressed);
        }

        private boolean fullyReleased(Set<Key> pressed) {
            return pressed.stream().noneMatch(keys::contains);
        }

        private boolean isProperSubsetOf(Chord other) {
            return keys.size() < other.keys.size() && other.keys.containsAll(keys);
        }
    }

    /** One configured chord and its command. */
    private record Binding(Chord chord, String command) {
    }

    /** State kept until a chord returns to an unmatched input state. */
    private enum TapState {
        CLEAN,
        SUPPRESSED
    }
}
