package com.nordstrom.uthernet.net;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * The addressing plan of the network the card is plugged into: a private
 * /24 with a gateway, a name server and one address to lease, the same
 * plan QEMU's user-mode networking uses, so it's familiar and unlikely to
 * collide with the host's own LAN. Only the guest sees these addresses;
 * {@link #toHost} and {@link #fromHost} translate at the boundary.
 * <p>
 * The gateway stands for the host itself: a guest connecting to it
 * reaches the host's loopback interface. Every other address is passed to
 * the host unchanged, so a guest configured by hand for the host's real
 * LAN works too.
 */
final class Subnet {

    static final int NETWORK = 0x0A000200;      // 10.0.2.0
    static final int MASK = 0xFFFFFF00;         // 255.255.255.0
    static final int GATEWAY = 0x0A000202;      // 10.0.2.2
    static final int NAME_SERVER = 0x0A000203;  // 10.0.2.3
    static final int LEASED = 0x0A00020F;       // 10.0.2.15
    static final int DIRECTED_BROADCAST = NETWORK | ~MASK;

    /** The gateway's MAC: locally administered, 52:55 then its address, as QEMU's is. */
    static final byte[] GATEWAY_MAC = {0x52, 0x55, 0x0A, 0x00, 0x02, 0x02};

    private Subnet() {
    }

    /**
     * Where a guest-side destination is on the host.
     *
     * @param ip   the address the guest used
     * @param port the port the guest used
     * @return the host address to use
     */
    static InetSocketAddress toHost(int ip, int port) {
        InetAddress address = ip == GATEWAY ? InetAddress.getLoopbackAddress() : Packets.inetAddress(ip);
        return new InetSocketAddress(address, port);
    }

    /**
     * How the guest sees a host-side address: loopback is the gateway.
     *
     * @param ip a host-side IPv4 address
     * @return the address to show the guest
     */
    static int fromHost(int ip) {
        return (ip >>> 24) == 127 ? GATEWAY : ip;
    }

    /** @return true for addresses no host socket can reach: the limited and directed broadcasts */
    static boolean isBroadcast(int ip) {
        return ip == Packets.BROADCAST_IP || ip == DIRECTED_BROADCAST;
    }
}
