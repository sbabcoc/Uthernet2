package com.nordstrom.uthernet.net;

import com.nordstrom.uthernet.w5100.Network;

/**
 * The card's network: a private virtual LAN (see {@link Subnet}) reaching
 * the Internet through the host's own sockets, with no privileges, native
 * code or host setup needed, so it behaves the same on macOS, Windows and
 * Linux, PRoot included.
 * <p>
 * Sockets in TCP and UDP mode go straight to host channels
 * ({@link HostSockets}); frames from MACRAW and IPRAW sockets go to the
 * {@link Gateway}. Both share the same {@link UdpServices}, so DHCP and
 * name lookups work whichever way the guest's software sends them.
 */
public final class HostNetwork implements Network {

    private final UdpServices services = new UdpServices();
    private final Gateway gateway = new Gateway(services);
    private long now;

    @Override
    public StreamConnection connect(int ip, int port) {
        return HostSockets.Stream.connect(ip, port);
    }

    @Override
    public StreamListener listen(int port) {
        return HostSockets.Listener.listen(port);
    }

    @Override
    public DatagramEndpoint openDatagrams() {
        return new HostSockets.Datagrams(services);
    }

    @Override
    public void transmit(byte[] frame) {
        gateway.fromGuest(frame, now);
    }

    @Override
    public byte[] peekFrame() {
        return gateway.peek();
    }

    @Override
    public void dropFrame() {
        gateway.drop();
    }

    @Override
    public void poll(long micros) {
        now = micros;
        services.runCompletions();
        gateway.poll(micros);
    }

    @Override
    public void close() {
        gateway.close();
        services.close();
    }
}
