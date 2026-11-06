package com.nordstrom.uthernet.slot;

import com.nordstrom.emulator.MemoryBus;
import com.nordstrom.emulator.cpu.Cpu6502;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs real Apple II network software on the card: IP65's own Uthernet II
 * driver and TCP/IP stack (src/test/asm/ip65test.s, linked with IP65 into
 * src/test/resources/ip65test.bin), on ReplicApple2Plus's 6502. The driver
 * opens socket 0 in MACRAW mode, so this exercises the whole frame path:
 * DHCP from the gateway, a name lookup through the name server, and a TCP
 * connection through the gateway to an echo server on the host.
 * <p>
 * The machine is just RAM, the card in slot 3, and a stand-in ROM that is
 * all RTS except the Monitor's WAIT at $FCA8, which IP65's Apple II timer
 * calls. It runs paced to the 6502's real speed, as ReplicApple2Plus does,
 * so IP65's timeouts mean what they would on a real Apple.
 */
class Ip65Test {

    private static final int SLOT = 3;
    private static final int LOAD = 0x0800;
    private static final int STAGE = 0x0303;
    private static final double CYCLES_PER_MILLISECOND = 1_020.5;

    /** The Monitor's WAIT delay loop, which IP65's timer_read calls. */
    private static final int[] WAIT = {
        0x38, 0x48, 0xE9, 0x01, 0xD0, 0xFC, 0x68, 0xE9, 0x01, 0xD0, 0xF6, 0x60
    };

    private final int[] ram = new int[0xC000];
    private final Uthernet2 card = new Uthernet2();

    @Test
    void ip65LeasesResolvesConnectsAndEchoes() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread echo = new Thread(() -> echoStream(server));
            echo.setDaemon(true);
            echo.start();

            byte[] program;
            try (InputStream in = getClass().getResourceAsStream("/ip65test.bin")) {
                assertNotNull(in, "ip65test.bin");
                program = in.readAllBytes();
            }
            for (int i = 0; i < program.length; i++) {
                ram[LOAD + i] = program[i] & 0xFF;
            }
            ram[0x0300] = SLOT;
            ram[0x0301] = server.getLocalPort() & 0xFF;
            ram[0x0302] = server.getLocalPort() >> 8;
            card.configure(new Properties());
            try {
                run(new Cpu6502(new Bus(), 0xFFFC), 60_000);
            } finally {
                card.release();
            }

            assertEquals(0xFF, ram[STAGE], "reached the end (stage " + ram[STAGE] + " otherwise)");
            assertEquals("10.0.2.15", dotted(0x0310), "leased over DHCP");
            assertEquals("127.0.0.1", dotted(0x0314), "localhost, from the host's resolver");
            StringBuilder echoed = new StringBuilder();
            for (int a = 0x0320; ram[a] != 0; a++) {
                echoed.append((char) ram[a]);
            }
            assertEquals("HELLO FROM THE APPLE II+", echoed.toString());
        }
    }

    /** Runs until the program reaches its last stage or the wall-clock limit passes, paced to real time. */
    private void run(Cpu6502 cpu, long limitMillis) throws InterruptedException {
        long start = System.nanoTime();
        long cycles = 0;
        while (ram[STAGE] != 0xFF) {
            int c = cpu.step();
            if (c == 0) {
                throw new IllegalStateException("6502 halted");
            }
            cycles += c;
            card.tick(c);
            if ((cycles & 0x3FFF) < c) {
                long elapsed = (System.nanoTime() - start) / 1_000_000;
                long ahead = (long) (cycles / CYCLES_PER_MILLISECOND) - elapsed;
                if (ahead > 0) {
                    Thread.sleep(ahead);
                }
                if (elapsed > limitMillis) {
                    return;
                }
            }
        }
    }

    private String dotted(int address) {
        return ram[address] + "." + ram[address + 1] + "." + ram[address + 2] + "." + ram[address + 3];
    }

    private final class Bus implements MemoryBus {

        @Override
        public int read(int address) {
            if (address < 0xC000) {
                return ram[address];
            }
            if (address >= 0xC080 + SLOT * 16 && address < 0xC090 + SLOT * 16) {
                return card.readIoSwitch(address & 0x0F);
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
            return address >= 0xD000 ? 0x60 : 0x00; // RTS through the ROM; nothing else is driven
        }

        @Override
        public void write(int address, int value) {
            if (address < 0xC000) {
                ram[address] = value & 0xFF;
            } else if (address >= 0xC080 + SLOT * 16 && address < 0xC090 + SLOT * 16) {
                card.writeIoSwitch(address & 0x0F, value);
            }
        }
    }

    private static void echoStream(ServerSocket server) {
        try (Socket s = server.accept()) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
        } catch (IOException e) {
            // the test fails on what the Apple saw
        }
    }
}
