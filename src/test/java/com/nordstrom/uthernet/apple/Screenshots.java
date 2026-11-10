package com.nordstrom.uthernet.apple;

import com.nordstrom.emulator.MemoryBus;
import com.nordstrom.emulator.system.HiResRenderer;
import com.nordstrom.emulator.system.VideoSoftSwitches;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Captures the documentation's screenshots: runs TELNET and GOPHER on the
 * 6502 (AppleMachine), connected through the card's virtual network to
 * The Norse Wanderer BBS and Floodgap's Gopher server, and draws the
 * Soft-70 Hi-Res screen with ReplicApple2Plus's own HiResRenderer at the
 * application's 3x scale. Needs Internet access.
 */
public final class Screenshots {

    private static final int SCALE = 3;
    private static final String BBS = "www.norsewanderer.com:2323";
    private static final String GOPHER_HOST = "gopher.floodgap.com";
    private static final int GOPHER_PORT = 70;
    private static final int MENU_PAGE_ROWS = 22;
    private static final String SEARCH_WORDS = "apple ii";
    private static File out;

    public static void main(String[] args) throws Exception {
        out = new File(args.length > 0 ? args[0] : "docs/images");
        out.mkdirs();
        telnet();
        gopher();
    }

    // -----------------------------------------------------------------
    // TELNET

    private static void telnet() throws Exception {
        AppleMachine apple = new AppleMachine(false, "telnet.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Connect to host"), 30_000, "the host prompt");
            apple.type(BBS);
            apple.runUntil(() -> !apple.keysPending(), 10_000, "the typing");
            apple.runFor(300);
            save(apple, "telnet-connect.png");
            apple.type("\r");
            settle(apple, 90_000, "the BBS's first screen");             // its first screen may clear ours
            if (apple.screenContains("the connection failed.") || apple.screenContains("not found.")) {
                throw new IllegalStateException("couldn't reach " + BBS + "; screen:\n" + apple.screen());
            }
            save(apple, "telnet-bbs.png");

            apple.type("\u001bq");                                  // ESC Q: disconnect
            apple.runUntil(() -> apple.screenContains("Connection closed."), 20_000, "the disconnection");
        } finally {
            apple.close();
        }
    }

    // -----------------------------------------------------------------
    // GOPHER

    private static void gopher() throws Exception {
        List<String> menu = fetchMenu();
        int[] textItem = locate(menu, '0');
        int[] searchItem = locate(menu, '7');

        AppleMachine apple = new AppleMachine(false, "gopher.bin");
        try {
            apple.runUntil(() -> apple.screenContains("Go to host"), 30_000, "the go-to prompt");
            apple.type(GOPHER_HOST + "\r");
            apple.runUntil(() -> menuShown(apple), 60_000, "the menu");
            apple.runFor(300);
            save(apple, "gopher-menu.png");

            open(apple, textItem);
            apple.runUntil(() -> apple.row(23).startsWith("Page 1"), 60_000, "the text");
            apple.runFor(300);
            save(apple, "gopher-text.png");

            apple.type("\u001b");                                    // back to the menu page it was opened from
            apple.runUntil(() -> menuShown(apple), 60_000, "the menu again");
            open(apple, searchItem);
            apple.runUntil(() -> apple.screenContains("Search for:"), 10_000, "the search prompt");
            apple.type(SEARCH_WORDS + "\r");
            apple.runUntil(() -> menuShown(apple) && !apple.screenContains("Search for:"), 120_000,
                "the search results");
            apple.runFor(300);
            save(apple, "gopher-search.png");
        } finally {
            apple.close();
        }
    }

    /** Pages from the menu page shown to the item's page, then opens it by number. */
    private static void open(AppleMachine apple, int[] item) throws InterruptedException {
        int target = item[1] + 1;
        apple.runUntil(() -> menuPage(apple) > 0, 60_000, "the menu's page number");
        while (menuPage(apple) != target) {
            int next = menuPage(apple) + (menuPage(apple) < target ? 1 : -1);
            apple.type(next > menuPage(apple) ? " " : "-");
            apple.runUntil(() -> menuShown(apple) && menuPage(apple) == next, 60_000, "page " + next);
        }
        apple.type(item[0] + "\r");
    }

    /** @return the page number on the menu's status line, or -1 while there's none */
    private static int menuPage(AppleMachine apple) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^Item _ Page (\\d+)").matcher(apple.row(23));
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    private static boolean menuShown(AppleMachine apple) {
        return apple.row(23).startsWith("Item _") && apple.row(23).contains("Q quit");
    }

    /** Reads the server's main menu from the host, to find items to open by number. */
    private static List<String> fetchMenu() throws IOException {
        List<String> lines = new ArrayList<>();
        try (Socket s = new Socket(GOPHER_HOST, GOPHER_PORT)) {
            s.setSoTimeout(30_000);
            s.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
                StandardCharsets.ISO_8859_1));
            String line;
            while ((line = in.readLine()) != null && !line.equals(".")) {
                lines.add(line);
            }
        }
        return lines;
    }

    /**
     * @return the number GOPHER gives the first item of {@code type} in the menu, and the page
     *         (from 0) it's on: GOPHER numbers every line but blank ones, information lines (i)
     *         and errors (3) through the whole menu, and shows {@value #MENU_PAGE_ROWS} lines a page
     */
    private static int[] locate(List<String> menu, char type) {
        int number = 0;
        for (int i = 0; i < menu.size(); i++) {
            String line = menu.get(i);
            if (line.isEmpty() || line.charAt(0) == 'i' || line.charAt(0) == '3') {
                continue;
            }
            number++;
            if (line.charAt(0) == type) {
                return new int[] {number, i / MENU_PAGE_ROWS};
            }
        }
        throw new IllegalStateException("no item of type " + type + " in " + GOPHER_HOST + "'s menu");
    }

    // -----------------------------------------------------------------

    /** Runs until the screen has stopped changing for three seconds. */
    private static void settle(AppleMachine apple, long limitMillis, String what) throws InterruptedException {
        long start = System.currentTimeMillis();
        String last = apple.screen();
        long since = System.currentTimeMillis();
        while (System.currentTimeMillis() - since < 3_000) {
            if (System.currentTimeMillis() - start > limitMillis) {
                throw new AssertionError("timed out waiting for " + what + "; screen:\n" + apple.screen());
            }
            apple.runFor(250);
            String now = apple.screen();
            if (!now.equals(last)) {
                last = now;
                since = System.currentTimeMillis();
            }
        }
    }

    private static void save(AppleMachine apple, String name) throws IOException {
        MemoryBus bus = new MemoryBus() {
            @Override
            public int read(int address) {
                return apple.ram[address];
            }

            @Override
            public void write(int address, int value) {
            }
        };
        Color[][] pixels = HiResRenderer.render(bus, new VideoSoftSwitches());
        int w = pixels[0].length, h = pixels.length;
        BufferedImage image = new BufferedImage(w * SCALE, h * SCALE, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h * SCALE; y++) {
            for (int x = 0; x < w * SCALE; x++) {
                image.setRGB(x, y, pixels[y / SCALE][x / SCALE].getRGB());
            }
        }
        ImageIO.write(image, "png", new File(out, name));
        System.out.println(name + "\n" + apple.screen());
    }

    private Screenshots() {
    }
}
