package dev.funayd.fancyMap.lockview;

import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerInput;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerRotation;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSteerVehicle;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import dev.funayd.fancyMap.input.MovementInput;
import dev.funayd.fancyMap.input.NormalizedInput;
import org.bukkit.entity.Player;

/**
 * Packet boundary for input received while a player is locked.
 */
final class LockViewPacketListener implements PacketListener {
    private final LockViewController controller;

    /**
     * Creates a packet listener backed by the lock controller.
     *
     * @param controller lock controller
     */
    LockViewPacketListener(LockViewController controller) {
        this.controller = controller;
    }

    /**
     * Captures movement, rotation and zoom packets and blocks server movement.
     *
     * @param event received PacketEvents event
     */
    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }
        LockViewState state = controller.stateFor(player);
        if (state == null) {
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.STEER_VEHICLE) {
            WrapperPlayClientSteerVehicle packet =
                    new WrapperPlayClientSteerVehicle(event);
            MovementInput input = MovementInput.from(
                    packet.getSideways(),
                    packet.getForward(),
                    packet.isJump(),
                    packet.isUnmount()
            );
            controller.handleInput(player, state, NormalizedInput.movement(input));
            if (packet.isUnmount()) {
                packet.setUnmount(false);
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_INPUT) {
            WrapperPlayClientPlayerInput packet =
                    new WrapperPlayClientPlayerInput(event);
            MovementInput input = new MovementInput(
                    packet.isForward(),
                    packet.isBackward(),
                    packet.isLeft(),
                    packet.isRight(),
                    packet.isJump(),
                    packet.isShift()
            );
            controller.handleInput(player, state, NormalizedInput.movement(input));
            if (packet.isShift()) {
                packet.setShift(false);
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            WrapperPlayClientHeldItemChange packet =
                    new WrapperPlayClientHeldItemChange(event);
            int currentSlot = packet.getSlot();
            int previousSlot = state.lastHotbarSlot;
            int delta = hotbarDelta(previousSlot, currentSlot);
            state.lastHotbarSlot = previousSlot;
            if (delta != 0) {
                controller.handleInput(player, state, NormalizedInput.scroll(delta));
            }
            event.setCancelled(true);
            controller.restoreHotbar(player, state, previousSlot);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging packet =
                    new WrapperPlayClientPlayerDigging(event);
            if (packet.getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                event.setCancelled(true);
                controller.handleInput(player, state, NormalizedInput.press(NormalizedInput.Key.F));
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
            WrapperPlayClientPlayerRotation packet =
                    new WrapperPlayClientPlayerRotation(event);
            packet.setYaw(state.lockedYaw);
            packet.setPitch(state.lockedPitch);
            event.markForReEncode(true);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION
                || event.getPacketType() == PacketType.Play.Client.PLAYER_POSITION) {
            event.setCancelled(true);
        }
    }

    /** Calculates the shortest signed hotbar movement between two slots. */
    private int hotbarDelta(int previousSlot, int currentSlot) {
        int delta = currentSlot - previousSlot;
        if (delta > 4) {
            return delta - 9;
        }
        if (delta < -4) {
            return delta + 9;
        }
        return delta;
    }
}
