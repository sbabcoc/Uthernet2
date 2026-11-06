package com.nordstrom.uthernet.w5100;

import java.util.Arrays;

/**
 * The WIZnet W5100 Ethernet controller, as a host sees it through its
 * parallel bus: a 32 KB address space holding the common registers
 * ($0000-$002F), four sets of socket registers ($0400-$07FF), 8 KB of
 * transmit memory ($4000-$5FFF) and 8 KB of receive memory ($6000-$7FFF).
 * Register addresses, reset values and command semantics follow the W5100
 * datasheet; HARDWARE-REFERENCE.md lists where each comes from.
 * <p>
 * The bus side is {@link #busRead}/{@link #busWrite}: two address lines
 * and a data byte. With the IND bit of MR clear (direct mode), those two
 * lines are the chip's ADDR1-0 and ADDR14-2 are pulled down, so the four
 * bus addresses reach chip addresses $0000-$0003. With IND set (indirect
 * mode) they select MR, the two halves of the indirect address register,
 * and the data register.
 * <p>
 * The network side is a {@link Network}. Every method runs on the
 * emulation thread.
 */
public final class W5100 {

    // Common registers
    static final int MR = 0x0000;
    static final int GAR = 0x0001;
    static final int SUBR = 0x0005;
    static final int SHAR = 0x0009;
    static final int SIPR = 0x000F;
    static final int IR = 0x0015;
    static final int IMR = 0x0016;
    static final int RTR = 0x0017;
    static final int RCR = 0x0019;
    static final int RMSR = 0x001A;
    static final int TMSR = 0x001B;
    static final int PATR = 0x001C;
    static final int PTIMER = 0x0028;
    static final int PMAGIC = 0x0029;
    static final int UIPR = 0x002A;
    static final int UPORT = 0x002E;
    static final int COMMON_END = 0x0030;

    static final int MR_RST = 0x80;
    static final int MR_AI = 0x02;
    static final int MR_IND = 0x01;

    // Socket registers, as offsets from a socket's base ($0400 + n * $100)
    static final int SOCKET_BASE = 0x0400;
    static final int SOCKET_END = 0x0800;
    static final int SN_MR = 0x00;
    static final int SN_CR = 0x01;
    static final int SN_IR = 0x02;
    static final int SN_SR = 0x03;
    static final int SN_PORT = 0x04;
    static final int SN_DHAR = 0x06;
    static final int SN_DIPR = 0x0C;
    static final int SN_DPORT = 0x10;
    static final int SN_MSS = 0x12;
    static final int SN_PROTO = 0x14;
    static final int SN_TOS = 0x15;
    static final int SN_TTL = 0x16;
    static final int SN_TX_FSR = 0x20;
    static final int SN_TX_RD = 0x22;
    static final int SN_TX_WR = 0x24;
    static final int SN_RX_RSR = 0x26;
    static final int SN_RX_RD = 0x28;
    static final int SN_REGISTERS_END = 0x2A;

    // Sn_MR protocol field (P3-P0) and flags
    static final int PROTOCOL_MASK = 0x0F;
    static final int PROTOCOL_TCP = 0x01;
    static final int PROTOCOL_UDP = 0x02;
    static final int PROTOCOL_IPRAW = 0x03;
    static final int PROTOCOL_MACRAW = 0x04;
    static final int SN_MR_MF = 0x40;

    // Sn_CR commands
    static final int CMD_OPEN = 0x01;
    static final int CMD_LISTEN = 0x02;
    static final int CMD_CONNECT = 0x04;
    static final int CMD_DISCON = 0x08;
    static final int CMD_CLOSE = 0x10;
    static final int CMD_SEND = 0x20;
    static final int CMD_SEND_MAC = 0x21;
    static final int CMD_SEND_KEEP = 0x22;
    static final int CMD_RECV = 0x40;

    // Sn_IR bits
    static final int IR_CON = 0x01;
    static final int IR_DISCON = 0x02;
    static final int IR_RECV = 0x04;
    static final int IR_TIMEOUT = 0x08;
    static final int IR_SEND_OK = 0x10;

    // Sn_SR values
    static final int SOCK_CLOSED = 0x00;
    static final int SOCK_INIT = 0x13;
    static final int SOCK_LISTEN = 0x14;
    static final int SOCK_SYNSENT = 0x15;
    static final int SOCK_ESTABLISHED = 0x17;
    static final int SOCK_FIN_WAIT = 0x18;
    static final int SOCK_CLOSE_WAIT = 0x1C;
    static final int SOCK_UDP = 0x22;
    static final int SOCK_IPRAW = 0x32;
    static final int SOCK_MACRAW = 0x42;

    static final int TX_MEMORY = 0x4000;
    static final int RX_MEMORY = 0x6000;
    static final int MEMORY_SIZE = 0x8000;

    /** The chip decodes ADDR14-0: an indirect address's top bit reaches nothing. */
    private static final int ADDRESS_MASK = 0x7FFF;

    private static final int ETHERNET_HEADER = 14;
    private static final int ETHERTYPE_IPV4 = 0x0800;
    private static final byte[] BROADCAST = {-1, -1, -1, -1, -1, -1};

    private final byte[] memory = new byte[MEMORY_SIZE];
    private final Socket[] sockets = new Socket[4];
    private final Network network;
    private int indirectAddress;
    private short ipIdentification;

    /**
     * Powers up the chip, with every register at its reset value.
     *
     * @param network what the chip's Ethernet jack is plugged into
     */
    public W5100(Network network) {
        this.network = network;
        for (int n = 0; n < sockets.length; n++) {
            sockets[n] = new Socket(n);
        }
        initializeRegisters();
    }

    // ------------------------------------------------------------------
    // Bus interface
    // ------------------------------------------------------------------

    /**
     * A read on the host bus.
     *
     * @param line the two address lines, 0-3
     * @return the byte read
     */
    public int busRead(int line) {
        line &= 3;
        if ((memory[MR] & MR_IND) == 0) {
            return read(line);
        }
        switch (line) {
            case 0:
                return read(MR);
            case 1:
                return indirectAddress >> 8;
            case 2:
                return indirectAddress & 0xFF;
            default:
                int value = read(indirectAddress & ADDRESS_MASK);
                autoIncrement();
                return value;
        }
    }

    /**
     * A write on the host bus.
     *
     * @param line  the two address lines, 0-3
     * @param value the byte written
     */
    public void busWrite(int line, int value) {
        line &= 3;
        value &= 0xFF;
        if ((memory[MR] & MR_IND) == 0) {
            write(line, value);
            return;
        }
        switch (line) {
            case 0:
                write(MR, value);
                break;
            case 1:
                indirectAddress = (value << 8) | (indirectAddress & 0x00FF);
                break;
            case 2:
                indirectAddress = (indirectAddress & 0xFF00) | value;
                break;
            default:
                write(indirectAddress & ADDRESS_MASK, value);
                autoIncrement();
                break;
        }
    }

    /**
     * Steps the indirect address after a data register access, when AI is
     * set. Per the Uthernet II manual (pp. 12-13): running off the end of
     * transmit memory wraps to its start, off the end of receive memory to
     * its start, and past $FFFF to $E000; elsewhere above $7FFF the address
     * advances as usual.
     */
    private void autoIncrement() {
        if ((memory[MR] & MR_AI) == 0) {
            return;
        }
        indirectAddress = (indirectAddress + 1) & 0xFFFF;
        switch (indirectAddress) {
            case RX_MEMORY:
            case MEMORY_SIZE:
                indirectAddress -= 0x2000;
                break;
            case 0x0000:
                indirectAddress = 0xE000;
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------
    // Address space
    // ------------------------------------------------------------------

    /**
     * Reads a chip address.
     *
     * @param address 0-$7FFF
     * @return the byte there
     */
    public int read(int address) {
        if (address >= SOCKET_BASE && address < SOCKET_END) {
            return sockets[(address - SOCKET_BASE) >> 8].readRegister(address & 0xFF);
        }
        if (address == IR) {
            return interruptRegister();
        }
        return memory[address] & 0xFF;
    }

    /**
     * Writes a chip address. Read-only registers and reserved space ignore it.
     *
     * @param address 0-$7FFF
     * @param value   the byte written
     */
    public void write(int address, int value) {
        if (address >= SOCKET_BASE && address < SOCKET_END) {
            sockets[(address - SOCKET_BASE) >> 8].writeRegister(address & 0xFF, value);
        } else if (address >= TX_MEMORY) {
            memory[address] = (byte) value;
        } else if (address < COMMON_END) {
            writeCommon(address, value);
        }
    }

    private void writeCommon(int address, int value) {
        if (address == MR) {
            if ((value & MR_RST) != 0) {
                softwareReset();
            } else {
                memory[MR] = (byte) value;
            }
        } else if (address == IR) {
            memory[IR] &= (byte) ~(value & 0xE0); // CONFLICT, UNREACH, PPPoE: write 1 to clear
        } else if (address == RMSR || address == TMSR) {
            memory[address] = (byte) value;
            allocateBuffers();
        } else if (address == PATR || address == PATR + 1 || (address >= UIPR && address < COMMON_END)) {
            // read-only
        } else if (address <= TMSR || address == PTIMER || address == PMAGIC) {
            memory[address] = (byte) value; // GAR, SUBR, SHAR, SIPR, IMR, RTR, RCR, PTIMER, PMAGIC
        }
    }

    /** IR: bits 7-5 are latched events; bits 3-0 report which sockets have a nonzero Sn_IR. */
    private int interruptRegister() {
        int value = memory[IR] & 0xE0;
        for (Socket socket : sockets) {
            if (socket.interrupts() != 0) {
                value |= 1 << socket.index;
            }
        }
        return value;
    }

    /**
     * The /INT pin: asserted while any interrupt that IMR enables is pending.
     *
     * @return true while /INT is asserted
     */
    public boolean isInterruptAsserted() {
        return (interruptRegister() & memory[IMR] & 0xEF) != 0;
    }

    // ------------------------------------------------------------------
    // Reset
    // ------------------------------------------------------------------

    /**
     * A software reset (MR's RST bit): every register back to its reset
     * value and every socket closed, but the indirect address register
     * keeps its value (Uthernet II manual, p. 10).
     */
    private void softwareReset() {
        for (Socket socket : sockets) {
            socket.releaseHost();
        }
        initializeRegisters();
    }

    /**
     * The /RESET pin asserted: as a software reset, and the indirect
     * address register returns to 0 as well (Uthernet II manual, p. 10).
     */
    public void hardwareReset() {
        softwareReset();
        indirectAddress = 0;
    }

    /** Powers the chip off for good, releasing what its sockets hold on the host. */
    public void close() {
        for (Socket socket : sockets) {
            socket.releaseHost();
        }
        network.close();
    }

    private void initializeRegisters() {
        Arrays.fill(memory, 0, SOCKET_END, (byte) 0);
        memory[RTR] = 0x07;
        memory[RTR + 1] = (byte) 0xD0;
        memory[RCR] = 0x08;
        memory[RMSR] = 0x55;
        memory[TMSR] = 0x55;
        memory[PTIMER] = 0x28;
        for (Socket socket : sockets) {
            socket.initialize();
        }
        allocateBuffers();
    }

    /**
     * Assigns transmit and receive memory to the sockets from TMSR and
     * RMSR: two bits per socket choose 1, 2, 4 or 8 KB, given out from
     * socket 0 up, and a socket whose share doesn't fit in the 8 KB left
     * gets none.
     */
    private void allocateBuffers() {
        int txSizes = memory[TMSR] & 0xFF;
        int rxSizes = memory[RMSR] & 0xFF;
        int txBase = TX_MEMORY;
        int rxBase = RX_MEMORY;
        for (Socket socket : sockets) {
            int txSize = 0x400 << ((txSizes >> (socket.index * 2)) & 3);
            int rxSize = 0x400 << ((rxSizes >> (socket.index * 2)) & 3);
            socket.txSize = txBase + txSize <= RX_MEMORY ? txSize : 0;
            socket.txBase = txBase;
            txBase += socket.txSize;
            socket.rxSize = rxBase + rxSize <= MEMORY_SIZE ? rxSize : 0;
            socket.rxBase = rxBase;
            rxBase += socket.rxSize;
        }
    }

    // ------------------------------------------------------------------
    // Network service
    // ------------------------------------------------------------------

    /**
     * Does the chip's own work between bus accesses: services the network,
     * moves data between the sockets and the host, and delivers arriving
     * frames to MACRAW and IPRAW sockets.
     *
     * @param micros the time, in microseconds of emulated time
     */
    public void poll(long micros) {
        network.poll(micros);
        for (Socket socket : sockets) {
            socket.service();
        }
        deliverFrames();
    }

    private void deliverFrames() {
        byte[] frame;
        while ((frame = network.peekFrame()) != null) {
            if (frame.length >= ETHERNET_HEADER && !deliver(frame)) {
                return; // a socket that wants it has no room yet
            }
            network.dropFrame();
        }
    }

    /**
     * Offers a frame to the sockets: an IPRAW socket whose protocol matches
     * takes an IPv4 packet addressed to this chip first, then socket 0 in
     * MACRAW mode.
     *
     * @return false if the socket that should take it has no room yet
     */
    private boolean deliver(byte[] frame) {
        boolean forUs = macMatches(frame, SHAR) || Arrays.equals(frame, 0, 6, BROADCAST, 0, 6);
        if (forUs && etherType(frame) == ETHERTYPE_IPV4 && frame.length >= ETHERNET_HEADER + 20) {
            int ihl = (frame[ETHERNET_HEADER] & 0x0F) * 4;
            int protocol = frame[ETHERNET_HEADER + 9] & 0xFF;
            int totalLength = Math.min(u16(frame, ETHERNET_HEADER + 2), frame.length - ETHERNET_HEADER);
            for (Socket socket : sockets) {
                if (socket.status == SOCK_IPRAW && socket.register(SN_PROTO) == protocol && totalLength >= ihl) {
                    return socket.receiveIpRaw(frame, ETHERNET_HEADER + ihl, totalLength - ihl);
                }
            }
        }
        Socket socket0 = sockets[0];
        if (socket0.status == SOCK_MACRAW && (forUs || (socket0.register(SN_MR) & SN_MR_MF) == 0)) {
            return socket0.receiveMacRaw(frame);
        }
        return true; // no socket takes it: dropped
    }

    private static int etherType(byte[] frame) {
        return u16(frame, 12);
    }

    private boolean macMatches(byte[] frame, int registerAddress) {
        return Arrays.equals(frame, 0, 6, memory, registerAddress, registerAddress + 6);
    }

    private int ipRegister(int address) {
        return ((memory[address] & 0xFF) << 24) | ((memory[address + 1] & 0xFF) << 16)
            | ((memory[address + 2] & 0xFF) << 8) | (memory[address + 3] & 0xFF);
    }

    private int u16Register(int address) {
        return ((memory[address] & 0xFF) << 8) | (memory[address + 1] & 0xFF);
    }

    private void setU16Register(int address, int value) {
        memory[address] = (byte) (value >> 8);
        memory[address + 1] = (byte) value;
    }

    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    // ------------------------------------------------------------------
    // Sockets
    // ------------------------------------------------------------------

    /** One of the four sockets: its registers live in {@link #memory}; this holds its state and host side. */
    private final class Socket {

        final int index;
        final int base;
        int status;
        int txBase;
        int txSize;
        int rxBase;
        int rxSize;
        /** Where the next received byte goes: the receive write pointer, which has no register of its own. */
        int rxWrite;
        /** Sn_RX_RD as of the last RECV command; received data runs from here to {@link #rxWrite}. */
        int rxReadCommitted;
        /** Sn_TX_WR as of the last SEND, while a TCP send is still going out. */
        int sendTarget;
        boolean sending;
        boolean disconnectAfterSend;
        Network.StreamConnection connection;
        Network.StreamListener listener;
        Network.DatagramEndpoint datagrams;

        Socket(int index) {
            this.index = index;
            this.base = SOCKET_BASE + index * 0x100;
        }

        void initialize() {
            status = SOCK_CLOSED;
            Arrays.fill(memory, base + SN_DHAR, base + SN_DHAR + 6, (byte) 0xFF);
            memory[base + SN_TTL] = (byte) 0x80;
            resetPointers();
        }

        private void resetPointers() {
            setU16Register(base + SN_TX_RD, 0);
            setU16Register(base + SN_TX_WR, 0);
            setU16Register(base + SN_RX_RD, 0);
            rxWrite = 0;
            rxReadCommitted = 0;
            sending = false;
            disconnectAfterSend = false;
        }

        int register(int offset) {
            return memory[base + offset] & 0xFF;
        }

        int interrupts() {
            return memory[base + SN_IR] & 0x1F;
        }

        private void raise(int bits) {
            memory[base + SN_IR] |= (byte) bits;
        }

        int readRegister(int offset) {
            switch (offset) {
                case SN_SR:
                    return status;
                case SN_TX_FSR:
                    return freeTransmitSpace() >> 8;
                case SN_TX_FSR + 1:
                    return freeTransmitSpace() & 0xFF;
                case SN_RX_RSR:
                    return receivedSize() >> 8;
                case SN_RX_RSR + 1:
                    return receivedSize() & 0xFF;
                default:
                    return offset < SN_REGISTERS_END ? memory[base + offset] & 0xFF : 0;
            }
        }

        void writeRegister(int offset, int value) {
            switch (offset) {
                case SN_CR:
                    command(value & 0xFF);
                    break;
                case SN_IR:
                    memory[base + SN_IR] &= (byte) ~value; // write 1 to clear
                    break;
                case SN_SR:
                case SN_TX_FSR:
                case SN_TX_FSR + 1:
                case SN_TX_RD:
                case SN_TX_RD + 1:
                case SN_RX_RSR:
                case SN_RX_RSR + 1:
                    break; // read-only
                default:
                    if (offset < SN_REGISTERS_END && (offset < 0x17 || offset >= SN_TX_FSR)) {
                        memory[base + offset] = (byte) value;
                    }
                    break;
            }
        }

        private int freeTransmitSpace() {
            int queued = (u16Register(base + SN_TX_WR) - u16Register(base + SN_TX_RD)) & 0xFFFF;
            return Math.max(0, txSize - queued);
        }

        private int receivedSize() {
            return (rxWrite - rxReadCommitted) & 0xFFFF;
        }

        private int freeReceiveSpace() {
            return rxSize - receivedSize();
        }

        // ---------------- commands ----------------

        private void command(int value) {
            switch (value) {
                case CMD_OPEN:
                    open();
                    break;
                case CMD_LISTEN:
                    if (status == SOCK_INIT) {
                        listener = network.listen(u16Register(base + SN_PORT));
                        status = SOCK_LISTEN;
                    }
                    break;
                case CMD_CONNECT:
                    if (status == SOCK_INIT) {
                        connection = network.connect(ipRegister(base + SN_DIPR), u16Register(base + SN_DPORT));
                        status = SOCK_SYNSENT;
                    }
                    break;
                case CMD_DISCON:
                    disconnect();
                    break;
                case CMD_CLOSE:
                    releaseHost();
                    status = SOCK_CLOSED;
                    break;
                case CMD_SEND:
                case CMD_SEND_MAC:
                    send();
                    break;
                case CMD_SEND_KEEP:
                    break; // the host's TCP keeps its own connections alive
                case CMD_RECV:
                    rxReadCommitted = u16Register(base + SN_RX_RD);
                    if (receivedSize() != 0) {
                        raise(IR_RECV);
                    }
                    break;
                default:
                    break;
            }
            memory[base + SN_CR] = 0; // cleared once the command is taken
        }

        private void open() {
            releaseHost();
            resetPointers();
            status = SOCK_CLOSED;
            int protocol = register(SN_MR) & PROTOCOL_MASK;
            if (protocol == PROTOCOL_TCP) {
                status = SOCK_INIT;
            } else if (protocol == PROTOCOL_UDP) {
                datagrams = network.openDatagrams();
                status = SOCK_UDP;
            } else if (protocol == PROTOCOL_IPRAW) {
                status = SOCK_IPRAW;
            } else if (protocol == PROTOCOL_MACRAW && index == 0) {
                status = SOCK_MACRAW;
            }
        }

        private void disconnect() {
            if (connection == null) {
                if (status == SOCK_INIT || status == SOCK_LISTEN) {
                    releaseHost();
                    status = SOCK_CLOSED;
                }
                return;
            }
            if (sending) {
                disconnectAfterSend = true;
            } else {
                finishDisconnect();
            }
        }

        private void finishDisconnect() {
            disconnectAfterSend = false;
            if (status == SOCK_CLOSE_WAIT) {
                releaseHost();
                status = SOCK_CLOSED;
                raise(IR_DISCON);
            } else {
                connection.shutdownOutput();
                status = SOCK_FIN_WAIT;
            }
        }

        private void send() {
            int txRead = u16Register(base + SN_TX_RD);
            int txWrite = u16Register(base + SN_TX_WR);
            switch (status) {
                case SOCK_ESTABLISHED:
                case SOCK_CLOSE_WAIT:
                    sendTarget = txWrite;
                    sending = true;
                    transmitStream();
                    return;
                case SOCK_UDP:
                    datagrams.send(ipRegister(base + SN_DIPR), u16Register(base + SN_DPORT),
                        transmitData(txRead, txWrite));
                    break;
                case SOCK_IPRAW:
                    network.transmit(ipRawFrame(transmitData(txRead, txWrite)));
                    break;
                case SOCK_MACRAW:
                    network.transmit(transmitData(txRead, txWrite));
                    break;
                default:
                    return;
            }
            setU16Register(base + SN_TX_RD, txWrite);
            raise(IR_SEND_OK);
        }

        /** The bytes queued in transmit memory from one pointer to another, wrapping within this socket's share. */
        private byte[] transmitData(int from, int to) {
            int length = txSize == 0 ? 0 : (to - from) & 0xFFFF;
            byte[] data = new byte[Math.min(length, txSize)];
            for (int i = 0; i < data.length; i++) {
                data[i] = memory[txBase + ((from + i) & (txSize - 1))];
            }
            return data;
        }

        /** An IPv4 packet for an IPRAW send, in a frame addressed to every station: the frame's addressing isn't guest-visible. */
        private byte[] ipRawFrame(byte[] payload) {
            byte[] frame = new byte[ETHERNET_HEADER + 20 + payload.length];
            System.arraycopy(BROADCAST, 0, frame, 0, 6);
            System.arraycopy(memory, SHAR, frame, 6, 6);
            frame[12] = 0x08;
            int ip = ETHERNET_HEADER;
            frame[ip] = 0x45;
            frame[ip + 1] = memory[base + SN_TOS];
            int totalLength = 20 + payload.length;
            frame[ip + 2] = (byte) (totalLength >> 8);
            frame[ip + 3] = (byte) totalLength;
            ipIdentification++;
            frame[ip + 4] = (byte) (ipIdentification >> 8);
            frame[ip + 5] = (byte) ipIdentification;
            frame[ip + 8] = memory[base + SN_TTL];
            frame[ip + 9] = memory[base + SN_PROTO];
            System.arraycopy(memory, SIPR, frame, ip + 12, 4);
            System.arraycopy(memory, base + SN_DIPR, frame, ip + 16, 4);
            int sum = 0;
            for (int i = 0; i < 20; i += 2) {
                sum += u16(frame, ip + i);
            }
            sum = (sum & 0xFFFF) + (sum >> 16);
            sum = ~((sum & 0xFFFF) + (sum >> 16));
            frame[ip + 10] = (byte) (sum >> 8);
            frame[ip + 11] = (byte) sum;
            System.arraycopy(payload, 0, frame, ip + 20, payload.length);
            return frame;
        }

        // ---------------- service ----------------

        void service() {
            switch (status) {
                case SOCK_SYNSENT:
                    if (connection.isFailed()) {
                        timeout();
                    } else if (!connection.isConnecting()) {
                        status = SOCK_ESTABLISHED;
                        raise(IR_CON);
                    }
                    break;
                case SOCK_LISTEN:
                    Network.StreamConnection accepted = listener.accept();
                    if (accepted != null) {
                        listener.close();
                        listener = null;
                        connection = accepted;
                        setIp(base + SN_DIPR, accepted.remoteIp());
                        setU16Register(base + SN_DPORT, accepted.remotePort());
                        status = SOCK_ESTABLISHED;
                        raise(IR_CON);
                    }
                    break;
                case SOCK_ESTABLISHED:
                case SOCK_CLOSE_WAIT:
                case SOCK_FIN_WAIT:
                    serviceStream();
                    break;
                case SOCK_UDP:
                    receiveDatagrams();
                    break;
                default:
                    break;
            }
        }

        private void serviceStream() {
            if (connection.isFailed()) {
                timeout();
                return;
            }
            if (sending) {
                transmitStream();
            }
            if (status != SOCK_CLOSE_WAIT) {
                receiveStream();
            }
        }

        /** Sends what the host will take of the bytes between Sn_TX_RD and the SEND's target, advancing Sn_TX_RD. */
        private void transmitStream() {
            int txRead = u16Register(base + SN_TX_RD);
            while (txRead != sendTarget && txSize != 0) {
                int offset = txRead & (txSize - 1);
                int length = Math.min((sendTarget - txRead) & 0xFFFF, txSize - offset);
                int written = connection.write(memory, txBase + offset, length);
                txRead = (txRead + written) & 0xFFFF;
                setU16Register(base + SN_TX_RD, txRead);
                if (written < length) {
                    return;
                }
            }
            sending = false;
            raise(IR_SEND_OK);
            if (disconnectAfterSend) {
                finishDisconnect();
            }
        }

        private void receiveStream() {
            int free;
            while ((free = freeReceiveSpace()) > 0) {
                int offset = rxWrite & (rxSize - 1);
                int length = Math.min(free, rxSize - offset);
                int read = connection.read(memory, rxBase + offset, length);
                if (read < 0) {
                    peerClosed();
                    return;
                }
                if (read == 0) {
                    return;
                }
                rxWrite = (rxWrite + read) & 0xFFFF;
                raise(IR_RECV);
            }
        }

        private void peerClosed() {
            if (status == SOCK_FIN_WAIT) {
                releaseHost();
                status = SOCK_CLOSED;
            } else {
                status = SOCK_CLOSE_WAIT;
            }
            raise(IR_DISCON);
        }

        private void timeout() {
            releaseHost();
            status = SOCK_CLOSED;
            raise(IR_TIMEOUT);
        }

        /** UDP: each datagram lands behind an 8-byte header of sender address, sender port and size. */
        private void receiveDatagrams() {
            Network.Datagram datagram;
            while ((datagram = datagrams.peek()) != null) {
                byte[] payload = datagram.payload();
                if (8 + payload.length > rxSize) {
                    datagrams.drop(); // can never fit
                    continue;
                }
                if (8 + payload.length > freeReceiveSpace()) {
                    return;
                }
                putReceived((byte) (datagram.ip() >> 24), (byte) (datagram.ip() >> 16),
                    (byte) (datagram.ip() >> 8), (byte) datagram.ip(),
                    (byte) (datagram.port() >> 8), (byte) datagram.port(),
                    (byte) (payload.length >> 8), (byte) payload.length);
                putReceived(payload, 0, payload.length);
                datagrams.drop();
                raise(IR_RECV);
            }
        }

        /** IPRAW: the packet's payload behind a 6-byte header of sender address and size. */
        boolean receiveIpRaw(byte[] frame, int payloadOffset, int payloadLength) {
            if (6 + payloadLength > rxSize) {
                return true; // can never fit: dropped
            }
            if (6 + payloadLength > freeReceiveSpace()) {
                return false;
            }
            int source = ETHERNET_HEADER + 12;
            putReceived(frame[source], frame[source + 1], frame[source + 2], frame[source + 3],
                (byte) (payloadLength >> 8), (byte) payloadLength);
            putReceived(frame, payloadOffset, payloadLength);
            raise(IR_RECV);
            return true;
        }

        /** MACRAW: the whole frame behind a 2-byte size that counts itself. */
        boolean receiveMacRaw(byte[] frame) {
            int size = frame.length + 2;
            if (size > rxSize) {
                return true; // can never fit: dropped
            }
            if (size > freeReceiveSpace()) {
                return false;
            }
            putReceived((byte) (size >> 8), (byte) size);
            putReceived(frame, 0, frame.length);
            raise(IR_RECV);
            return true;
        }

        private void putReceived(byte... bytes) {
            putReceived(bytes, 0, bytes.length);
        }

        private void putReceived(byte[] bytes, int offset, int length) {
            for (int i = 0; i < length; i++) {
                memory[rxBase + (rxWrite & (rxSize - 1))] = bytes[offset + i];
                rxWrite = (rxWrite + 1) & 0xFFFF;
            }
        }

        private void setIp(int address, int ip) {
            memory[address] = (byte) (ip >> 24);
            memory[address + 1] = (byte) (ip >> 16);
            memory[address + 2] = (byte) (ip >> 8);
            memory[address + 3] = (byte) ip;
        }

        /** Lets go of whatever this socket holds on the host. */
        void releaseHost() {
            if (connection != null) {
                connection.close();
                connection = null;
            }
            if (listener != null) {
                listener.close();
                listener = null;
            }
            if (datagrams != null) {
                datagrams.close();
                datagrams = null;
            }
            sending = false;
            disconnectAfterSend = false;
        }
    }

    // ------------------------------------------------------------------
    // Inspection
    // ------------------------------------------------------------------

    /**
     * The chip's MAC address (SHAR).
     *
     * @return a copy of the six bytes
     */
    public byte[] macAddress() {
        return Arrays.copyOfRange(memory, SHAR, SHAR + 6);
    }

    /**
     * A socket's status (Sn_SR).
     *
     * @param socket 0-3
     * @return the status code
     */
    public int socketStatus(int socket) {
        return sockets[socket].status;
    }
}
