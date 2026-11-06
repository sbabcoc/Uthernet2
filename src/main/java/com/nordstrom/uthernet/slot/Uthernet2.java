package com.nordstrom.uthernet.slot;

import com.nordstrom.emulator.InterruptLines;
import com.nordstrom.emulator.system.CardParameter;
import com.nordstrom.emulator.system.SlotCard;
import com.nordstrom.uthernet.net.HostNetwork;
import com.nordstrom.uthernet.w5100.W5100;

import java.util.List;
import java.util.Properties;

/**
 * ReplicApple2Plus peripheral: the a2RetroSystems Uthernet II, a WIZnet
 * W5100 on the slot's $C0n0-$C0nF I/O switches. Per the card's schematic
 * (Uthernet II manual, pp. 47-48), DEVSEL drives the chip's /CS and only
 * A1-A0 reach its address pins, so every group of four switches addresses
 * its MR, IDM_AR0, IDM_AR1 and IDM_DR alike; software uses $C0n4-$C0n7.
 * The chip's /INT drives the slot's /IRQ through an open-drain buffer, and
 * the slot's /RESET drives the chip's /RESET. The card has no ROM, so its
 * $Cn00 page reads as the floating bus.
 * <p>
 * The chip does its own work between bus accesses -- moving data to and
 * from the network, finishing connections -- every millisecond or so of
 * emulated time, kept by {@link #tick}.
 * <p>
 * {@link #configure} opens nothing on the host: the emulator configures
 * cards it then discards when it checks a configuration, so host sockets
 * are opened only when guest software asks for them.
 */
public final class Uthernet2 implements SlotCard {

    /** The 6502's clock: 14.31818 MHz x 65 / 912, about 1.0205 MHz. */
    private static final long CYCLE_MICROS_NUMERATOR = 912L * 1_000_000L;
    private static final long CYCLE_MICROS_DENOMINATOR = 14_318_180L * 65L;

    /** Cycles between the chip's service passes: about a millisecond. */
    private static final int POLL_CYCLES = 1_000;

    private W5100 chip;
    private InterruptLines interruptLines;
    private boolean irqAsserted;
    private long micros;
    private long microsRemainder;
    private int cyclesSincePoll;

    @Override
    public String getShortName() {
        return "uthernet2";
    }

    @Override
    public String getDisplayName() {
        return "Uthernet II";
    }

    @Override
    public List<CardParameter> getParameters() {
        return List.of();
    }

    @Override
    public void configure(Properties props) {
        chip = new W5100(new HostNetwork());
    }

    @Override
    public void connectInterruptLines(InterruptLines lines) {
        interruptLines = lines;
    }

    @Override
    public int readIoSwitch(int offset) {
        return chip.busRead(offset & 3);
    }

    @Override
    public void writeIoSwitch(int offset, int value) {
        chip.busWrite(offset & 3, value);
        updateIrq();
    }

    @Override
    public void onReset() {
        chip.hardwareReset();
        updateIrq();
    }

    @Override
    public boolean hasRom() {
        return false;
    }

    @Override
    public int readRom(int offset) {
        throw new UnsupportedOperationException("The Uthernet II has no slot ROM");
    }

    /**
     * Keeps the chip's time and gives it a service pass about every millisecond.
     *
     * @param cycles elapsed 6502 cycles
     */
    @Override
    public void tick(int cycles) {
        microsRemainder += cycles * CYCLE_MICROS_NUMERATOR;
        micros += microsRemainder / CYCLE_MICROS_DENOMINATOR;
        microsRemainder %= CYCLE_MICROS_DENOMINATOR;
        cyclesSincePoll += cycles;
        if (cyclesSincePoll >= POLL_CYCLES) {
            cyclesSincePoll = 0;
            chip.poll(micros);
            updateIrq();
        }
    }

    /** Follows the chip's /INT on the slot's /IRQ: asserted while an interrupt IMR enables is pending. */
    private void updateIrq() {
        boolean asserted = chip.isInterruptAsserted();
        if (asserted != irqAsserted && interruptLines != null) {
            if (asserted) {
                interruptLines.raiseIrq();
            } else {
                interruptLines.lowerIrq();
            }
        }
        irqAsserted = asserted;
    }

    /** Releases every host socket and listening port the card holds, for the card that replaces it. */
    @Override
    public void release() {
        chip.close();
    }

    /** @return the chip, for inspection by tests */
    public W5100 chip() {
        return chip;
    }
}
