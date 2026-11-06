package com.nordstrom.uthernet.net;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The two services the virtual network itself provides over UDP, so a
 * guest can configure itself and look up names with no setup on the host:
 * <ul>
 * <li>a DHCP server on the gateway, which leases the guest
 *     {@link Subnet#LEASED} with the gateway as router and
 *     {@link Subnet#NAME_SERVER} as name server; and</li>
 * <li>a name server at {@link Subnet#NAME_SERVER}, which answers A queries
 *     by asking the host's own resolver, so it works wherever the host
 *     can look names up, with no need to know the host's DNS servers.</li>
 * </ul>
 * A lookup can take a while, so it runs on a background thread; its answer
 * is handed back to the emulation thread by {@link #runCompletions}.
 */
final class UdpServices {

    /** Where a service's reply goes. */
    interface Reply {
        /**
         * @param fromIp   the reply's source address
         * @param fromPort the reply's source port
         * @param toIp     the reply's destination address
         * @param toPort   the reply's destination port
         * @param payload  the reply
         */
        void send(int fromIp, int fromPort, int toIp, int toPort, byte[] payload);
    }

    static final int DHCP_SERVER_PORT = 67;
    static final int DHCP_CLIENT_PORT = 68;
    static final int DNS_PORT = 53;

    private static final int LEASE_SECONDS = 86_400;
    private static final int ANSWER_TTL_SECONDS = 60;

    private static final int DHCP_MAGIC = 0x63825363;
    private static final int DHCPDISCOVER = 1;
    private static final int DHCPOFFER = 2;
    private static final int DHCPREQUEST = 3;
    private static final int DHCPACK = 5;
    private static final int DHCPNAK = 6;
    private static final int DHCPINFORM = 8;

    private final ExecutorService resolver = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Uthernet2 name lookups");
        thread.setDaemon(true);
        return thread;
    });
    private final Queue<Runnable> completions = new ConcurrentLinkedQueue<>();

    /**
     * Handles a datagram addressed to one of the services.
     *
     * @param sourceIp        the sender's address
     * @param sourcePort      the sender's port
     * @param destinationIp   the destination address
     * @param destinationPort the destination port
     * @param payload         the payload
     * @param reply           where an answer goes
     * @return true if a service took the datagram; false if it's for elsewhere
     */
    boolean handle(int sourceIp, int sourcePort, int destinationIp, int destinationPort, byte[] payload,
                   Reply reply) {
        if (destinationPort == DHCP_SERVER_PORT
            && (Subnet.isBroadcast(destinationIp) || destinationIp == Subnet.GATEWAY)) {
            dhcp(payload, reply);
            return true;
        }
        if (destinationIp == Subnet.NAME_SERVER && destinationPort == DNS_PORT) {
            dns(sourceIp, sourcePort, payload, reply);
            return true;
        }
        return false;
    }

    /** Delivers the answers lookups have finished since the last call. Runs on the emulation thread. */
    void runCompletions() {
        Runnable completion;
        while ((completion = completions.poll()) != null) {
            completion.run();
        }
    }

    void close() {
        resolver.shutdownNow();
    }

    // ---------------- DHCP ----------------

    private void dhcp(byte[] request, Reply reply) {
        if (request.length < 240 || request[0] != 1 || Packets.u32(request, 236) != DHCP_MAGIC) {
            return;
        }
        int type = 0;
        int requested = 0;
        for (int i = 240; i < request.length && (request[i] & 0xFF) != 255; ) {
            int code = request[i] & 0xFF;
            if (code == 0) {
                i++;
                continue;
            }
            if (i + 1 >= request.length) {
                break;
            }
            int length = request[i + 1] & 0xFF;
            if (code == 53 && length >= 1 && i + 2 < request.length) {
                type = request[i + 2] & 0xFF;
            } else if (code == 50 && length == 4 && i + 6 <= request.length) {
                requested = Packets.u32(request, i + 2);
            }
            i += 2 + length;
        }
        int clientAddress = Packets.u32(request, 12);
        int answer;
        if (type == DHCPDISCOVER) {
            answer = DHCPOFFER;
        } else if (type == DHCPREQUEST) {
            int wanted = requested != 0 ? requested : clientAddress;
            answer = wanted == 0 || wanted == Subnet.LEASED ? DHCPACK : DHCPNAK;
        } else if (type == DHCPINFORM) {
            answer = DHCPACK;
        } else {
            return; // DECLINE, RELEASE: nothing to answer
        }
        reply.send(Subnet.GATEWAY, DHCP_SERVER_PORT, Packets.BROADCAST_IP, DHCP_CLIENT_PORT,
            dhcpReply(request, answer, type == DHCPINFORM));
    }

    private static byte[] dhcpReply(byte[] request, int type, boolean inform) {
        byte[] reply = new byte[300];
        reply[0] = 2;  // BOOTREPLY
        reply[1] = 1;  // Ethernet
        reply[2] = 6;
        System.arraycopy(request, 4, reply, 4, 4);   // xid
        System.arraycopy(request, 10, reply, 10, 2); // flags
        System.arraycopy(request, 12, reply, 12, 4); // ciaddr
        if (type != DHCPNAK && !inform) {
            Packets.put32(reply, 16, Subnet.LEASED);
        }
        Packets.put32(reply, 20, Subnet.GATEWAY);
        System.arraycopy(request, 24, reply, 24, 4 + 16); // giaddr, chaddr
        Packets.put32(reply, 236, DHCP_MAGIC);
        int i = 240;
        reply[i++] = 53;
        reply[i++] = 1;
        reply[i++] = (byte) type;
        i = option32(reply, i, 54, Subnet.GATEWAY);
        if (type != DHCPNAK) {
            if (!inform) {
                i = option32(reply, i, 51, LEASE_SECONDS);
            }
            i = option32(reply, i, 1, Subnet.MASK);
            i = option32(reply, i, 3, Subnet.GATEWAY);
            i = option32(reply, i, 6, Subnet.NAME_SERVER);
        }
        reply[i] = (byte) 255;
        return reply;
    }

    private static int option32(byte[] b, int i, int code, int value) {
        b[i] = (byte) code;
        b[i + 1] = 4;
        Packets.put32(b, i + 2, value);
        return i + 6;
    }

    // ---------------- DNS ----------------

    private void dns(int sourceIp, int sourcePort, byte[] query, Reply reply) {
        if (query.length < 12 || (query[2] & 0x80) != 0 || Packets.u16(query, 4) != 1) {
            return; // not a single-question query
        }
        int i = 12;
        List<String> labels = new ArrayList<>();
        while (i < query.length && query[i] != 0) {
            int length = query[i] & 0xFF;
            if (length > 63 || i + 1 + length > query.length) {
                return; // a compressed or malformed question
            }
            labels.add(new String(query, i + 1, length, StandardCharsets.US_ASCII));
            i += 1 + length;
        }
        int questionEnd = i + 5;
        if (questionEnd > query.length) {
            return;
        }
        int type = Packets.u16(query, i + 1);
        int queryClass = Packets.u16(query, i + 3);
        String name = String.join(".", labels);
        if (type != 1 || queryClass != 1 || name.isEmpty()) {
            reply.send(Subnet.NAME_SERVER, DNS_PORT, sourceIp, sourcePort, dnsAnswer(query, questionEnd, 0, List.of()));
            return;
        }
        try {
            resolver.execute(() -> {
                List<Integer> addresses = new ArrayList<>();
                int rcode = 0;
                try {
                    for (InetAddress address : InetAddress.getAllByName(name)) {
                        if (address instanceof Inet4Address) {
                            addresses.add(Packets.ipOf(address));
                        }
                    }
                } catch (UnknownHostException e) {
                    rcode = 3; // NXDOMAIN
                }
                byte[] answer = dnsAnswer(query, questionEnd, rcode, addresses);
                completions.add(() -> reply.send(Subnet.NAME_SERVER, DNS_PORT, sourceIp, sourcePort, answer));
            });
        } catch (RejectedExecutionException e) {
            // closed: no answer
        }
    }

    private static byte[] dnsAnswer(byte[] query, int questionEnd, int rcode, List<Integer> addresses) {
        byte[] answer = new byte[questionEnd + addresses.size() * 16];
        System.arraycopy(query, 0, answer, 0, questionEnd);
        answer[2] = (byte) (0x80 | (query[2] & 0x79));  // QR, opcode and RD from the query
        answer[3] = (byte) (0x80 | rcode);              // RA
        Packets.put16(answer, 4, 1);
        Packets.put16(answer, 6, addresses.size());
        Packets.put16(answer, 8, 0);
        Packets.put16(answer, 10, 0);
        int i = questionEnd;
        for (int address : addresses) {
            Packets.put16(answer, i, 0xC00C); // the name in the question
            Packets.put16(answer, i + 2, 1);
            Packets.put16(answer, i + 4, 1);
            Packets.put32(answer, i + 6, ANSWER_TTL_SECONDS);
            Packets.put16(answer, i + 10, 4);
            Packets.put32(answer, i + 12, address);
            i += 16;
        }
        return answer;
    }
}
