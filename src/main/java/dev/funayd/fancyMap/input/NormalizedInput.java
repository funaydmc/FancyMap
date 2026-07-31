package dev.funayd.fancyMap.input;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** Canonical input event used by map navigation and configured key bindings. */
public record NormalizedInput(
        MovementInput movement,
        Set<Key> heldKeys,
        Key instantKey,
        int scrollDelta
) {
    /** Converts a complete movement packet into one held-key state. */
    public static NormalizedInput movement(MovementInput movement) {
        EnumSet<Key> keys = EnumSet.noneOf(Key.class);
        for (Key key : Key.values()) {
            if (key.pressed(movement)) {
                keys.add(key);
            }
        }
        return new NormalizedInput(movement, Set.copyOf(keys), null, 0);
    }

    /** Converts an instant client key action, such as swapping the off-hand item. */
    public static NormalizedInput press(Key key) {
        return new NormalizedInput(null, Set.of(), key, 0);
    }

    /** Converts one hotbar-wheel delta. */
    public static NormalizedInput scroll(int delta) {
        return new NormalizedInput(null, Set.of(), null, delta);
    }

    /** Supported logical input keys and their configuration aliases. */
    public enum Key {
        FORWARD("forward", "w"),
        BACKWARD("backward", "s"),
        LEFT("left", "a"),
        RIGHT("right", "d"),
        SPACE("space", "jump"),
        SHIFT("shift", "sneak"),
        F("f", "swap-offhand");

        private final String[] names;

        Key(String... names) {
            this.names = names;
        }

        /** Parses a configured key name. */
        public static Key from(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);
            for (Key key : values()) {
                for (String alias : key.names) {
                    if (alias.equals(normalized)) {
                        return key;
                    }
                }
            }
            return null;
        }

        private boolean pressed(MovementInput input) {
            return switch (this) {
                case FORWARD -> input.forward();
                case BACKWARD -> input.backward();
                case LEFT -> input.left();
                case RIGHT -> input.right();
                case SPACE -> input.jump();
                case SHIFT -> input.shift();
                case F -> false;
            };
        }
    }
}
