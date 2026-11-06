package com.nordstrom.uthernet.w5100;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the chip through its four bus addresses, as 6502 software does,
 * against a scripted {@link Network}. Expected values come from the W5100
 * datasheet's register descriptions.
 */
class W5100Test {

    private ScriptedNetwork network;
    private W5100 chip;

    @BeforeEach
    void powerUp() {
        network = new ScriptedNetwork();
        chip = new W5100(network);
        chip.busWrite(0, W5100.MR_IND | W5100.MR_AI);
    }

    // ---------------- bus interface ----------------

    @Test
    void directModeReachesTheFirstFourChipAddresses() {
        W5100 fresh = new W5100(new ScriptedNetwork());
        fresh.busWrite(1, 0xC0); // GAR0, since IND is clear
        fresh.busWrite(3, 0x01); // GAR2
        assertEquals(0xC0, fresh.read(W5100.GAR));
        assertEquals(0x01, fresh.read(W5100.GAR + 2));
        assertEquals(0xC0, fresh.busRead(1));
    }

    @Test
    void resetValuesMatchTheDatasheet() {
        assertEquals(0x07, peek(W5100.RTR));
        assertEquals(0xD0, peek(W5100.RTR + 1));
        assertEquals(0x08, peek(W5100.RCR));
        assertEquals(0x55, peek(W5100.RMSR));
        assertEquals(0x55, peek(W5100.TMSR));
        assertEquals(0x28, peek(W5100.PTIMER));
        for (int s = 0; s < 4; s++) {
            int base = W5100.SOCKET_BASE + s * 0x100;
            assertEquals(0xFF, peek(base + W5100.SN_DHAR));
            assertEquals(0x80, peek(base + W5100.SN_TTL));
            assertEquals(0x0800, peek16(base + W5100.SN_TX_FSR)); // 2 KB each from TMSR $55
        }
    }

    @Test
    void autoIncrementStepsTheAddressAndReadsItBack() {
        setAddress(W5100.SHAR);
        for (int i = 0; i < 6; i++) {
            chip.busWrite(3, 0x10 + i);
        }
        assertEquals(0x00, chip.busRead(1));
        assertEquals(W5100.SHAR + 6, chip.busRead(2));
        assertArrayEquals(new byte[] {0x10, 0x11, 0x12, 0x13, 0x14, 0x15}, chip.macAddress());
    }

    @Test
    void autoIncrementWrapsWithinTransmitAndReceiveMemory() {
        setAddress(0x5FFF);
        chip.busRead(3);
        assertEquals(0x4000, address());
        setAddress(0x7FFF);
        chip.busRead(3);
        assertEquals(0x6000, address());
    }

    @Test
    void autoIncrementWrapsPastFFFFToE000() {
        setAddress(0xFFFF);
        chip.busRead(3);
        assertEquals(0xE000, address(), "Uthernet II manual, p. 13");
        setAddress(0xDFFF);
        chip.busRead(3);
        assertEquals(0xE000, address(), "elsewhere above $7FFF it advances as usual");
    }

    @Test
    void addressesAbove7FFFReachTheChipFromZero() {
        poke(W5100.RMSR, 0x0A);
        assertEquals(0x0A, peek(0x801A), "Uthernet II manual, p. 13: $801A is RMSR");
    }

    @Test
    void aSoftwareResetKeepsTheAddressPointerAndAHardwareResetClearsIt() {
        setAddress(0x1234);
        chip.busWrite(0, W5100.MR_RST);
        chip.busWrite(0, W5100.MR_IND | W5100.MR_AI);
        assertEquals(0x1234, address(), "Uthernet II manual, p. 10");

        int s = socket(0);
        poke(s + W5100.SN_MR, W5100.PROTOCOL_TCP);
        command(s, W5100.CMD_OPEN);
        command(s, W5100.CMD_CONNECT);
        poke(W5100.GAR, 10);
        setAddress(0x1234);
        chip.hardwareReset();
        assertTrue(network.streams.get(0).closed, "the socket's host connection is released");
        assertEquals(0, chip.busRead(0), "MR back to its reset value: direct mode");
        assertEquals(0, chip.busRead(1), "direct mode reads GAR0, reset to 0");
        chip.busWrite(0, W5100.MR_IND | W5100.MR_AI);
        assertEquals(0, address(), "the address pointer is 0 after a hardware reset");
        assertEquals(W5100.SOCK_CLOSED, peek(s + W5100.SN_SR));
    }

    @Test
    void softwareResetRestoresRegistersAndClearsItself() {
        poke(W5100.GAR, 10);
        poke(W5100.RMSR, 0x03);
        chip.busWrite(0, W5100.MR_RST);
        assertEquals(0, chip.busRead(0), "RST clears itself, and IND with it");
        chip.busWrite(0, W5100.MR_IND | W5100.MR_AI);
        assertEquals(0, peek(W5100.GAR));
        assertEquals(0x55, peek(W5100.RMSR));
    }

    @Test
    void aSocketWhoseShareDoesNotFitGetsNoMemory() {
        poke(W5100.TMSR, 0x0A); // 4 KB, 4 KB, then 1 KB and 1 KB that don't fit
        assertEquals(0x1000, peek16(socket(0) + W5100.SN_TX_FSR));
        assertEquals(0x1000, peek16(socket(1) + W5100.SN_TX_FSR));
        assertEquals(0, peek16(socket(2) + W5100.SN_TX_FSR));
        assertEquals(0, peek16(socket(3) + W5100.SN_TX_FSR));
    }

    // ---------------- UDP ----------------

    @Test
    void udpSendsADatagramAndReceivesWithAnEightByteHeader() {
        int s = socket(1);
        poke(s + W5100.SN_MR, W5100.PROTOCOL_UDP);
        command(s, W5100.CMD_OPEN);
        assertEquals(W5100.SOCK_UDP, peek(s + W5100.SN_SR));

        pokeIp(s + W5100.SN_DIPR, 0x0A000203);
        poke16(s + W5100.SN_DPORT, 53);
        writeTx(1, new byte[] {1, 2, 3});
        command(s, W5100.CMD_SEND);
        assertEquals(0, peek(s + W5100.SN_CR), "Sn_CR clears once the command is taken");
        assertEquals(W5100.IR_SEND_OK, peek(s + W5100.SN_IR) & W5100.IR_SEND_OK);
        ScriptedNetwork.Sent sent = network.datagrams.get(0).sent.get(0);
        assertEquals(0x0A000203, sent.ip());
        assertEquals(53, sent.port());
        assertArrayEquals(new byte[] {1, 2, 3}, sent.payload());

        network.datagrams.get(0).inbound.add(new Network.Datagram(0x0A000203, 53, new byte[] {9, 8}));
        chip.poll(0);
        assertEquals(10, peek16(s + W5100.SN_RX_RSR));
        assertTrue((chip.read(W5100.IR) & 0x02) != 0, "IR reports socket 1");
        byte[] received = readRx(1, 10);
        assertArrayEquals(new byte[] {10, 0, 2, 3, 0, 53, 0, 2, 9, 8}, received);

        poke16(s + W5100.SN_RX_RD, 10);
        assertEquals(10, peek16(s + W5100.SN_RX_RSR), "RSR changes only on RECV");
        command(s, W5100.CMD_RECV);
        assertEquals(0, peek16(s + W5100.SN_RX_RSR));
    }

    // ---------------- TCP ----------------

    @Test
    void tcpConnectsTransfersAndCloses() {
        int s = socket(0);
        poke(s + W5100.SN_MR, W5100.PROTOCOL_TCP);
        command(s, W5100.CMD_OPEN);
        assertEquals(W5100.SOCK_INIT, peek(s + W5100.SN_SR));
        pokeIp(s + W5100.SN_DIPR, 0x0A000202);
        poke16(s + W5100.SN_DPORT, 7);
        command(s, W5100.CMD_CONNECT);
        assertEquals(W5100.SOCK_SYNSENT, peek(s + W5100.SN_SR));

        ScriptedNetwork.Stream stream = network.streams.get(0);
        stream.connecting = false;
        chip.poll(0);
        assertEquals(W5100.SOCK_ESTABLISHED, peek(s + W5100.SN_SR));
        assertEquals(W5100.IR_CON, peek(s + W5100.SN_IR) & W5100.IR_CON);

        writeTx(0, "hello".getBytes());
        stream.accept = 2; // the host takes two bytes now, the rest later
        command(s, W5100.CMD_SEND);
        assertEquals(2, peek16(s + W5100.SN_TX_RD));
        assertEquals(0, peek(s + W5100.SN_IR) & W5100.IR_SEND_OK);
        stream.accept = Integer.MAX_VALUE;
        chip.poll(0);
        assertEquals(5, peek16(s + W5100.SN_TX_RD));
        assertEquals("hello", stream.written.toString());
        assertEquals(W5100.IR_SEND_OK, peek(s + W5100.SN_IR) & W5100.IR_SEND_OK);

        stream.toRead.add("world".getBytes());
        chip.poll(0);
        assertEquals(5, peek16(s + W5100.SN_RX_RSR));
        assertEquals("world", new String(readRx(0, 5)));

        stream.eof = true;
        chip.poll(0);
        assertEquals(W5100.SOCK_CLOSE_WAIT, peek(s + W5100.SN_SR));
        assertEquals(W5100.IR_DISCON, peek(s + W5100.SN_IR) & W5100.IR_DISCON);
        command(s, W5100.CMD_DISCON);
        assertEquals(W5100.SOCK_CLOSED, peek(s + W5100.SN_SR));
        assertTrue(stream.closed);
    }

    @Test
    void aFailedConnectionTimesOut() {
        int s = socket(2);
        poke(s + W5100.SN_MR, W5100.PROTOCOL_TCP);
        command(s, W5100.CMD_OPEN);
        command(s, W5100.CMD_CONNECT);
        network.streams.get(0).failed = true;
        chip.poll(0);
        assertEquals(W5100.SOCK_CLOSED, peek(s + W5100.SN_SR));
        assertEquals(W5100.IR_TIMEOUT, peek(s + W5100.SN_IR) & W5100.IR_TIMEOUT);
        poke(s + W5100.SN_IR, W5100.IR_TIMEOUT);
        assertEquals(0, peek(s + W5100.SN_IR), "Sn_IR bits clear by writing 1");
    }

    // ---------------- MACRAW ----------------

    @Test
    void macRawIsOnlyForSocketZero() {
        poke(socket(1) + W5100.SN_MR, W5100.PROTOCOL_MACRAW);
        command(socket(1), W5100.CMD_OPEN);
        assertEquals(W5100.SOCK_CLOSED, peek(socket(1) + W5100.SN_SR));
    }

    @Test
    void macRawSendsFramesAndReceivesThemBehindASizeThatCountsItself() {
        setAddress(W5100.SHAR);
        byte[] mac = {0x00, 0x08, (byte) 0xDC, 1, 2, 3};
        for (byte b : mac) {
            chip.busWrite(3, b);
        }
        int s = socket(0);
        poke(s + W5100.SN_MR, W5100.PROTOCOL_MACRAW | W5100.SN_MR_MF);
        command(s, W5100.CMD_OPEN);
        assertEquals(W5100.SOCK_MACRAW, peek(s + W5100.SN_SR));

        byte[] out = new byte[60];
        out[0] = 7;
        writeTx(0, out);
        command(s, W5100.CMD_SEND);
        assertArrayEquals(out, network.frames.get(0));

        byte[] notForUs = frameTo(new byte[] {2, 2, 2, 2, 2, 2});
        byte[] forUs = frameTo(mac);
        network.inbound.add(notForUs);
        network.inbound.add(forUs);
        chip.poll(0);
        assertTrue(network.inbound.isEmpty());
        assertEquals(forUs.length + 2, peek16(s + W5100.SN_RX_RSR), "MF filtered out the other station's frame");
        byte[] received = readRx(0, forUs.length + 2);
        assertEquals(forUs.length + 2, ((received[0] & 0xFF) << 8) | (received[1] & 0xFF));
        assertArrayEquals(forUs, Arrays.copyOfRange(received, 2, received.length));
    }

    @Test
    void aFrameWaitsUntilTheReceiveBufferHasRoom() {
        poke(W5100.RMSR, 0x00); // 1 KB for socket 0
        int s = socket(0);
        poke(s + W5100.SN_MR, W5100.PROTOCOL_MACRAW);
        command(s, W5100.CMD_OPEN);
        network.inbound.add(new byte[600]);
        network.inbound.add(new byte[600]);
        chip.poll(0);
        assertEquals(602, peek16(s + W5100.SN_RX_RSR));
        assertEquals(1, network.inbound.size(), "the second frame waits");
        poke16(s + W5100.SN_RX_RD, 602);
        command(s, W5100.CMD_RECV);
        chip.poll(0);
        assertEquals(602, peek16(s + W5100.SN_RX_RSR));
        assertTrue(network.inbound.isEmpty());
    }

    // ---------------- helpers ----------------

    private static byte[] frameTo(byte[] mac) {
        byte[] frame = new byte[64];
        System.arraycopy(mac, 0, frame, 0, 6);
        frame[12] = 0x08;
        frame[13] = 0x06;
        return frame;
    }

    private static int socket(int n) {
        return W5100.SOCKET_BASE + n * 0x100;
    }

    private void setAddress(int address) {
        chip.busWrite(1, address >> 8);
        chip.busWrite(2, address & 0xFF);
    }

    private int address() {
        return (chip.busRead(1) << 8) | chip.busRead(2);
    }

    private int peek(int address) {
        setAddress(address);
        return chip.busRead(3);
    }

    private int peek16(int address) {
        setAddress(address);
        return (chip.busRead(3) << 8) | chip.busRead(3);
    }

    private void poke(int address, int value) {
        setAddress(address);
        chip.busWrite(3, value);
    }

    private void poke16(int address, int value) {
        setAddress(address);
        chip.busWrite(3, value >> 8);
        chip.busWrite(3, value & 0xFF);
    }

    private void pokeIp(int address, int ip) {
        setAddress(address);
        for (int shift = 24; shift >= 0; shift -= 8) {
            chip.busWrite(3, (ip >> shift) & 0xFF);
        }
    }

    private void command(int socketBase, int command) {
        poke(socketBase + W5100.SN_CR, command);
    }

    /** Queues bytes at Sn_TX_WR the way the datasheet's send procedure does, socket memory as allocated by $55. */
    private void writeTx(int n, byte[] data) {
        int s = socket(n);
        int size = peek16(s + W5100.SN_TX_FSR) + ((peek16(s + W5100.SN_TX_WR) - peek16(s + W5100.SN_TX_RD)) & 0xFFFF);
        int base = W5100.TX_MEMORY + n * size;
        int wr = peek16(s + W5100.SN_TX_WR);
        for (int i = 0; i < data.length; i++) {
            poke(base + ((wr + i) & (size - 1)), data[i] & 0xFF);
        }
        poke16(s + W5100.SN_TX_WR, (wr + data.length) & 0xFFFF);
    }

    private byte[] readRx(int n, int length) {
        int size = 0x400 << ((peek(W5100.RMSR) >> (n * 2)) & 3);
        int base = W5100.RX_MEMORY;
        for (int i = 0; i < n; i++) {
            base += 0x400 << ((peek(W5100.RMSR) >> (i * 2)) & 3);
        }
        int rd = peek16(socket(n) + W5100.SN_RX_RD);
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) peek(base + ((rd + i) & (size - 1)));
        }
        return data;
    }

    /** A network whose every part the test controls. */
    static final class ScriptedNetwork implements Network {

        record Sent(int ip, int port, byte[] payload) {
        }

        final List<Stream> streams = new ArrayList<>();
        final List<Endpoint> datagrams = new ArrayList<>();
        final List<byte[]> frames = new ArrayList<>();
        final Deque<byte[]> inbound = new ArrayDeque<>();

        @Override
        public StreamConnection connect(int ip, int port) {
            Stream stream = new Stream();
            streams.add(stream);
            return stream;
        }

        @Override
        public StreamListener listen(int port) {
            return new StreamListener() {
                @Override
                public StreamConnection accept() {
                    return null;
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public DatagramEndpoint openDatagrams() {
            Endpoint endpoint = new Endpoint();
            datagrams.add(endpoint);
            return endpoint;
        }

        @Override
        public void transmit(byte[] frame) {
            frames.add(frame);
        }

        @Override
        public byte[] peekFrame() {
            return inbound.peek();
        }

        @Override
        public void dropFrame() {
            assertNotNull(inbound.poll());
        }

        @Override
        public void poll(long micros) {
        }

        @Override
        public void close() {
        }

        static final class Stream implements StreamConnection {
            boolean connecting = true;
            boolean failed;
            boolean eof;
            boolean closed;
            int accept = Integer.MAX_VALUE;
            final ByteArrayOutputStream written = new ByteArrayOutputStream();
            final Deque<byte[]> toRead = new ArrayDeque<>();

            @Override
            public boolean isConnecting() {
                return connecting;
            }

            @Override
            public boolean isFailed() {
                return failed;
            }

            @Override
            public int remoteIp() {
                return 0;
            }

            @Override
            public int remotePort() {
                return 0;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                byte[] next = toRead.poll();
                if (next == null) {
                    return eof ? -1 : 0;
                }
                assertFalse(next.length > length, "test data fits");
                System.arraycopy(next, 0, buffer, offset, next.length);
                return next.length;
            }

            @Override
            public int write(byte[] buffer, int offset, int length) {
                int taken = Math.min(length, accept);
                written.write(buffer, offset, taken);
                accept -= Math.min(accept, taken);
                return taken;
            }

            @Override
            public void shutdownOutput() {
            }

            @Override
            public void close() {
                closed = true;
            }
        }

        static final class Endpoint implements DatagramEndpoint {
            final List<Sent> sent = new ArrayList<>();
            final Deque<Datagram> inbound = new ArrayDeque<>();

            @Override
            public void send(int ip, int port, byte[] payload) {
                sent.add(new Sent(ip, port, payload));
            }

            @Override
            public Datagram peek() {
                return inbound.peek();
            }

            @Override
            public void drop() {
                inbound.poll();
            }

            @Override
            public void close() {
            }
        }
    }
}
