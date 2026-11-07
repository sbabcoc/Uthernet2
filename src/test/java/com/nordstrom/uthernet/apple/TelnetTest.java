package com.nordstrom.uthernet.apple;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs TELNET (src/apple/telnet.s) on the 6502 against a scripted BBS on
 * the host's loopback, reached through the card's gateway at 10.0.2.2.
 */
class TelnetTest {

    private static final int IAC = 255, DONT = 254, DO = 253, WILL = 251, SB = 250, SE = 240;
    private static final int ECHO = 1, SGA = 3, TTYPE = 24, NAWS = 31;
    private static final byte ESC = 0x1B;

    /** A BBS that negotiates, draws a screen, asks where the cursor is, and reads a line. */
    private static final class Bbs implements Runnable {
        final ServerSocket server;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();
        volatile boolean ended;

        Bbs() throws IOException {
            server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        }

        @Override
        public void run() {
            try (Socket s = server.accept()) {
                s.setSoTimeout(20_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                out.write(bytes(IAC, DO, TTYPE, IAC, DO, NAWS, IAC, WILL, ECHO, IAC, WILL, SGA, IAC, DO, 99));
                readUntil(in, bytes(IAC, DO, SGA));
                out.write(bytes(IAC, SB, TTYPE, 1, IAC, SE));
                readUntil(in, "VT100".getBytes(StandardCharsets.US_ASCII));
                String screen = "\u001b[2J\u001b[HWelcome to the test BBS\r\n"
                    + "\u001b[5;10Hhere\u001b[6n";
                out.write(screen.getBytes(StandardCharsets.ISO_8859_1));
                readUntil(in, "R".getBytes(StandardCharsets.US_ASCII));
                byte[] wide = new byte[80];
                Arrays.fill(wide, (byte) ' ');
                wide[75] = 'Z';
                out.write("\u001b[7;1H".getBytes(StandardCharsets.US_ASCII));
                out.write(bytes(0xDA, 0xC4, 0xBF));                 // CP437 box corner, line, corner
                out.write("\u001b[9;1H".getBytes(StandardCharsets.US_ASCII));
                out.write(wide);
                // a full row of W, then the same row erased from columns 10-16 -- every
                // bit position a Soft-70 cell can start at -- and from the end of one
                StringBuilder erase = new StringBuilder();
                for (int r = 13; r <= 21; r++) {
                    erase.append("\u001b[").append(r).append(";1H").append("W".repeat(80));
                }
                for (int r = 14; r <= 20; r++) {
                    erase.append("\u001b[").append(r).append(';').append(r - 3).append("H\u001b[K");
                }
                erase.append("\u001b[21;5H\u001b[1K");              // erase to here: columns 0-4
                out.write(erase.toString().getBytes(StandardCharsets.US_ASCII));
                out.write("\u001b[12;1HName: ".getBytes(StandardCharsets.US_ASCII));
                readUntil(in, bytes('\r', 0));
                out.write("\r\nBye!\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // the test fails on what the Apple saw
            } finally {
                ended = true;
            }
        }

        private void readUntil(InputStream in, byte[] marker) throws IOException {
            int b;
            while ((b = in.read()) >= 0) {
                synchronized (received) {
                    received.write(b);
                    if (endsWith(received.toByteArray(), marker)) {
                        return;
                    }
                }
            }
        }

        byte[] received() {
            synchronized (received) {
                return received.toByteArray();
            }
        }
    }

    @Test
    void talksToABbsOnSoft70() throws Exception {
        Bbs bbs = new Bbs();
        Thread thread = new Thread(bbs);
        thread.setDaemon(true);
        thread.start();
        AppleMachine apple = new AppleMachine(false, "telnet.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Connect to host"), 30_000, "the host prompt");
            assertFalse(apple.text, "Soft-70 is a Hi-Res screen");
            assertTrue(apple.hires);
            assertTrue(apple.screenContains("Looking for the Uthernet II... slot 1"), apple.screen());
            assertTrue(apple.screenContains("Getting an address... 10.0.2.15"), apple.screen());

            apple.type("10.0.2.2:" + bbs.server.getLocalPort() + "\r");
            apple.runUntil(() -> apple.screenContains("Name:"), 30_000, "the BBS screen");

            byte[] replies = bbs.received();
            assertTrue(contains(replies, bytes(IAC, WILL, TTYPE)), "agrees to send its terminal type");
            assertTrue(contains(replies, bytes(IAC, WILL, NAWS, IAC, SB, NAWS, 0, 80, 0, 24, IAC, SE)), "window size 80x24");
            assertTrue(contains(replies, bytes(IAC, DO, ECHO)) && contains(replies, bytes(IAC, DO, SGA)));
            assertTrue(contains(replies, bytes(IAC, 252, 99)), "refuses options it doesn't know");
            assertTrue(contains(replies, concat(bytes(IAC, SB, TTYPE, 0), "VT100".getBytes(StandardCharsets.US_ASCII), bytes(IAC, SE))));
            assertTrue(contains(replies, concat(bytes(ESC), "[5;14R".getBytes(StandardCharsets.US_ASCII))),
                "reports the cursor at row 5, column 14");

            assertEquals("Welcome to the test BBS", apple.row(0).stripTrailing());
            assertEquals("here", apple.row(4).substring(9, 13));
            assertEquals("+-+", apple.row(6).substring(0, 3), "CP437 box drawing as ASCII");
            assertEquals('Z', apple.row(8).charAt(75));
            assertTrue(apple.softCellLit(4, 9), "'h' drawn at column 9");
            assertFalse(apple.softCellLit(4, 20), "nothing at column 20");
            assertFalse(apple.softCellLit(8, 65), "column 75 is off the right edge");

            for (int r = 13; r <= 19; r++) {                         // ESC [ K from column r - 3
                assertErased(apple, r, r - 3, 70);
                assertEquals(" ".repeat(80 - (r - 3)), apple.row(r).substring(r - 3));
            }
            assertErased(apple, 20, 0, 5);                           // ESC [ 1 K through column 4
            assertEquals("     " + "W".repeat(75), apple.row(20));

            apple.type("\u001b\u0015");                             // ESC, right arrow
            apple.runUntil(() -> apple.softCellLit(8, 65), 10_000, "the window to move right");

            apple.type("hello\r");
            apple.runUntil(() -> bbs.ended, 20_000, "the BBS to read the name");
            assertTrue(endsWith(bbs.received(), concat("hello".getBytes(StandardCharsets.US_ASCII), bytes('\r', 0))),
                "lowercase typed through the shift-key mod, then CR NUL");

            apple.runUntil(() -> apple.screenContains("Connection closed."), 20_000, "the BBS to hang up");
            apple.type("\r");
            apple.runUntil(() -> apple.exited, 10_000, "the program to quit");
            assertTrue(apple.text, "back to the text screen");
        } finally {
            apple.close();
        }
    }

    @Test
    void drawsOnTheVideoTermAndScrollsItsDisplay() throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (Socket s = server.accept()) {
                OutputStream out = s.getOutputStream();
                for (int i = 1; i <= 30; i++) {
                    out.write(("line " + i + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (i == 20) {
                        // every cell inked, so a cell the clear misses shows -- some rows
                        // cross a 512-byte VRAM page by now
                        StringBuilder full = new StringBuilder();
                        for (int r = 1; r <= 24; r++) {
                            full.append("\u001b[").append(r).append(";1H").append("X".repeat(80));
                        }
                        out.write((full + "\u001b[2J\u001b[24;1H").getBytes(StandardCharsets.US_ASCII));
                    }
                }
                out.write(("\u001b[18;1H" + "W".repeat(80) + "\u001b[18;30H\u001b[K\u001b[24;1Hlast")
                    .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                s.getInputStream().read();
            } catch (IOException e) {
                // the test fails on what the Apple saw
            }
        });
        thread.setDaemon(true);
        thread.start();
        AppleMachine apple = new AppleMachine(true, "telnet.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Connect to host"), 30_000, "the host prompt");
            VideoTermDouble vt = apple.videoTerm;
            assertTrue(apple.text && apple.an0, "80 columns: text mode with annunciator 0 on");
            assertEquals(0x50, vt.registers[1], "CRTC programmed with firmware 2.4's values");
            assertTrue(apple.screenContains("slot 1"), "the probe skipped the VideoTerm's slot 3");

            apple.type("10.0.2.2:" + server.getLocalPort() + "\r");
            apple.runUntil(() -> apple.screenContains("last"), 30_000, "the lines");
            for (int r = 0; r < 24; r++) {
                for (int c = 0; c < 80; c++) {
                    assertEquals(apple.row(r).charAt(c), vt.at(r, c), "VRAM matches the buffer at " + r + "," + c);
                }
            }
            assertTrue(vt.start() != 0, "the display start moved as it scrolled");
            assertEquals(0, vt.start() % 80 % 16 == 0 ? 0 : 1, "scrolled a whole line at a time");
            assertEquals("last", apple.row(23).substring(0, 4));
            assertEquals("W".repeat(29) + " ".repeat(51), apple.row(17), "ESC [ K from column 29");
            assertTrue(apple.screenContains("line 30") && !apple.screenContains("line 20"), "ESC [ 2 J cleared");
            assertEquals((vt.start() + 23 * 80 + 4) & 0x7FF, vt.cursor(), "the CRTC cursor follows");

            apple.type("\u001bq");
            apple.runUntil(() -> apple.screenContains("Connection closed."), 20_000, "the disconnect");
            apple.type("\r");
            apple.runUntil(() -> apple.exited, 10_000, "the program to quit");
            assertFalse(apple.an0, "back to 40 columns");
            assertEquals(0, vt.start(), "display start reset for the firmware");
        } finally {
            apple.close();
            server.close();
        }
    }

    /**
     * Soft-70 row {@code row} must match the untouched row of W above the
     * erased rows (12) bit for bit, except that pixels of window columns
     * {@code from} to {@code to} (exclusive) are dark.
     */
    private static void assertErased(AppleMachine apple, int row, int from, int to) {
        for (int line = 0; line < 8; line++) {
            for (int index = 0; index < 40; index++) {
                int expected = apple.hiresByte(12, line, index) & 0x7F;
                for (int bit = 0; bit < 7; bit++) {
                    int pixel = index * 7 + bit;
                    if (pixel >= 4 * from && pixel < 4 * to) {
                        expected &= ~(1 << bit);
                    }
                }
                assertEquals(expected, apple.hiresByte(row, line, index) & 0x7F,
                    "row " + row + " erased from " + from + ": scan line " + line + ", byte " + index);
            }
        }
    }

    // -----------------------------------------------------------------

    static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            all.writeBytes(part);
        }
        return all.toByteArray();
    }

    static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean endsWith(byte[] data, byte[] tail) {
        return data.length >= tail.length
            && Arrays.equals(Arrays.copyOfRange(data, data.length - tail.length, data.length), tail);
    }
}
