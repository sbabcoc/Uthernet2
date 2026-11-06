package com.nordstrom.uthernet.net;

import com.nordstrom.uthernet.w5100.Network;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;

/**
 * The host side of sockets in TCP and UDP mode: non-blocking NIO channels,
 * with guest addresses translated through {@link Subnet}. A failure on the
 * host shows up as {@link Network.StreamConnection#isFailed}, which the
 * chip reports as a timeout, never as an exception on the emulation thread.
 */
final class HostSockets {

    private HostSockets() {
    }

    /** Reports a host-side problem the guest can't see; the card has nowhere better to say it. */
    static void log(String message, Exception e) {
        System.err.println("Uthernet II: " + message + (e == null ? "" : ": " + e));
    }

    // ---------------- TCP ----------------

    /** A TCP connection on a host {@link SocketChannel}. */
    static final class Stream implements Network.StreamConnection {

        private final SocketChannel channel;
        private final int remoteIp;
        private final int remotePort;
        private boolean connecting;
        private boolean failed;

        /** Starts connecting to a guest-side address. */
        static Stream connect(int ip, int port) {
            SocketChannel channel = null;
            try {
                channel = SocketChannel.open();
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                boolean connected = channel.connect(Subnet.toHost(ip, port));
                return new Stream(channel, ip, port, !connected, false);
            } catch (IOException | RuntimeException e) {
                log("connecting to " + Packets.dotted(ip) + ":" + port + " failed", e);
                closeQuietly(channel);
                return new Stream(null, ip, port, false, true);
            }
        }

        /** Wraps a connection a listener accepted. */
        static Stream accepted(SocketChannel channel) throws IOException {
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            SocketAddress remote = channel.getRemoteAddress();
            return new Stream(channel, Subnet.fromHost(Packets.ipOf(remote)), Packets.portOf(remote), false, false);
        }

        private Stream(SocketChannel channel, int remoteIp, int remotePort, boolean connecting, boolean failed) {
            this.channel = channel;
            this.remoteIp = remoteIp;
            this.remotePort = remotePort;
            this.connecting = connecting;
            this.failed = failed;
        }

        @Override
        public boolean isConnecting() {
            if (connecting && !failed) {
                try {
                    connecting = !channel.finishConnect();
                } catch (IOException e) {
                    failed = true;
                    connecting = false;
                }
            }
            return connecting;
        }

        @Override
        public boolean isFailed() {
            return failed;
        }

        @Override
        public int remoteIp() {
            return remoteIp;
        }

        @Override
        public int remotePort() {
            return remotePort;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (failed || connecting) {
                return 0;
            }
            try {
                return channel.read(ByteBuffer.wrap(buffer, offset, length));
            } catch (IOException e) {
                failed = true;
                return 0;
            }
        }

        @Override
        public int write(byte[] buffer, int offset, int length) {
            if (failed || connecting) {
                return 0;
            }
            try {
                return channel.write(ByteBuffer.wrap(buffer, offset, length));
            } catch (IOException e) {
                failed = true;
                return 0;
            }
        }

        @Override
        public void shutdownOutput() {
            if (channel != null && !failed) {
                try {
                    channel.shutdownOutput();
                } catch (IOException e) {
                    failed = true;
                }
            }
        }

        @Override
        public void close() {
            closeQuietly(channel);
        }
    }

    /** A TCP listener on a host {@link ServerSocketChannel}, bound to the guest's own port number. */
    static final class Listener implements Network.StreamListener {

        private final ServerSocketChannel channel;

        static Listener listen(int port) {
            ServerSocketChannel channel = null;
            try {
                channel = ServerSocketChannel.open(StandardProtocolFamily.INET);
                channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
                channel.configureBlocking(false);
                channel.bind(new InetSocketAddress(port));
                return new Listener(channel);
            } catch (IOException | RuntimeException e) {
                log("can't listen on host port " + port, e);
                closeQuietly(channel);
                return new Listener(null); // stays in LISTEN, as a chip whose port nobody can reach would
            }
        }

        private Listener(ServerSocketChannel channel) {
            this.channel = channel;
        }

        @Override
        public Network.StreamConnection accept() {
            if (channel == null) {
                return null;
            }
            SocketChannel accepted = null;
            try {
                accepted = channel.accept();
                return accepted == null ? null : Stream.accepted(accepted);
            } catch (IOException e) {
                closeQuietly(accepted);
                return null;
            }
        }

        @Override
        public void close() {
            closeQuietly(channel);
        }
    }

    // ---------------- UDP ----------------

    /**
     * A UDP endpoint: datagrams for the virtual network's own services are
     * answered in-process; everything else goes out a host
     * {@link DatagramChannel} on an ephemeral port.
     */
    static final class Datagrams implements Network.DatagramEndpoint {

        private static final int MAX_DATAGRAM = 65_507;

        private final UdpServices services;
        private final Deque<Network.Datagram> received = new ArrayDeque<>();
        private final ByteBuffer receiveBuffer = ByteBuffer.allocate(MAX_DATAGRAM);
        private DatagramChannel channel;
        private boolean closed;

        Datagrams(UdpServices services) {
            this.services = services;
        }

        @Override
        public void send(int ip, int port, byte[] payload) {
            if (closed) {
                return;
            }
            boolean local = services.handle(Subnet.LEASED, 0, ip, port, payload,
                (fromIp, fromPort, toIp, toPort, reply) -> {
                    if (!closed) {
                        received.add(new Network.Datagram(fromIp, fromPort, reply));
                    }
                });
            if (local || Subnet.isBroadcast(ip)) {
                return;
            }
            try {
                channel().send(ByteBuffer.wrap(payload), Subnet.toHost(ip, port));
            } catch (IOException e) {
                log("sending to " + Packets.dotted(ip) + ":" + port + " failed", e);
            }
        }

        @Override
        public Network.Datagram peek() {
            if (received.isEmpty() && channel != null) {
                try {
                    receiveBuffer.clear();
                    SocketAddress from = channel.receive(receiveBuffer);
                    if (from != null) {
                        receiveBuffer.flip();
                        byte[] payload = Arrays.copyOf(receiveBuffer.array(), receiveBuffer.limit());
                        received.add(new Network.Datagram(Subnet.fromHost(Packets.ipOf(from)),
                            Packets.portOf(from), payload));
                    }
                } catch (IOException e) {
                    log("receiving failed", e);
                }
            }
            return received.peek();
        }

        @Override
        public void drop() {
            received.poll();
        }

        @Override
        public void close() {
            closed = true;
            received.clear();
            closeQuietly(channel);
        }

        private DatagramChannel channel() throws IOException {
            if (channel == null) {
                channel = DatagramChannel.open(StandardProtocolFamily.INET);
                channel.configureBlocking(false);
                channel.bind(new InetSocketAddress(0));
            }
            return channel;
        }
    }

    static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException e) {
                // nothing more to release
            }
        }
    }
}
