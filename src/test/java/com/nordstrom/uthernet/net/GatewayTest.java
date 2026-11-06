package com.nordstrom.uthernet.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Predicate;

import static com.nordstrom.uthernet.net.Packets.ETHERNET_HEADER;
import static com.nordstrom.uthernet.net.Packets.IP_HEADER;
import static com.nordstrom.uthernet.net.Packets.u16;
import static com.nordstrom.uthernet.net.Packets.u32;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plays the guest's side of the virtual LAN with hand-built frames, the
 * way IP65 or Contiki does over a MACRAW socket, against real servers on
 * the host's loopback interface.
 */
class GatewayTest {

    private static final byte[] GUEST_MAC = {0x00, 0x08, (byte) 0xDC, (byte) 0xA2, (byte) 0xA2, (byte) 0xA2};
    private static final int GUEST = Subnet.LEASED;
    private static final int FIN = 0x01;
    private static final int SYN = 0x02;
    private static final int RST = 0x04;
    private static final int ACK = 0x10;

    private final UdpServices services = new UdpServices();
    private final Gateway gateway = new Gateway(services);
    private long now;

    @AfterEach
    void close() {
        gateway.close();
        services.close();
    }

    @Test
    void answersArpForAnyAddressButTheGuests() {
        gateway.fromGuest(arpRequest(Subnet.GATEWAY), now);
        byte[] reply = next();
        assertArrayEquals(GUEST_MAC, Arrays.copyOfRange(reply, 0, 6));
        assertEquals(2, u16(reply, ETHERNET_HEADER + 6));
        assertArrayEquals(Subnet.GATEWAY_MAC, Arrays.copyOfRange(reply, ETHERNET_HEADER + 8, ETHERNET_HEADER + 14));
        assertEquals(Subnet.GATEWAY, u32(reply, ETHERNET_HEADER + 14));

        gateway.fromGuest(arpRequest(0xC0A80101), now); // a hand-configured guest's router
        assertEquals(0xC0A80101, u32(next(), ETHERNET_HEADER + 14));

        gateway.fromGuest(arpRequest(GUEST), now); // the guest probing its own address
        assertNull(gateway.peek());
    }

    @Test
    void leasesAnAddressOverDhcp() {
        gateway.fromGuest(udp(0, 68, Packets.BROADCAST_IP, 67, dhcp(1), Packets.BROADCAST_MAC), now);
        byte[] offer = udpPayload(next());
        assertEquals(2, offer[0]);
        assertEquals(Subnet.LEASED, u32(offer, 16));
        assertEquals(2, option(offer, 53)[0]);
        assertEquals(Subnet.MASK, u32(option(offer, 1), 0));
        assertEquals(Subnet.GATEWAY, u32(option(offer, 3), 0));
        assertEquals(Subnet.NAME_SERVER, u32(option(offer, 6), 0));

        gateway.fromGuest(udp(0, 68, Packets.BROADCAST_IP, 67, dhcp(3), Packets.BROADCAST_MAC), now);
        assertEquals(5, option(udpPayload(next()), 53)[0]);
    }

    @Test
    void looksNamesUpWithTheHostsResolver() {
        byte[] query = dnsQuery("localhost");
        gateway.fromGuest(udp(GUEST, 1234, Subnet.NAME_SERVER, 53, query, Subnet.GATEWAY_MAC), now);
        byte[] frame = await(f -> true);
        assertEquals(Subnet.NAME_SERVER, u32(frame, ETHERNET_HEADER + 12));
        assertEquals(53, u16(frame, ETHERNET_HEADER + IP_HEADER));
        byte[] answer = udpPayload(frame);
        assertEquals(0x1234, u16(answer, 0));
        assertEquals(0, answer[3] & 0x0F);
        assertTrue(u16(answer, 6) >= 1);
        assertEquals(0x7F000001, u32(answer, query.length + 12));
    }

    @Test
    void answersPingsToTheGateway() {
        byte[] echo = {8, 0, 0, 0, 0, 1, 0, 1, 'h', 'i'};
        Packets.put16(echo, 2, Packets.checksum(echo, 0, echo.length, 0));
        byte[] frame = Packets.ipFrame(Subnet.GATEWAY_MAC, GUEST_MAC, GUEST, Subnet.GATEWAY, Packets.PROTOCOL_ICMP,
            echo.length, 1);
        System.arraycopy(echo, 0, frame, ETHERNET_HEADER + IP_HEADER, echo.length);
        gateway.fromGuest(frame, now);
        byte[] reply = next();
        int icmp = ETHERNET_HEADER + IP_HEADER;
        assertEquals(0, reply[icmp]);
        assertEquals(0, Packets.checksum(reply, icmp, echo.length, 0));
        assertEquals('i', reply[icmp + 9]);
    }

    @Test
    void carriesUdpToTheHostThroughTheGatewayAddress() throws Exception {
        try (DatagramSocket echo = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> echoOnce(echo));
            server.start();
            byte[] payload = "marco".getBytes(StandardCharsets.US_ASCII);
            gateway.fromGuest(udp(GUEST, 4000, Subnet.GATEWAY, echo.getLocalPort(), payload, Subnet.GATEWAY_MAC), now);
            byte[] reply = await(f -> true);
            server.join(5000);
            assertEquals(Subnet.GATEWAY, u32(reply, ETHERNET_HEADER + 12), "the host shows up as the gateway");
            assertEquals(echo.getLocalPort(), u16(reply, ETHERNET_HEADER + IP_HEADER));
            assertEquals(4000, u16(reply, ETHERNET_HEADER + IP_HEADER + 2));
            assertArrayEquals(payload, udpPayload(reply));
            assertEquals(0, Packets.checksum(pseudoHeaded(reply), 0, pseudoHeaded(reply).length, 0));
        }
    }

    @Test
    void carriesATcpConnectionAndClosesItBothWays() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread echo = new Thread(() -> echoStream(server));
            echo.start();
            int port = server.getLocalPort();
            int seq = 1000;
            gateway.fromGuest(tcp(5000, Subnet.GATEWAY, port, seq, 0, SYN, new byte[0]), now);
            byte[] synAck = await(f -> (flags(f) & SYN) != 0);
            assertEquals(SYN | ACK, flags(synAck));
            assertEquals(seq + 1, ack(synAck));
            int theirs = seq(synAck) + 1;
            seq++;
            gateway.fromGuest(tcp(5000, Subnet.GATEWAY, port, seq, theirs, ACK, new byte[0]), now);

            byte[] data = "ping".getBytes(StandardCharsets.US_ASCII);
            gateway.fromGuest(tcp(5000, Subnet.GATEWAY, port, seq, theirs, ACK, data), now);
            seq += data.length;
            byte[] echoed = await(f -> tcpData(f).length > 0);
            assertEquals(seq, ack(echoed));
            assertEquals(theirs, seq(echoed));
            assertArrayEquals(data, tcpData(echoed));
            theirs += data.length;

            gateway.fromGuest(tcp(5000, Subnet.GATEWAY, port, seq, theirs, ACK | FIN, new byte[0]), now);
            seq++;
            byte[] fin = await(f -> (flags(f) & FIN) != 0); // the echo server closes when it sees ours
            assertEquals(seq, ack(fin));
            gateway.fromGuest(tcp(5000, Subnet.GATEWAY, port, seq, theirs + 1, ACK, new byte[0]), now);
            echo.join(5000);
            gateway.poll(now);
            gateway.fromGuest(tcp(5000, Subnet.GATEWAY, port, seq, theirs + 1, ACK, new byte[]{1}), now);
            assertEquals(RST, flags(await(f -> true)) & RST, "the flow is gone, so a straggler is reset");
        }
    }

    @Test
    void resendsDataTheGuestDoesNotAcknowledge() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread talker = new Thread(() -> {
                try (Socket s = server.accept()) {
                    s.getOutputStream().write("hello".getBytes(StandardCharsets.US_ASCII));
                    s.getInputStream().read();
                } catch (IOException e) {
                    // the test fails on what the guest saw
                }
            });
            talker.start();
            int port = server.getLocalPort();
            gateway.fromGuest(tcp(5001, Subnet.GATEWAY, port, 1, 0, SYN, new byte[0]), now);
            int theirs = seq(await(f -> (flags(f) & SYN) != 0)) + 1;
            gateway.fromGuest(tcp(5001, Subnet.GATEWAY, port, 2, theirs, ACK, new byte[0]), now);
            byte[] first = await(f -> tcpData(f).length > 0);
            assertEquals(theirs, seq(first));

            now += 1_100_000; // past the first retransmission timeout, with no ACK
            byte[] again = await(f -> tcpData(f).length > 0);
            assertEquals(theirs, seq(again));
            assertArrayEquals(tcpData(first), tcpData(again));
            gateway.fromGuest(tcp(5001, Subnet.GATEWAY, port, 2, theirs + 5, ACK | RST, new byte[0]), now);
            talker.join(5000);
        }
    }

    @Test
    void resetsAConnectionTheHostRefuses() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort(); // closed again before the guest connects
        }
        gateway.fromGuest(tcp(5002, Subnet.GATEWAY, port, 77, 0, SYN, new byte[0]), now);
        byte[] reset = await(f -> true);
        assertEquals(RST, flags(reset) & RST);
        assertEquals(78, ack(reset));
    }

    // ---------------- guest frames ----------------

    private static byte[] arpRequest(int target) {
        byte[] f = new byte[ETHERNET_HEADER + 28];
        System.arraycopy(Packets.BROADCAST_MAC, 0, f, 0, 6);
        System.arraycopy(GUEST_MAC, 0, f, 6, 6);
        Packets.put16(f, 12, Packets.ETHERTYPE_ARP);
        int a = ETHERNET_HEADER;
        Packets.put16(f, a, 1);
        Packets.put16(f, a + 2, Packets.ETHERTYPE_IPV4);
        f[a + 4] = 6;
        f[a + 5] = 4;
        Packets.put16(f, a + 6, 1);
        System.arraycopy(GUEST_MAC, 0, f, a + 8, 6);
        Packets.put32(f, a + 14, GUEST);
        Packets.put32(f, a + 24, target);
        return f;
    }

    private static byte[] udp(int source, int sourcePort, int destination, int destinationPort, byte[] payload,
                              byte[] destinationMac) {
        byte[] f = Packets.ipFrame(destinationMac, GUEST_MAC, source, destination, Packets.PROTOCOL_UDP,
            8 + payload.length, 1);
        int u = ETHERNET_HEADER + IP_HEADER;
        Packets.put16(f, u, sourcePort);
        Packets.put16(f, u + 2, destinationPort);
        Packets.put16(f, u + 4, 8 + payload.length);
        System.arraycopy(payload, 0, f, u + 8, payload.length);
        Packets.finishTransport(f, 6);
        return f;
    }

    private static byte[] tcp(int sourcePort, int destination, int destinationPort, int seq, int ack, int flags,
                              byte[] data) {
        byte[] f = Packets.ipFrame(Subnet.GATEWAY_MAC, GUEST_MAC, GUEST, destination, Packets.PROTOCOL_TCP,
            20 + data.length, 1);
        int t = ETHERNET_HEADER + IP_HEADER;
        Packets.put16(f, t, sourcePort);
        Packets.put16(f, t + 2, destinationPort);
        Packets.put32(f, t + 4, seq);
        Packets.put32(f, t + 8, ack);
        f[t + 12] = 0x50;
        f[t + 13] = (byte) flags;
        Packets.put16(f, t + 14, 1024);
        System.arraycopy(data, 0, f, t + 20, data.length);
        Packets.finishTransport(f, 16);
        return f;
    }

    private static byte[] dhcp(int type) {
        byte[] d = new byte[300];
        d[0] = 1;
        d[1] = 1;
        d[2] = 6;
        Packets.put32(d, 4, 0xCAFEF00D);
        System.arraycopy(GUEST_MAC, 0, d, 28, 6);
        Packets.put32(d, 236, 0x63825363);
        d[240] = 53;
        d[241] = 1;
        d[242] = (byte) type;
        d[243] = (byte) 255;
        return d;
    }

    private static byte[] dnsQuery(String name) {
        byte[] d = new byte[12 + name.length() + 2 + 4];
        Packets.put16(d, 0, 0x1234);
        d[2] = 0x01; // RD
        Packets.put16(d, 4, 1);
        int i = 12;
        for (String label : name.split("\\.")) {
            d[i++] = (byte) label.length();
            for (char c : label.toCharArray()) {
                d[i++] = (byte) c;
            }
        }
        d[i++] = 0;
        Packets.put16(d, i, 1);
        Packets.put16(d, i + 2, 1);
        return d;
    }

    // ---------------- reading the gateway's frames ----------------

    private byte[] next() {
        byte[] frame = gateway.peek();
        assertNotNull(frame, "a frame for the guest");
        gateway.drop();
        return frame;
    }

    /** Polls, as the chip does, until a frame matching {@code wanted} arrives; drops others. */
    private byte[] await(Predicate<byte[]> wanted) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            gateway.poll(now);
            services.runCompletions();
            byte[] frame;
            while ((frame = gateway.peek()) != null) {
                gateway.drop();
                if (wanted.test(frame)) {
                    return frame;
                }
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        throw new AssertionError("no frame arrived");
    }

    private static byte[] udpPayload(byte[] frame) {
        int u = ETHERNET_HEADER + IP_HEADER;
        return Arrays.copyOfRange(frame, u + 8, u + u16(frame, u + 4));
    }

    private static byte[] option(byte[] dhcp, int code) {
        for (int i = 240; i < dhcp.length && (dhcp[i] & 0xFF) != 255; i += 2 + dhcp[i + 1]) {
            if (dhcp[i] == code) {
                return Arrays.copyOfRange(dhcp, i + 2, i + 2 + dhcp[i + 1]);
            }
        }
        throw new AssertionError("no option " + code);
    }

    private static int flags(byte[] f) {
        return f[ETHERNET_HEADER + IP_HEADER + 13] & 0x3F;
    }

    private static int seq(byte[] f) {
        return u32(f, ETHERNET_HEADER + IP_HEADER + 4);
    }

    private static int ack(byte[] f) {
        return u32(f, ETHERNET_HEADER + IP_HEADER + 8);
    }

    private static byte[] tcpData(byte[] f) {
        int t = ETHERNET_HEADER + IP_HEADER;
        int header = ((f[t + 12] & 0xF0) >> 4) * 4;
        return Arrays.copyOfRange(f, t + header, f.length);
    }

    /** The UDP segment behind its pseudo-header, so a correct checksum sums to zero. */
    private static byte[] pseudoHeaded(byte[] frame) {
        int u = ETHERNET_HEADER + IP_HEADER;
        int length = frame.length - u;
        byte[] p = new byte[12 + length];
        System.arraycopy(frame, ETHERNET_HEADER + 12, p, 0, 8);
        p[9] = Packets.PROTOCOL_UDP;
        Packets.put16(p, 10, length);
        System.arraycopy(frame, u, p, 12, length);
        return p;
    }

    private static void echoOnce(DatagramSocket socket) {
        try {
            DatagramPacket packet = new DatagramPacket(new byte[512], 512);
            socket.receive(packet);
            socket.send(new DatagramPacket(packet.getData(), packet.getLength(), packet.getSocketAddress()));
        } catch (IOException e) {
            // the test fails on what the guest saw
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
