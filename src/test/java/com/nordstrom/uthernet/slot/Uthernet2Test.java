package com.nordstrom.uthernet.slot;

import com.nordstrom.emulator.InterruptLines;
import com.nordstrom.emulator.system.MotherboardBus;
import com.nordstrom.emulator.system.SlotCard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the card through its I/O switches the way 6502 software does,
 * with the chip's sockets in TCP and UDP mode reaching real servers on the
 * host's loopback interface through the gateway address.
 */
class Uthernet2Test {

    private static final int MODE = 4;
    private static final int ADDRESS_HIGH = 5;
    private static final int ADDRESS_LOW = 6;
    private static final int DATA = 7;

    private static final int S1 = 0x0500;
    private static final int GATEWAY = 0x0A000202;
    private static final int NAME_SERVER = 0x0A000203;

    private Uthernet2 card;

    @BeforeEach
    void plugIn() {
        card = new Uthernet2();
        card.configure(new Properties());
        card.writeIoSwitch(MODE, 0x03); // indirect, auto-increment
    }

    @AfterEach
    void unplug() {
        card.release();
    }

    @Test
    void identifiesItselfAndHasNoRom() {
        assertEquals("uthernet2", card.getShortName());
        assertFalse(card.hasRom());
        assertTrue(card.getParameters().isEmpty());
    }

    @Test
    void onlyTheTwoLowAddressLinesAreDecoded() {
        card.writeIoSwitch(ADDRESS_HIGH - 4, 0x00); // $C0n1 is IDM_AR0, same as $C0n5
        card.writeIoSwitch(ADDRESS_LOW + 8, 0x17);  // $C0nE is IDM_AR1
        assertEquals(0x07, card.readIoSwitch(DATA + 4)); // RTR0, read through $C0nB
        assertEquals(0xD0, card.readIoSwitch(DATA));
    }

    @Test
    void passesTheManualsInstallationTest() {
        // Uthernet II manual, p. 5: C0X4:80, C0X4:03, then C0X4 reads 03
        card.writeIoSwitch(MODE, 0x80);
        card.writeIoSwitch(MODE, 0x03);
        assertEquals(0x03, card.readIoSwitch(MODE));
    }

    @Test
    void answersTheManualsProbe() {
        // Uthernet II manual, p. 38: MR reads $00 after a reset, then reads back $03
        card.writeIoSwitch(MODE, 0x80);
        assertEquals(0x00, card.readIoSwitch(MODE));
        card.writeIoSwitch(MODE, 0x03);
        assertEquals(0x03, card.readIoSwitch(MODE));
    }

    @Test
    void drivesIrqFromTheChipsInterrupt() {
        int[] level = {0};
        card.connectInterruptLines(new InterruptLines() {
            @Override
            public void raiseIrq() {
                level[0]++;
            }

            @Override
            public void lowerIrq() {
                level[0]--;
            }

            @Override
            public void raiseNmi() {
                throw new AssertionError("the card has no NMI connection");
            }

            @Override
            public void lowerNmi() {
                throw new AssertionError("the card has no NMI connection");
            }
        });
        poke(0x0016, 0xEF);                     // IMR: everything, as the manual (p. 44) suggests
        poke(S1, 0x02);                         // UDP
        poke(S1 + 1, 0x01);                     // OPEN
        pokeIp(S1 + 0x0C, NAME_SERVER);
        poke16(S1 + 0x10, 9);
        poke16(S1 + 0x24, 1);
        poke(S1 + 1, 0x20);                     // SEND: SEND_OK
        assertEquals(1, level[0], "/IRQ asserted");
        assertEquals(0x02, peek(0x0015), "IR shows socket 1");
        poke(S1 + 2, 0x10);                     // clear SEND_OK
        assertEquals(0, level[0], "/IRQ released");
        poke(0x0016, 0x00);
    }

    @Test
    void theSlotsResetLineResetsTheChip() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        poke(S1, 0x01);
        poke16(S1 + 0x04, port);
        poke(S1 + 1, 0x01);
        poke(S1 + 1, 0x02);                     // LISTEN
        setAddress(0x1234);
        card.onReset();
        assertEquals(0x00, card.readIoSwitch(MODE));
        card.writeIoSwitch(MODE, 0x03);
        assertEquals(0x00, card.readIoSwitch(ADDRESS_HIGH), "the address pointer is back to 0");
        assertEquals(0x00, peek(S1 + 3), "the socket is closed");
        try (ServerSocket rebound = new ServerSocket(port)) {
            assertEquals(port, rebound.getLocalPort(), "the host port was released");
        }
    }

    @Test
    void aRebootedMachinesCardCanListenWhereTheOldOneDid() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        poke(S1, 0x01);
        poke16(S1 + 0x04, port);
        poke(S1 + 1, 0x01);
        poke(S1 + 1, 0x02);                     // LISTEN on the old machine
        SlotCard[] slots = new SlotCard[8];
        slots[3] = card;
        try (MotherboardBus oldBus = MotherboardBus.withoutAudio(slots)) {
            oldBus.releaseCards();              // what Reboot does to the old machine
        }

        card = new Uthernet2();                 // the fresh machine's card; unplug() releases it
        card.configure(new Properties());
        card.writeIoSwitch(MODE, 0x03);
        poke(S1, 0x01);
        poke16(S1 + 0x04, port);
        poke(S1 + 1, 0x01);
        poke(S1 + 1, 0x02);
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
            client.getOutputStream().write('B');
            await(() -> peek16(S1 + 0x26) == 1);
            assertEquals('B', peek(0x6800), "the fresh card took the connection");
        }
    }

    @Test
    void tcpModeConnectsThroughTheGatewayToTheHost() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread echo = new Thread(() -> echoStream(server));
            echo.start();
            poke(S1, 0x01);                         // Sn_MR: TCP
            poke(S1 + 1, 0x01);                     // OPEN
            assertEquals(0x13, peek(S1 + 3));
            pokeIp(S1 + 0x0C, GATEWAY);
            poke16(S1 + 0x10, server.getLocalPort());
            poke(S1 + 1, 0x04);                     // CONNECT
            await(() -> peek(S1 + 3) == 0x17);

            byte[] hello = "hello".getBytes(StandardCharsets.US_ASCII);
            int txBase = 0x4800;                    // socket 1, TMSR $55
            for (int i = 0; i < hello.length; i++) {
                poke(txBase + i, hello[i]);
            }
            poke16(S1 + 0x24, hello.length);
            poke(S1 + 1, 0x20);                     // SEND
            await(() -> peek16(S1 + 0x26) == hello.length);
            StringBuilder received = new StringBuilder();
            for (int i = 0; i < hello.length; i++) {
                received.append((char) peek(0x6800 + i));
            }
            assertEquals("hello", received.toString());

            poke(S1 + 1, 0x08);                     // DISCON
            await(() -> peek(S1 + 3) == 0x00);
            echo.join(5000);
        }
    }

    @Test
    void udpModeLooksNamesUpAtTheNameServer() {
        poke(S1, 0x02);                             // Sn_MR: UDP
        poke(S1 + 1, 0x01);
        assertEquals(0x22, peek(S1 + 3));
        byte[] query = {0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
            9, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', 0, 0, 1, 0, 1};
        for (int i = 0; i < query.length; i++) {
            poke(0x4800 + i, query[i]);
        }
        pokeIp(S1 + 0x0C, NAME_SERVER);
        poke16(S1 + 0x10, 53);
        poke16(S1 + 0x24, query.length);
        poke(S1 + 1, 0x20);
        await(() -> peek16(S1 + 0x26) != 0);
        int[] header = new int[8];
        for (int i = 0; i < 8; i++) {
            header[i] = peek(0x6800 + i);
        }
        assertEquals(10, header[0]);
        assertEquals(3, header[3]);
        assertEquals(53, (header[4] << 8) | header[5]);
        int answerAddress = 0x6800 + 8 + query.length + 12;
        assertEquals(127, peek(answerAddress));
        assertEquals(1, peek(answerAddress + 3));
    }

    @Test
    void tcpModeListensOnTheHost() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        poke(S1, 0x01);
        poke16(S1 + 0x04, port);
        poke(S1 + 1, 0x01);
        poke(S1 + 1, 0x02);                         // LISTEN
        assertEquals(0x14, peek(S1 + 3));
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), port)) {
            client.getOutputStream().write('A');
            await(() -> peek16(S1 + 0x26) == 1);
            assertEquals(0x17, peek(S1 + 3));
            assertEquals(GATEWAY >>> 24, peek(S1 + 0x0C), "a loopback peer shows up as the gateway");
            assertEquals('A', peek(0x6800));
        }
    }

    // ---------------- helpers ----------------

    private void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            card.tick(1000);
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void setAddress(int address) {
        card.writeIoSwitch(ADDRESS_HIGH, address >> 8);
        card.writeIoSwitch(ADDRESS_LOW, address & 0xFF);
    }

    private int peek(int address) {
        setAddress(address);
        return card.readIoSwitch(DATA);
    }

    private int peek16(int address) {
        setAddress(address);
        return (card.readIoSwitch(DATA) << 8) | card.readIoSwitch(DATA);
    }

    private void poke(int address, int value) {
        setAddress(address);
        card.writeIoSwitch(DATA, value & 0xFF);
    }

    private void poke16(int address, int value) {
        setAddress(address);
        card.writeIoSwitch(DATA, value >> 8);
        card.writeIoSwitch(DATA, value & 0xFF);
    }

    private void pokeIp(int address, int ip) {
        setAddress(address);
        for (int shift = 24; shift >= 0; shift -= 8) {
            card.writeIoSwitch(DATA, (ip >> shift) & 0xFF);
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
            // the test fails on what the guest saw
        }
    }
}
