package com.nordstrom.uthernet.apple;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs GOPHER (src/apple/gopher.s) on the 6502 against a Gopher server on
 * the host's loopback, reached through the card's gateway at 10.0.2.2.
 */
class GopherTest {

    private ServerSocket server;
    private final List<String> selectors = new CopyOnWriteArrayList<>();
    /** Lines of /huge.txt the server wrote before its connection failed; -1 while still sending. */
    private final AtomicInteger hugeLinesSent = new AtomicInteger(-1);
    private static final int HUGE_LINES = 5_000_000;

    @BeforeEach
    void startServer() throws IOException {
        server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(this::serve);
        thread.setDaemon(true);
        thread.start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    private void serve() {
        while (true) {
            try (Socket s = server.accept()) {
                String selector = new BufferedReader(new InputStreamReader(s.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
                selectors.add(selector);
                OutputStream out = s.getOutputStream();
                if ("/huge.txt".equals(selector)) {
                    sendHuge(out);
                    continue;
                }
                out.write(response(selector).getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (SocketException e) {
                return;
            } catch (IOException e) {
                // the test fails on what the Apple saw
            }
        }
    }

    /** Effectively endless -- more than the host's socket buffers could ever hold -- until it fails. */
    private void sendHuge(OutputStream out) {
        int line = 0;
        try {
            for (; line < HUGE_LINES; line++) {
                out.write(("Huge line " + (line + 1) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            }
            out.write(".\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
        } catch (IOException e) {
            // the client let go
        }
        hugeLinesSent.set(line);
    }

    /** @return true once the status line starting with {@code start} is completely drawn */
    private static boolean status(AppleMachine apple, String start) {
        String line = apple.row(23);
        return line.startsWith(start) && line.contains("Q quit");
    }

    private String response(String selector) {
        String here = "\t10.0.2.2\t" + server.getLocalPort() + "\r\n";
        if (selector.isEmpty()) {
            return "iWelcome to the test Gopher\t\terror.host\t1\r\n"
                + "0About this server\t/about.txt" + here
                + "1A submenu\t/sub" + here
                + "7Search the server\t/search" + here
                + "9A binary file\t/file.bin" + here
                + ".\r\n";
        }
        if (selector.equals("/about.txt")) {
            StringBuilder text = new StringBuilder();
            for (int i = 1; i <= 30; i++) {
                text.append("About line ").append(i).append("\r\n");
            }
            return text.append(".\r\n").toString();
        }
        if (selector.equals("/long.txt")) {
            StringBuilder text = new StringBuilder();
            for (int i = 1; i <= 1000; i++) {
                text.append("Long line ").append(i).append("\r\n");
            }
            text.append(".hidden? no: a dot that isn't alone starts an ordinary line\r\n");
            return text.append(".\r\n").toString();
        }
        if (selector.startsWith("/search\t")) {
            return "iYou searched for " + selector.substring(8) + "\t\terror.host\t1\r\n.\r\n";
        }
        return "3Not found\t\terror.host\t1\r\n.\r\n";
    }

    @Test
    void browsesMenusTextAndSearch() throws Exception {
        AppleMachine apple = new AppleMachine(false, "gopher.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Go to host"), 30_000, "the go-to prompt");
            apple.type("10.0.2.2:" + server.getLocalPort() + "\r");
            apple.runUntil(() -> status(apple, "Item _"), 30_000, "the menu");
            assertEquals("    Welcome to the test Gopher", apple.row(0).stripTrailing(), "an info line, unnumbered");
            assertEquals("  1 About this server", apple.row(1).stripTrailing());
            assertEquals("  2 A submenu", apple.row(2).stripTrailing());
            assertEquals("  3 Search the server", apple.row(3).stripTrailing());
            assertTrue(status(apple, "Item _"), apple.screen());

            apple.type("1\r");
            apple.runUntil(() -> status(apple, "Page "), 20_000, "the text");
            assertEquals("About line 23", apple.row(22).stripTrailing(), "23 lines a page");
            apple.type(" ");
            apple.runUntil(() -> apple.row(0).startsWith("About line 24")
                && status(apple, "Page 2"), 10_000, "page 2");
            assertTrue(apple.row(23).contains("[end]"), "the last page says so");
            assertEquals("About line 30", apple.row(6).stripTrailing());
            assertEquals("", apple.row(7).trim(), "the closing . isn't shown");
            apple.type("-");
            apple.runUntil(() -> apple.row(0).startsWith("About line 1 ")
                && status(apple, "Page 1"), 10_000, "back to page 1");

            apple.type("\u001b");
            apple.runUntil(() -> status(apple, "Item _"), 20_000, "back to the menu");

            apple.type("4\r");
            apple.runUntil(() -> apple.screenContains("Can't open"), 10_000, "the unsupported-type notice");
            apple.type(" ");
            apple.type("3\r");
            apple.runUntil(() -> apple.screenContains("Search for:"), 10_000, "the search prompt");
            apple.type("apple\r");
            apple.runUntil(() -> apple.screenContains("You searched for apple")
                && status(apple, "Item _"), 20_000, "the search results");
            assertTrue(selectors.contains("/search\tapple"), "query sent after a tab: " + selectors);

            apple.type("q");
            apple.runUntil(() -> apple.exited, 10_000, "the program to quit");
            assertTrue(apple.text, "back to the text screen");
        } finally {
            apple.close();
        }
    }

    @Test
    void streamsADocumentFarLargerThanItsBuffer() throws Exception {
        AppleMachine apple = new AppleMachine(false, "gopher.bin");   // Soft-70: the 4.2K buffer
        try {
            apple.runUntil(() -> apple.screenContains("Go to host"), 30_000, "the go-to prompt");
            apple.type("10.0.2.2:" + server.getLocalPort() + "/0/long.txt\r");
            apple.runUntil(() -> status(apple, "Page 1"), 30_000, "page 1");
            assertEquals("Long line 1", apple.row(0).stripTrailing());
            assertEquals("Long line 23", apple.row(22).stripTrailing());
            for (int page = 2; page <= 3; page++) {
                int p = page;
                apple.type(" ");
                apple.runUntil(() -> status(apple, "Page " + p), 10_000, "page " + p);
            }
            assertEquals("Long line 47", apple.row(0).stripTrailing(), "page 3 picks up where page 2 left off");
            int requests = selectors.size();
            apple.type("-");
            apple.runUntil(() -> status(apple, "Page 2"), 30_000, "back to page 2");
            assertEquals("Long line 24", apple.row(0).stripTrailing(), "fetched again and skipped to page 2");
            assertEquals(requests + 1, selectors.size());

            for (int page = 3; page <= 44; page++) {                  // ~22K in, five times the buffer
                int p = page;
                apple.type(" ");
                apple.runUntil(() -> status(apple, "Page " + p), 10_000, "page " + p);
            }
            assertEquals("Long line 990", apple.row(0).stripTrailing());
            assertEquals("Long line 1000", apple.row(10).stripTrailing());
            assertTrue(apple.row(11).startsWith(".hidden?"), "a line starting with a dot is still text");
            assertTrue(apple.row(23).contains("[end]"));
        } finally {
            apple.close();
        }
    }

    @Test
    void letsGoOfADocumentItLeavesPartway() throws Exception {
        AppleMachine apple = new AppleMachine(false, "gopher.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Go to host"), 30_000, "the go-to prompt");
            apple.type("10.0.2.2:" + server.getLocalPort() + "\r");
            apple.runUntil(() -> status(apple, "Item _"), 30_000, "the menu");
            apple.type("1\r");                                       // something to come back to
            apple.runUntil(() -> status(apple, "Page 1"), 20_000, "the about page");
            apple.type("g10.0.2.2:" + server.getLocalPort() + "/0/huge.txt\r");
            apple.runUntil(() -> status(apple, "Page 1") && apple.row(0).startsWith("Huge"), 30_000,
                "page 1 of the huge one");
            assertEquals("Huge line 1", apple.row(0).stripTrailing());
            assertEquals(-1, hugeLinesSent.get(), "page 1 shows without waiting for the rest");

            apple.type("\u001b");                                    // back, partway through
            apple.runUntil(() -> status(apple, "Page 1")
                && apple.row(0).startsWith("About line 1 "), 30_000, "the previous document");
            apple.runUntil(() -> hugeLinesSent.get() >= 0, 30_000, "the server to be cut off");
            assertTrue(hugeLinesSent.get() < HUGE_LINES, "the server was reset partway: " + hugeLinesSent.get());
        } finally {
            apple.close();
        }
    }

    @Test
    void keepsDocumentsInHiResMemoryWithTheVideoTerm() throws Exception {
        AppleMachine apple = new AppleMachine(true, "gopher.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Go to host"), 30_000, "the go-to prompt");
            apple.type("gopher://10.0.2.2:" + server.getLocalPort() + "/0/about.txt\r");
            apple.runUntil(() -> status(apple, "Page "), 30_000, "the text");
            assertEquals("/about.txt", selectors.get(selectors.size() - 1), "a gopher:// URL's type and selector");
            String start = "About line 1";
            boolean found = false;
            for (int a = AppleMachine.HIRES; a < 0x4000 - start.length() && !found; a++) {
                found = true;
                for (int i = 0; i < start.length(); i++) {
                    if (apple.ram[a + i] != start.charAt(i)) {
                        found = false;
                        break;
                    }
                }
            }
            assertTrue(found, "the document is in $2000-$3FFF");
            assertEquals('A', apple.videoTerm.at(0, 0), "shown on the VideoTerm");
        } finally {
            apple.close();
        }
    }
}
