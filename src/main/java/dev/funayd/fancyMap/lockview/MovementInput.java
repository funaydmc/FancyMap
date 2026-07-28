package dev.funayd.fancyMap.lockview;

/**
 * Normalized movement/button state received from a locked player.
 *
 * @param forward whether W/forward is held
 * @param backward whether S/backward is held
 * @param left whether A/left is held
 * @param right whether D/right is held
 * @param jump whether Space/jump is held
 * @param shift whether Shift/sneak is held
 */
record MovementInput(
        boolean forward,
        boolean backward,
        boolean left,
        boolean right,
        boolean jump,
        boolean shift
) {
    /**
     * Converts vehicle steering axes into button state.
     *
     * @param sideways sideways steering axis
     * @param forward forward steering axis
     * @param jump jump input
     * @param shift dismount/sneak input
     * @return normalized movement state
     */
    static MovementInput from(float sideways, float forward, boolean jump, boolean shift) {
        return new MovementInput(
                forward > 0.01F,
                forward < -0.01F,
                sideways > 0.01F,
                sideways < -0.01F,
                jump,
                shift
        );
    }

    /**
     * Checks whether no movement or button is currently held.
     *
     * @return true when idle
     */
    boolean isIdle() {
        return !forward && !backward && !left && !right && !jump && !shift;
    }

    /**
     * Formats the held buttons for debug output.
     *
     * @return compact button description
     */
    String describe() {
        StringBuilder result = new StringBuilder();
        append(result, forward, "W");
        append(result, backward, "S");
        append(result, left, "A");
        append(result, right, "D");
        append(result, jump, "Space");
        append(result, shift, "Shift");
        return result.toString();
    }

    /** Appends one active button name to a comma-separated description. */
    private static void append(StringBuilder result, boolean active, String name) {
        if (active) {
            if (result.length() > 0) {
                result.append(", ");
            }
            result.append(name);
        }
    }
}
