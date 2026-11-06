package com.nordstrom.uthernet.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static com.nordstrom.uthernet.net.Packets.ETHERNET_HEADER;
import static com.nordstrom.uthernet.net.Packets.IP_HEADER;
import static com.nordstrom.uthernet.net.Packets.TCP_HEADER;
import static com.nordstrom.uthernet.net.Packets.UDP_HEADER;
import static com.nordstrom.uthernet.net.Packets.put16;
import static com.nordstrom.uthernet.net.Packets.put32;
import static com.nordstrom.uthernet.net.Packets.u16;
import static com.nordstrom.uthernet.net.Packets.u32;
import static com.nordstrom.uthernet.net.Packets.u8;

/**
 * The only other station on the guest's Ethernet segment: a gateway that
 * terminates everything the guest sends in frames (MACRAW and IPRAW
 * sockets) and carries it to the host.
 * <ul>
 * <li>ARP: answers every request for an address other than the guest's own
 *     with the gateway's MAC, so a guest configured for any subnet finds
 *     its next hop.</li>
 * <li>ICMP: answers echo requests to the gateway and name server.</li>
 * <li>UDP: DHCP and DNS go to {@link UdpServices}; everything else gets a
 *     host {@link DatagramChannel} per guest port.</li>
 * <li>TCP: each connection the guest opens is terminated here and carried
 *     on a host {@link SocketChannel}; see {@link Flow}.</li>
 * </ul>
 * Frames to the guest wait in a queue until the chip has room for them,
 * so the segment never drops one. A TCP flow stops reading from the host
 * while the queue is long, which pushes back on the host's sender.
 */
final class Gateway {

    private static final int FLAG_FIN = 0x01;
    private static final int FLAG_SYN = 0x02;
    private static final int FLAG_RST = 0x04;
    private static final int FLAG_PSH = 0x08;
    private static final int FLAG_ACK = 0x10;

    /** Frames queued for the guest beyond which TCP flows stop reading from the host. */
    private static final int QUEUE_LIMIT = 16;
    private static final long UDP_IDLE_MICROS = 120_000_000L;

    private final UdpServices services;
    private final Deque<byte[]> toGuest = new ArrayDeque<>();
    private final Map<Integer, UdpBinding> udpBindings = new HashMap<>();
    private final Map<FlowKey, Flow> flows = new HashMap<>();
    private final ByteBuffer udpBuffer = ByteBuffer.allocate(65_507);
    private byte[] guestMac = Packets.BROADCAST_MAC;
    private int identification;
    private long now;

    Gateway(UdpServices services) {
        this.services = services;
    }

    /** @return the next frame for the guest, left queued until {@link #drop} */
    byte[] peek() {
        return toGuest.peek();
    }

    void drop() {
        toGuest.poll();
    }

    /**
     * Moves data from the host toward the guest and runs timers.
     *
     * @param micros emulated time
     */
    void poll(long micros) {
        now = micros;
        for (Iterator<UdpBinding> it = udpBindings.values().iterator(); it.hasNext(); ) {
            UdpBinding binding = it.next();
            binding.receive();
            if (now - binding.lastUsed > UDP_IDLE_MICROS) {
                HostSockets.closeQuietly(binding.channel);
                it.remove();
            }
        }
        for (Iterator<Flow> it = flows.values().iterator(); it.hasNext(); ) {
            if (!it.next().service()) {
                it.remove();
            }
        }
    }

    void close() {
        udpBindings.values().forEach(binding -> HostSockets.closeQuietly(binding.channel));
        udpBindings.clear();
        flows.values().forEach(flow -> HostSockets.closeQuietly(flow.channel));
        flows.clear();
        toGuest.clear();
    }

    // ------------------------------------------------------------------
    // From the guest
    // ------------------------------------------------------------------

    /**
     * Takes a frame the guest transmitted.
     *
     * @param frame  the frame
     * @param micros emulated time
     */
    void fromGuest(byte[] frame, long micros) {
        now = micros;
        if (frame.length < ETHERNET_HEADER) {
            return;
        }
        if ((frame[6] & 1) == 0) {
            guestMac = Arrays.copyOfRange(frame, 6, 12); // a unicast source: the guest's MAC
        }
        int type = u16(frame, 12);
        if (type == Packets.ETHERTYPE_ARP) {
            arp(frame);
        } else if (type == Packets.ETHERTYPE_IPV4) {
            ipv4(frame);
        }
    }

    private void arp(byte[] frame) {
        int a = ETHERNET_HEADER;
        if (frame.length < a + 28 || u16(frame, a) != 1 || u16(frame, a + 2) != Packets.ETHERTYPE_IPV4
            || u16(frame, a + 6) != 1) {
            return;
        }
        int sender = u32(frame, a + 14);
        int target = u32(frame, a + 24);
        if (target == sender || target == 0) {
            return; // a probe or announcement of the guest's own address
        }
        byte[] reply = new byte[ETHERNET_HEADER + 28];
        System.arraycopy(frame, a + 8, reply, 0, 6);
        System.arraycopy(Subnet.GATEWAY_MAC, 0, reply, 6, 6);
        put16(reply, 12, Packets.ETHERTYPE_ARP);
        System.arraycopy(frame, a, reply, a, 6);                // hardware and protocol types, lengths
        put16(reply, a + 6, 2);                                  // reply
        System.arraycopy(Subnet.GATEWAY_MAC, 0, reply, a + 8, 6);
        put32(reply, a + 14, target);
        System.arraycopy(frame, a + 8, reply, a + 18, 10);      // the asker's MAC and address
        toGuest.add(reply);
    }

    private void ipv4(byte[] frame) {
        int ip = ETHERNET_HEADER;
        if (frame.length < ip + IP_HEADER || (frame[ip] & 0xF0) != 0x40) {
            return;
        }
        int ihl = (frame[ip] & 0x0F) * 4;
        int total = Math.min(u16(frame, ip + 2), frame.length - ip);
        if ((u16(frame, ip + 6) & 0x3FFF) != 0 || total < ihl) {
            return; // a fragment: the W5100 doesn't fragment, and nothing here reassembles
        }
        int source = u32(frame, ip + 12);
        int destination = u32(frame, ip + 16);
        int transport = ip + ihl;
        int length = total - ihl;
        switch (u8(frame, ip + 9)) {
            case Packets.PROTOCOL_ICMP:
                icmp(frame, source, destination, transport, length);
                break;
            case Packets.PROTOCOL_UDP:
                udp(frame, source, destination, transport, length);
                break;
            case Packets.PROTOCOL_TCP:
                tcp(frame, source, destination, transport, length);
                break;
            default:
                break;
        }
    }

    private void icmp(byte[] frame, int source, int destination, int offset, int length) {
        boolean ours = destination == Subnet.GATEWAY || destination == Subnet.NAME_SERVER;
        if (!ours || length < 8 || frame[offset] != 8) {
            return; // only echo requests to the gateway's own addresses are answered
        }
        byte[] reply = Packets.ipFrame(guestMac, Subnet.GATEWAY_MAC, destination, source, Packets.PROTOCOL_ICMP,
            length, ++identification);
        int r = ETHERNET_HEADER + IP_HEADER;
        System.arraycopy(frame, offset, reply, r, length);
        reply[r] = 0; // echo reply
        put16(reply, r + 2, 0);
        put16(reply, r + 2, Packets.checksum(reply, r, length, 0));
        toGuest.add(reply);
    }

    // ---------------- UDP ----------------

    private void udp(byte[] frame, int source, int destination, int offset, int length) {
        if (length < UDP_HEADER) {
            return;
        }
        int sourcePort = u16(frame, offset);
        int destinationPort = u16(frame, offset + 2);
        int payloadLength = Math.min(u16(frame, offset + 4), length) - UDP_HEADER;
        if (payloadLength < 0) {
            return;
        }
        byte[] payload = Arrays.copyOfRange(frame, offset + UDP_HEADER, offset + UDP_HEADER + payloadLength);
        if (services.handle(source, sourcePort, destination, destinationPort, payload, this::udpToGuest)
            || Subnet.isBroadcast(destination)) {
            return;
        }
        UdpBinding binding = udpBindings.get(sourcePort);
        try {
            if (binding == null) {
                binding = new UdpBinding(sourcePort, source);
                udpBindings.put(sourcePort, binding);
            }
            binding.lastUsed = now;
            binding.guestIp = source;
            binding.channel.send(ByteBuffer.wrap(payload), Subnet.toHost(destination, destinationPort));
        } catch (IOException e) {
            HostSockets.log("UDP to " + Packets.dotted(destination) + ":" + destinationPort + " failed", e);
        }
    }

    private void udpToGuest(int fromIp, int fromPort, int toIp, int toPort, byte[] payload) {
        byte[] mac = toIp == Packets.BROADCAST_IP ? Packets.BROADCAST_MAC : guestMac;
        byte[] frame = Packets.ipFrame(mac, Subnet.GATEWAY_MAC, fromIp, toIp, Packets.PROTOCOL_UDP,
            UDP_HEADER + payload.length, ++identification);
        int u = ETHERNET_HEADER + IP_HEADER;
        put16(frame, u, fromPort);
        put16(frame, u + 2, toPort);
        put16(frame, u + 4, UDP_HEADER + payload.length);
        System.arraycopy(payload, 0, frame, u + UDP_HEADER, payload.length);
        Packets.finishTransport(frame, 6);
        toGuest.add(frame);
    }

    /** One guest UDP port's host channel. */
    private final class UdpBinding {
        final int guestPort;
        final DatagramChannel channel;
        int guestIp;
        long lastUsed;

        UdpBinding(int guestPort, int guestIp) throws IOException {
            this.guestPort = guestPort;
            this.guestIp = guestIp;
            this.channel = DatagramChannel.open(StandardProtocolFamily.INET);
            channel.configureBlocking(false);
            channel.bind(new InetSocketAddress(0));
        }

        void receive() {
            try {
                while (true) {
                    udpBuffer.clear();
                    SocketAddress from = channel.receive(udpBuffer);
                    if (from == null) {
                        return;
                    }
                    lastUsed = now;
                    udpBuffer.flip();
                    udpToGuest(Subnet.fromHost(Packets.ipOf(from)), Packets.portOf(from), guestIp, guestPort,
                        Arrays.copyOf(udpBuffer.array(), udpBuffer.limit()));
                }
            } catch (IOException e) {
                HostSockets.log("UDP receive failed", e);
            }
        }
    }

    // ---------------- TCP ----------------

    private record FlowKey(int guestPort, int remoteIp, int remotePort) {
    }

    private void tcp(byte[] frame, int source, int destination, int offset, int length) {
        if (length < TCP_HEADER) {
            return;
        }
        int headerLength = ((frame[offset + 12] & 0xF0) >> 4) * 4;
        if (headerLength < TCP_HEADER || headerLength > length) {
            return;
        }
        int sourcePort = u16(frame, offset);
        int destinationPort = u16(frame, offset + 2);
        int seq = u32(frame, offset + 4);
        int ack = u32(frame, offset + 8);
        int flags = frame[offset + 13] & 0x3F;
        int window = u16(frame, offset + 14);
        int dataOffset = offset + headerLength;
        int dataLength = length - headerLength;
        FlowKey key = new FlowKey(sourcePort, destination, destinationPort);
        Flow flow = flows.get(key);
        if ((flags & FLAG_RST) != 0) {
            if (flow != null) {
                HostSockets.closeQuietly(flow.channel);
                flows.remove(key);
            }
            return;
        }
        if (flow == null) {
            if ((flags & (FLAG_SYN | FLAG_ACK)) == FLAG_SYN) {
                flows.put(key, new Flow(key, source, seq, window, mss(frame, offset, headerLength)));
            } else {
                // nothing open for it: reset, as any TCP would
                int segmentLength = dataLength + ((flags & FLAG_SYN) != 0 ? 1 : 0) + ((flags & FLAG_FIN) != 0 ? 1 : 0);
                if ((flags & FLAG_ACK) != 0) {
                    tcpToGuest(destination, destinationPort, source, sourcePort, ack, 0, FLAG_RST, 0, null, 0, 0);
                } else {
                    tcpToGuest(destination, destinationPort, source, sourcePort, 0, seq + segmentLength,
                        FLAG_RST | FLAG_ACK, 0, null, 0, 0);
                }
            }
            return;
        }
        if (!flow.segment(frame, seq, ack, flags, window, dataOffset, dataLength)) {
            flows.remove(key);
        }
    }

    private static int mss(byte[] frame, int offset, int headerLength) {
        for (int i = offset + TCP_HEADER; i < offset + headerLength; ) {
            int kind = frame[i] & 0xFF;
            if (kind == 0) {
                break;
            }
            if (kind == 1) {
                i++;
                continue;
            }
            if (i + 1 >= offset + headerLength) {
                break;
            }
            int length = frame[i + 1] & 0xFF;
            if (kind == 2 && length == 4 && i + 4 <= offset + headerLength) {
                return u16(frame, i + 2);
            }
            if (length < 2) {
                break;
            }
            i += length;
        }
        return 536; // RFC 1122's default
    }

    private void tcpToGuest(int fromIp, int fromPort, int toIp, int toPort, int seq, int ack, int flags,
                            int window, byte[] data, int dataOffset, int dataLength) {
        boolean syn = (flags & FLAG_SYN) != 0;
        int header = TCP_HEADER + (syn ? 4 : 0);
        byte[] frame = Packets.ipFrame(guestMac, Subnet.GATEWAY_MAC, fromIp, toIp, Packets.PROTOCOL_TCP,
            header + dataLength, ++identification);
        int t = ETHERNET_HEADER + IP_HEADER;
        put16(frame, t, fromPort);
        put16(frame, t + 2, toPort);
        put32(frame, t + 4, seq);
        put32(frame, t + 8, ack);
        frame[t + 12] = (byte) ((header / 4) << 4);
        frame[t + 13] = (byte) flags;
        put16(frame, t + 14, window);
        if (syn) {
            frame[t + 20] = 2; // MSS
            frame[t + 21] = 4;
            put16(frame, t + 22, Flow.OUR_MSS);
        }
        if (dataLength > 0) {
            System.arraycopy(data, dataOffset, frame, t + header, dataLength);
        }
        Packets.finishTransport(frame, 16);
        toGuest.add(frame);
    }

    /**
     * One guest TCP connection, terminated here and carried to the host on
     * a {@link SocketChannel}. Bytes from the host wait in {@link #unacked}
     * until the guest acknowledges them, so a segment the guest misses is
     * sent again when the retransmission timer runs out; sending respects
     * the guest's window and MSS. Bytes from the guest are taken in order
     * only, up to what's waiting to go to the host.
     */
    private final class Flow {

        static final int OUR_MSS = 1460;
        private static final int BUFFER = 16 * 1024;
        private static final long INITIAL_RTO_MICROS = 1_000_000L;
        private static final long MAX_RTO_MICROS = 16_000_000L;
        private static final int MAX_RETRIES = 8;

        private static final int CONNECTING = 0;
        private static final int SYN_RECEIVED = 1;
        private static final int ESTABLISHED = 2;

        final FlowKey key;
        final int guestIp;
        final int guestMss;
        final SocketChannel channel;
        final int iss = ThreadLocalRandom.current().nextInt();
        int state = CONNECTING;
        int sndUna = iss;
        int sndNxt = iss;
        int rcvNxt;
        int guestWindow;
        /** Bytes from the host, starting at {@link #sndUna}; the first {@link #sent} have been sent. */
        final byte[] unacked = new byte[BUFFER];
        int unackedLength;
        int sent;
        final ByteBuffer toHost = ByteBuffer.allocate(BUFFER);
        boolean hostEof;
        boolean finSent;
        boolean guestFin;
        boolean outputShut;
        long deadline;
        long rto = INITIAL_RTO_MICROS;
        int retries;

        Flow(FlowKey key, int guestIp, int seq, int window, int guestMss) {
            this.key = key;
            this.guestIp = guestIp;
            this.rcvNxt = seq + 1;
            this.guestWindow = window;
            this.guestMss = Math.min(guestMss, OUR_MSS);
            SocketChannel opened = null;
            try {
                opened = SocketChannel.open();
                opened.configureBlocking(false);
                opened.setOption(StandardSocketOptions.TCP_NODELAY, true);
                opened.connect(Subnet.toHost(key.remoteIp(), key.remotePort()));
            } catch (IOException | RuntimeException e) {
                HostSockets.log("connecting to " + Packets.dotted(key.remoteIp()) + ":" + key.remotePort()
                    + " failed", e);
                HostSockets.closeQuietly(opened);
                opened = null;
            }
            channel = opened;
        }

        /** @return false once the flow is over and should be forgotten */
        boolean segment(byte[] frame, int seq, int ack, int flags, int window, int dataOffset, int dataLength) {
            if ((flags & FLAG_SYN) != 0) {
                if (state == SYN_RECEIVED) {
                    send(iss, FLAG_SYN | FLAG_ACK, null, 0, 0); // our SYN-ACK was lost
                }
                return true;
            }
            if (state == CONNECTING || (flags & FLAG_ACK) == 0) {
                return true;
            }
            if (state == SYN_RECEIVED) {
                if (ack != iss + 1) {
                    return true;
                }
                state = ESTABLISHED;
                sndUna = iss + 1;
                sndNxt = sndUna;
                deadline = 0;
            }
            acknowledge(ack);
            guestWindow = window;
            boolean ackNeeded = dataLength > 0 || (flags & FLAG_FIN) != 0;
            if (seq == rcvNxt && !guestFin) {
                int taken = Math.min(dataLength, toHost.remaining());
                toHost.put(frame, dataOffset, taken);
                rcvNxt += taken;
                if ((flags & FLAG_FIN) != 0 && taken == dataLength) {
                    rcvNxt++;
                    guestFin = true;
                }
            }
            if (ackNeeded) {
                send(sndNxt, FLAG_ACK, null, 0, 0);
            }
            return service();
        }

        private void acknowledge(int ack) {
            int advance = ack - sndUna;
            if (advance <= 0 || ack - sndNxt > 0) {
                return; // old, or for something not sent
            }
            int dataAcked = Math.min(advance, sent);
            System.arraycopy(unacked, dataAcked, unacked, 0, unackedLength - dataAcked);
            unackedLength -= dataAcked;
            sent -= dataAcked;
            sndUna = ack;
            retries = 0;
            rto = INITIAL_RTO_MICROS;
            deadline = sndNxt != sndUna ? now + rto : 0;
        }

        /** Runs the flow: connect, move data both ways, time out. @return false once it's over */
        boolean service() {
            try {
                if (state == CONNECTING) {
                    if (channel == null) {
                        return resetGuest();
                    }
                    if (!channel.finishConnect()) {
                        return true;
                    }
                    state = SYN_RECEIVED;
                    sndNxt = iss + 1;
                    send(iss, FLAG_SYN | FLAG_ACK, null, 0, 0);
                    deadline = now + rto;
                    return true;
                }
                if (deadline != 0 && now >= deadline && !retransmit()) {
                    return resetGuest();
                }
                if (state != ESTABLISHED) {
                    return true;
                }
                if (toHost.position() > 0) {
                    toHost.flip();
                    channel.write(toHost);
                    toHost.compact();
                }
                if (guestFin && toHost.position() == 0 && !outputShut) {
                    channel.shutdownOutput();
                    outputShut = true;
                }
                fromHost();
                if (guestFin && finSent && sndUna == sndNxt && outputShut) {
                    HostSockets.closeQuietly(channel);
                    return false;
                }
                return true;
            } catch (IOException e) {
                return resetGuest();
            }
        }

        private void fromHost() throws IOException {
            while (!hostEof && unackedLength < BUFFER && toGuest.size() < QUEUE_LIMIT) {
                int read = channel.read(ByteBuffer.wrap(unacked, unackedLength, BUFFER - unackedLength));
                if (read < 0) {
                    hostEof = true;
                } else if (read == 0) {
                    break;
                } else {
                    unackedLength += read;
                }
            }
            while (sent < unackedLength && toGuest.size() < QUEUE_LIMIT) {
                int room = guestWindow - sent;
                int length = Math.min(Math.min(guestMss, unackedLength - sent), room);
                if (length <= 0) {
                    if (deadline == 0) {
                        deadline = now + rto; // a closed window: probe it when the timer runs out
                    }
                    break;
                }
                send(sndUna + sent, FLAG_ACK | FLAG_PSH, unacked, sent, length);
                sent += length;
                sndNxt = sndUna + sent;
                if (deadline == 0) {
                    deadline = now + rto;
                }
            }
            if (hostEof && !finSent && sent == unackedLength && toGuest.size() < QUEUE_LIMIT) {
                send(sndNxt, FLAG_FIN | FLAG_ACK, null, 0, 0);
                sndNxt++;
                finSent = true;
                if (deadline == 0) {
                    deadline = now + rto;
                }
            }
        }

        /** The timer ran out: go back to the first unacknowledged byte. @return false after too many tries */
        private boolean retransmit() {
            if (++retries > MAX_RETRIES) {
                return false;
            }
            rto = Math.min(rto * 2, MAX_RTO_MICROS);
            deadline = now + rto;
            if (state == SYN_RECEIVED) {
                send(iss, FLAG_SYN | FLAG_ACK, null, 0, 0);
                return true;
            }
            if (sndNxt == sndUna) {
                if (guestWindow == 0 && unackedLength > 0) {
                    send(sndUna, FLAG_ACK, unacked, 0, 1); // window probe; counts as sent once acknowledged
                    sent = 1;
                    sndNxt = sndUna + 1;
                } else {
                    deadline = 0;
                    retries = 0;
                }
                return true;
            }
            sent = 0;          // go back N: everything from sndUna goes again,
            sndNxt = sndUna;   // and the FIN after it, if it was sent and not acknowledged
            finSent = false;
            return true;
        }

        private boolean resetGuest() {
            send(sndNxt, FLAG_RST | FLAG_ACK, null, 0, 0);
            HostSockets.closeQuietly(channel);
            return false;
        }

        private void send(int seq, int flags, byte[] data, int offset, int length) {
            int window = Math.min(toHost.remaining(), 0xFFFF);
            tcpToGuest(key.remoteIp(), key.remotePort(), guestIp, key.guestPort(), seq, rcvNxt, flags, window,
                data, offset, length);
        }
    }
}
