package com.nordstrom.uthernet.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;

/**
 * Field access and header construction for the frames the virtual network
 * exchanges with the guest: Ethernet II, IPv4 (no options, no fragments),
 * ARP, ICMP, UDP and TCP. Addresses are IPv4 addresses packed big-endian
 * into an {@code int}.
 */
final class Packets {

    static final int ETHERNET_HEADER = 14;
    static final int IP_HEADER = 20;
    static final int UDP_HEADER = 8;
    static final int TCP_HEADER = 20;

    static final int ETHERTYPE_IPV4 = 0x0800;
    static final int ETHERTYPE_ARP = 0x0806;

    static final int PROTOCOL_ICMP = 1;
    static final int PROTOCOL_TCP = 6;
    static final int PROTOCOL_UDP = 17;

    static final byte[] BROADCAST_MAC = {-1, -1, -1, -1, -1, -1};
    static final int BROADCAST_IP = 0xFFFFFFFF;

    private Packets() {
    }

    static int u8(byte[] b, int offset) {
        return b[offset] & 0xFF;
    }

    static int u16(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 8) | (b[offset + 1] & 0xFF);
    }

    static int u32(byte[] b, int offset) {
        return (u16(b, offset) << 16) | u16(b, offset + 2);
    }

    static void put16(byte[] b, int offset, int value) {
        b[offset] = (byte) (value >> 8);
        b[offset + 1] = (byte) value;
    }

    static void put32(byte[] b, int offset, int value) {
        put16(b, offset, value >>> 16);
        put16(b, offset + 2, value);
    }

    /** The ones'-complement sum of 16-bit words, folded to 16 bits but not complemented. */
    static int sum(byte[] b, int offset, int length, int initial) {
        long sum = initial;
        int end = offset + length;
        int i = offset;
        for (; i + 1 < end; i += 2) {
            sum += u16(b, i);
        }
        if (i < end) {
            sum += (b[i] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) sum;
    }

    static int checksum(byte[] b, int offset, int length, int initial) {
        return ~sum(b, offset, length, initial) & 0xFFFF;
    }

    // ---------------- IPv4 addresses ----------------

    static int ipOf(InetAddress address) {
        byte[] b = address.getAddress();
        return b.length == 4 ? u32(b, 0) : 0;
    }

    static int ipOf(SocketAddress address) {
        return address instanceof InetSocketAddress ? ipOf(((InetSocketAddress) address).getAddress()) : 0;
    }

    static int portOf(SocketAddress address) {
        return address instanceof InetSocketAddress ? ((InetSocketAddress) address).getPort() : 0;
    }

    static InetAddress inetAddress(int ip) {
        byte[] b = new byte[4];
        put32(b, 0, ip);
        try {
            return Inet4Address.getByAddress(b);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e); // four bytes are always a valid address
        }
    }

    static String dotted(int ip) {
        return (ip >>> 24) + "." + ((ip >> 16) & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + (ip & 0xFF);
    }

    // ---------------- frame construction ----------------

    /**
     * Builds an Ethernet frame carrying an IPv4 packet whose transport
     * header and payload the caller fills in from {@link #ETHERNET_HEADER}
     * + {@link #IP_HEADER} on; {@link #finishTransport} then sets the checksums.
     *
     * @param destinationMac the frame's destination
     * @param sourceMac      the frame's source
     * @param source         the packet's source address
     * @param destination    the packet's destination address
     * @param protocol       the IP protocol number
     * @param transportLength the transport header and payload length
     * @param identification the IP identification field
     * @return the frame, IP header filled in
     */
    static byte[] ipFrame(byte[] destinationMac, byte[] sourceMac, int source, int destination, int protocol,
                          int transportLength, int identification) {
        byte[] frame = new byte[ETHERNET_HEADER + IP_HEADER + transportLength];
        System.arraycopy(destinationMac, 0, frame, 0, 6);
        System.arraycopy(sourceMac, 0, frame, 6, 6);
        put16(frame, 12, ETHERTYPE_IPV4);
        int ip = ETHERNET_HEADER;
        frame[ip] = 0x45;
        put16(frame, ip + 2, IP_HEADER + transportLength);
        put16(frame, ip + 4, identification);
        put16(frame, ip + 6, 0x4000); // don't fragment
        frame[ip + 8] = 64;
        frame[ip + 9] = (byte) protocol;
        put32(frame, ip + 12, source);
        put32(frame, ip + 16, destination);
        put16(frame, ip + 10, checksum(frame, ip, IP_HEADER, 0));
        return frame;
    }

    /**
     * Sets the UDP or TCP checksum of a frame from {@link #ipFrame}, over
     * the pseudo-header, transport header and payload.
     *
     * @param frame            the frame
     * @param checksumOffset   the checksum field's offset within the transport header
     */
    static void finishTransport(byte[] frame, int checksumOffset) {
        int ip = ETHERNET_HEADER;
        int transport = ip + IP_HEADER;
        int length = frame.length - transport;
        int pseudo = sum(frame, ip + 12, 8, 0) + u8(frame, ip + 9) + length;
        int value = checksum(frame, transport, length, pseudo);
        if (value == 0 && u8(frame, ip + 9) == PROTOCOL_UDP) {
            value = 0xFFFF; // zero means "no checksum" in UDP
        }
        put16(frame, transport + checksumOffset, value);
    }
}
