package com.nordstrom.uthernet.apple;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.nordstrom.uthernet.apple.TelnetTest.bytes;
import static com.nordstrom.uthernet.apple.TelnetTest.concat;
import static com.nordstrom.uthernet.apple.TelnetTest.contains;
import static com.nordstrom.uthernet.apple.TelnetTest.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The DOS 3.3 utility disk on a whole Apple II+: ReplicApple2Plus's
 * headless runner with the real Autostart ROM, DOS 3.3 booted from its
 * System Master in drive 1, the utility disk in drive 2, the Uthernet II in
 * slot 1 and the real VideoTerm in slot 3 -- the setup UTILITIES.md
 * describes. TELNET and GOPHER are run with {@code BRUN} and typed at
 * through the keyboard, against servers on the host's loopback reached
 * through the card's gateway at 10.0.2.2, and their screens are read off
 * the VideoTerm.
 * <p>
 * The runs keep real time ({@code --real-time}): the servers answer in
 * real time, and IP65's timeouts count emulated time, so an unthrottled
 * machine would give up on them several times too soon.
 * <p>
 * {@link TelnetTest} and {@link GopherTest} cover the programs in depth on
 * a stand-in machine that runs anywhere; these show them working where
 * they will be used. Needs the DOS 3.3 System Master
 * ({@code -Pdos33Master}) and the ROM images ({@code -Proms}, see
 * {@link HeadlessRun}).
 */
@EnabledIfSystemProperty(named = "uthernet.dos33Master", matches = ".+")
@EnabledIfSystemProperty(named = HeadlessRun.ROMS, matches = ".+")
class UtilitiesRunnerTest {

    private static final Path UTILITY_DISK = Path.of("src/apple/disks/uthernet2-dos33.dsk");
    private static final int IAC = 255, SB = 250, SE = 240, TTYPE = 24;

    private Path config;

    @BeforeEach
    void writeConfiguration(@TempDir Path scratch) throws IOException {
        config = scratch.resolve("utilities.ini");
        Files.writeString(config, String.join("\n",
            "[1]", "type=uthernet2", "",
            "[3]", "type=videoterm", "",
            "[6]", "type=disk2", ""));
    }

    @Test
    void telnetTalksToABbs() throws Exception {
        TelnetTest.Bbs bbs = new TelnetTest.Bbs();
        Thread thread = new Thread(bbs);
        thread.setDaemon(true);
        thread.start();
        try {
            HeadlessRun.Result run = utility("TELNET",
                "--expect", "Looking for the Uthernet II... slot 1",
                "--expect", "Getting an address... 10.0.2.15",
                "--expect", "Connect to host",
                "--type", "10.0.2.2:" + bbs.server.getLocalPort() + "\\r",
                "--expect", "Name:",
                "--type", "hello\\r",
                "--until", "Connection closed.");
            assertEquals(0, run.status(), run.describe());
            assertTrue(run.screen().contains("Welcome to the test BBS"), run.describe());
            assertTrue(run.screen().contains("Bye!"), run.describe());
            byte[] replies = bbs.received();
            assertTrue(contains(replies, concat(bytes(IAC, SB, TTYPE, 0),
                "VT100".getBytes(StandardCharsets.US_ASCII), bytes(IAC, SE))), "sends its terminal type");
            assertTrue(endsWith(replies, concat("hello".getBytes(StandardCharsets.US_ASCII), bytes('\r'))),
                "the name, typed in lowercase through the shift-key mod");
        } finally {
            bbs.server.close();
        }
    }

    @Test
    void gopherBrowsesAMenuAndAText() throws Exception {
        List<String> selectors = new CopyOnWriteArrayList<>();
        try (ServerSocket server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress())) {
            Thread thread = new Thread(() -> serveGopher(server, selectors));
            thread.setDaemon(true);
            thread.start();
            HeadlessRun.Result run = utility("GOPHER",
                "--expect", "Go to host",
                "--type", "10.0.2.2:" + server.getLocalPort() + "\\r",
                "--expect", "1 About this server",
                "--type", "1\\r",
                "--until", "the test Gopher's about page");
            assertEquals(0, run.status(), run.describe());
            assertEquals(List.of("", "/about.txt"), selectors, "the menu, then the item chosen");
        }
    }

    /** A Gopher server with a one-item menu and the text it leads to. */
    private static void serveGopher(ServerSocket server, List<String> selectors) {
        while (!server.isClosed()) {
            try (Socket s = server.accept()) {
                String selector = new BufferedReader(new InputStreamReader(s.getInputStream(),
                    StandardCharsets.US_ASCII)).readLine();
                selectors.add(selector);
                String response = selector.isEmpty()
                    ? "iWelcome to the test Gopher\t\terror.host\t1\r\n"
                        + "0About this server\t/about.txt\t10.0.2.2\t" + server.getLocalPort() + "\r\n.\r\n"
                    : "This is the test Gopher's about page.\r\n.\r\n";
                OutputStream out = s.getOutputStream();
                out.write(response.getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (IOException e) {
                // closed at the test's end, or the test fails on what the Apple saw
            }
        }
    }

    /**
     * @param program the utility to BRUN from drive 2
     * @param script  the runner's steps after that
     */
    private HeadlessRun.Result utility(String program, String... script) throws IOException, InterruptedException {
        List<String> args = new ArrayList<>(List.of(
            "--config", config.toString(),
            "--d1", System.getProperty("uthernet.dos33Master"),
            "--d2", UTILITY_DISK.toAbsolutePath().toString(),
            "--real-time",
            "--type", "BRUN " + program + ",D2\\r"));
        args.addAll(List.of(script));
        return HeadlessRun.run(args.toArray(String[]::new));
    }
}
