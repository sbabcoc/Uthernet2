package com.nordstrom.uthernet.w5100;

/**
 * Everything on the far side of the W5100's Ethernet jack, as the chip
 * model sees it.
 * <p>
 * The real chip runs its own TCP/IP stack and puts frames on the wire.
 * This model splits that in two, along the line the chip's own register
 * interface draws. For sockets in TCP and UDP mode, the guest only ever
 * sees the chip's socket registers and buffers, never a frame, so the
 * stack itself is the host's: {@link #connect}, {@link #listen} and
 * {@link #openDatagrams} hand back host-backed connections. For MACRAW and
 * IPRAW sockets the guest does see frames, so those go through
 * {@link #transmit} and {@link #peekFrame}.
 * <p>
 * Addresses are IPv4 addresses packed big-endian into an {@code int}, the
 * order the chip's registers hold them in. Every method is called on the
 * emulation thread, and none may block.
 */
public interface Network {

    /**
     * Starts a TCP connection, for a socket's CONNECT command.
     *
     * @param ip   the destination address (Sn_DIPR)
     * @param port the destination port (Sn_DPORT)
     * @return the connection, which may still be connecting
     */
    StreamConnection connect(int ip, int port);

    /**
     * Starts accepting TCP connections, for a socket's LISTEN command.
     *
     * @param port the port to listen on (Sn_PORT)
     * @return the listener
     */
    StreamListener listen(int port);

    /**
     * Opens a UDP endpoint, for a socket's OPEN command in UDP mode.
     *
     * @return the endpoint
     */
    DatagramEndpoint openDatagrams();

    /**
     * Puts an Ethernet frame (destination MAC through payload, no FCS) on the wire.
     *
     * @param frame the frame
     */
    void transmit(byte[] frame);

    /**
     * The next frame arriving from the wire, left in place until
     * {@link #dropFrame} -- so a socket whose receive buffer is full can
     * leave it to be taken later.
     *
     * @return the next frame, or null if none has arrived
     */
    byte[] peekFrame();

    /** Discards the frame {@link #peekFrame} returned. */
    void dropFrame();

    /**
     * Lets the network do whatever it does between bus accesses: move
     * bytes to and from the host, run timers.
     *
     * @param micros the time, in microseconds of emulated time, from any fixed origin
     */
    void poll(long micros);

    /** Releases every host resource the network holds. */
    void close();

    /** A TCP connection, made by {@link #connect} or accepted by a {@link StreamListener}. */
    interface StreamConnection {

        /** @return true while the connection is still being made */
        boolean isConnecting();

        /** @return true once the connection has failed or been reset; nothing more will move */
        boolean isFailed();

        /** @return the peer's address */
        int remoteIp();

        /** @return the peer's port */
        int remotePort();

        /**
         * Reads what the peer has sent.
         *
         * @param buffer where to put the bytes
         * @param offset where in {@code buffer} to start
         * @param length the most bytes to read
         * @return the bytes read, 0 if none are waiting, or -1 once the peer has closed its side
         */
        int read(byte[] buffer, int offset, int length);

        /**
         * Sends bytes to the peer, as many as the host will take now.
         *
         * @param buffer the bytes
         * @param offset where in {@code buffer} they start
         * @param length how many
         * @return how many were taken, possibly 0
         */
        int write(byte[] buffer, int offset, int length);

        /** Closes the sending side, so the peer sees end of stream. */
        void shutdownOutput();

        /** Closes the connection. */
        void close();
    }

    /** A TCP listener made by {@link #listen}. */
    interface StreamListener {

        /** @return the next incoming connection, or null if none is waiting */
        StreamConnection accept();

        /** Stops listening. */
        void close();
    }

    /** A UDP endpoint made by {@link #openDatagrams}. */
    interface DatagramEndpoint {

        /**
         * Sends one datagram.
         *
         * @param ip      the destination address
         * @param port    the destination port
         * @param payload the payload
         */
        void send(int ip, int port, byte[] payload);

        /** @return the next datagram received, left in place until {@link #drop}; null if none */
        Datagram peek();

        /** Discards the datagram {@link #peek} returned. */
        void drop();

        /** Closes the endpoint. */
        void close();
    }

    /**
     * A received datagram.
     *
     * @param ip      the sender's address
     * @param port    the sender's port
     * @param payload the payload
     */
    record Datagram(int ip, int port, byte[] payload) {
    }
}
