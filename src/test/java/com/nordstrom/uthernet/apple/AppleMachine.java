package com.nordstrom.uthernet.apple;

import com.nordstrom.emulator.MemoryBus;
import com.nordstrom.emulator.cpu.Cpu6502;
import com.nordstrom.uthernet.slot.Uthernet2;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Properties;
import java.util.function.BooleanSupplier;

/**
 * Just enough Apple II+ to run the utilities in src/apple on
 * ReplicApple2Plus's 6502: 48K of RAM, the keyboard with the shift-key mod
 * on pushbutton 2, the display soft switches, the Uthernet II in slot 1,
 * optionally a {@link VideoTermDouble} in slot 3, and a stand-in ROM that
 * is all RTS except the Monitor's WAIT, which IP65's timer calls. A
 * program leaving through the BASIC warm-start vector at $3D0 stops it.
 * It runs paced to the 6502's real speed, so IP65's timeouts mean what
 * they would on a real Apple.
 */
final class AppleMachine implements MemoryBus {

    static final int LOAD = 0x4000;
    static final int SCREEN = 0x0800;
    static final int HIRES = 0x2000;
    private static final int UTHERNET_SLOT = 1;
    private static final double CYCLES_PER_MILLISECOND = 1_020.5;
    private static final int[] WAIT = {
        0x38, 0x48, 0xE9, 0x01, 0xD0, 0xFC, 0x68, 0xE9, 0x01, 0xD0, 0xF6, 0x60
    };

    final int[] ram = new int[0xC000];
    final Uthernet2 card = new Uthernet2();
    final VideoTermDouble videoTerm;
    private final Deque<int[]> keys = new ArrayDeque<>();
    private int keyLatch;
    private boolean shiftHeld;
    boolean exited;
    boolean text = true;
    boolean hires;
    boolean an0;

    /**
     * @param withVideoTerm put a VideoTerm in slot 3
     * @param program       the program's resource name, loaded at $4000
     */
    AppleMachine(boolean withVideoTerm, String program) throws IOException {
        videoTerm = withVideoTerm ? new VideoTermDouble() : null;
        try (InputStream in = getClass().getResourceAsStream("/" + program)) {
            if (in == null) {
                throw new IOException(program + " is missing");
            }
            byte[] bytes = in.readAllBytes();
            for (int i = 0; i < bytes.length; i++) {
                ram[LOAD + i] = bytes[i] & 0xFF;
            }
        }
        card.configure(new Properties());
    }

    /** Releases the card's host sockets. */
    void close() {
        card.release();
    }

    /**
     * Queues keystrokes as a II+ keyboard sends them: letters uppercase,
     * with Shift reported on the shift-key mod for an uppercase letter.
     * '\r' is RETURN; other control characters go as they are.
     */
    void type(String keystrokes) {
        for (char c : keystrokes.toCharArray()) {
            boolean upper = Character.isUpperCase(c);
            keys.add(new int[] {Character.toUpperCase(c) & 0x7F, upper ? 1 : 0});
        }
    }

    /** Runs until {@code done} holds or the program exits; fails after {@code limitMillis}. */
    void runUntil(BooleanSupplier done, long limitMillis, String what) throws InterruptedException {
        Cpu6502 cpu = cpu();
        long start = System.nanoTime();
        long cycles = 0;
        while (!done.getAsBoolean()) {
            if (exited) {
                throw new AssertionError("the program exited while waiting for " + what);
            }
            for (int i = 0; i < 2000; i++) {
                int c = cpu.step();
                if (c == 0) {
                    throw new IllegalStateException("6502 halted");
                }
                cycles += c;
                card.tick(c);
                if (exited) {
                    break;
                }
            }
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            long ahead = (long) (cycles / CYCLES_PER_MILLISECOND) - elapsed;
            if (ahead > 0) {
                Thread.sleep(ahead);
            }
            if (elapsed > limitMillis) {
                throw new AssertionError("timed out waiting for " + what + "; screen:\n" + screen());
            }
        }
        totalCycles += cycles;
    }

    private Cpu6502 cpu;
    private long totalCycles;

    private Cpu6502 cpu() {
        if (cpu == null) {
            cpu = new Cpu6502(this, 0xFFFC);
        }
        return cpu;
    }

    /** @return row (0-23) of the program's 80x24 screen buffer */
    String row(int row) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < 80; c++) {
            line.append((char) ram[SCREEN + row * 80 + c]);
        }
        return line.toString();
    }

    String screen() {
        StringBuilder all = new StringBuilder();
        for (int r = 0; r < 24; r++) {
            all.append(String.format("%2d|", r)).append(row(r).stripTrailing()).append('\n');
        }
        return all.toString();
    }

    boolean screenContains(String text) {
        for (int r = 0; r < 24; r++) {
            if (row(r).contains(text)) {
                return true;
            }
        }
        return false;
    }

    /** @return the Hi-Res byte for scan line {@code line} (0-7) of text row {@code row}, byte {@code index} */
    int hiresByte(int row, int line, int index) {
        return ram[HIRES + (row % 8) * 0x80 + (row / 8) * 0x28 + line * 0x400 + index];
    }

    /** @return true if any pixel of Soft-70 window column {@code x} (0-69) in text row {@code row} is lit */
    boolean softCellLit(int row, int x) {
        int bit = (4 * x) % 7;
        int index = (4 * x) / 7;
        for (int line = 0; line < 8; line++) {
            int pair = hiresByte(row, line, index) & 0x7F;
            if (index + 1 < 40) {
                pair |= (hiresByte(row, line, index + 1) & 0x7F) << 7;
            }
            if (((pair >> bit) & 0x0F) != 0) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------
    // the bus

    @Override
    public int read(int address) {
        if (address < 0xC000) {
            if (address == 0x03D0) {
                exited = true;
            }
            return ram[address];
        }
        if (address < 0xC100) {
            return readIo(address);
        }
        if (address < 0xD000) {
            return videoTerm == null ? 0 : videoTerm.readSlotSpace(address);
        }
        if (address >= 0xFCA8 && address < 0xFCA8 + WAIT.length) {
            return WAIT[address - 0xFCA8];
        }
        if (address == 0xFFFC) {
            return LOAD & 0xFF;
        }
        if (address == 0xFFFD) {
            return LOAD >> 8;
        }
        return 0x60;
    }

    @Override
    public void write(int address, int value) {
        if (address < 0xC000) {
            ram[address] = value & 0xFF;
        } else if (address < 0xC100) {
            writeIo(address, value);
        } else if (address < 0xD000 && videoTerm != null) {
            videoTerm.writeSlotSpace(address, value);
        }
    }

    private int readIo(int address) {
        switch (address & 0xFFF0) {
            case 0xC000:
                if ((keyLatch & 0x80) == 0 && !keys.isEmpty()) {
                    int[] key = keys.poll();
                    keyLatch = key[0] | 0x80;
                    shiftHeld = key[1] != 0;
                }
                return keyLatch;
            case 0xC010:
                keyLatch &= 0x7F;
                return 0;
            case 0xC050:
                softSwitch(address);
                return 0;
            case 0xC060:
                return address == 0xC063 ? (shiftHeld ? 0x00 : 0x80) : 0;
            default:
                return slotIo(address, -1);
        }
    }

    private void writeIo(int address, int value) {
        switch (address & 0xFFF0) {
            case 0xC010:
                keyLatch &= 0x7F;
                break;
            case 0xC050:
                softSwitch(address);
                break;
            default:
                slotIo(address, value);
                break;
        }
    }

    private void softSwitch(int address) {
        switch (address) {
            case 0xC050 -> text = false;
            case 0xC051 -> text = true;
            case 0xC056 -> hires = false;
            case 0xC057 -> hires = true;
            case 0xC058 -> an0 = false;
            case 0xC059 -> an0 = true;
            default -> { }
        }
    }

    /** A slot's $C0n0-$C0nF: a read when value is -1, else a write. */
    private int slotIo(int address, int value) {
        int slot = ((address >> 4) & 0x0F) - 8;
        int offset = address & 0x0F;
        if (slot == UTHERNET_SLOT) {
            if (value < 0) {
                return card.readIoSwitch(offset);
            }
            card.writeIoSwitch(offset, value);
        } else if (slot == 3 && videoTerm != null) {
            if (value < 0) {
                return videoTerm.readIo(offset);
            }
            videoTerm.writeIo(offset, value);
        }
        return 0;
    }
}
