import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 0.3.2: every window takes its size, texture tier and padding from one spec.
 *
 * <p>The point is not prettiness — it is that seven XML files used to each pick
 * their own scale, their own disabled colour and their own button height, which
 * is how a panel ends up with a button that looks enabled but is not, and with
 * text pressed against a paper border. These assertions are the machine-checked
 * half of the visual spec; the other half needs eyes (T10–T13).</p>
 */
public final class UiStyleRegression {
    private static final Path GUI = Paths.get("src/main/resources/assets/rp_crossroads/gui");
    private static final Path CLIENT = Paths.get("src/main/java/com/YLFXM/rp/crossroads/client");
    private static final Path STYLE = CLIENT.resolve("UiStyle.java");

    /** Window resource to the size the visual spec fixes. */
    private static final Map<String, int[]> SIZES = new LinkedHashMap<>();

    /** Vanilla button texture tier to its native height; a button never leaves it. */
    private static final Map<String, Integer> TIER_HEIGHTS = Map.of(
            "builder_button_large", 17,
            "builder_button_medium", 17,
            "builder_button_medium_small", 17,
            "builder_button_small", 17,
            "builder_button_quite_small", 16,
            "builder_button_very_small", 16,
            "builder_button_mini", 15);

    static {
        SIZES.put("windowroadcontroller.xml", new int[]{248, 150});
        SIZES.put("windowroadcrossings.xml", new int[]{336, 228});
        SIZES.put("windowroadremovalchoice.xml", new int[]{244, 150});
        SIZES.put("windowroadsurveyor.xml", new int[]{280, 176});
        SIZES.put("windowroadtier.xml", new int[]{232, 144});
        SIZES.put("windowworkorderlist.xml", new int[]{288, 180});
        SIZES.put("windowroadstylelist.xml", new int[]{420, 240});
    }

    private static int checks;

    private static void check(final boolean value, final String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(final String[] args) throws Exception {
        sizes();
        backgrounds();
        styleWindow();
        buttonTiers();
        safeArea();
        oneDisabledColour();
        constants();
        System.out.println("PASS " + checks + " ui style assertions");
    }

    /** Every window is the size the spec fixes, and no window is missing from the spec. */
    private static void sizes() throws Exception {
        try (var files = Files.list(GUI)) {
            for (final Path file : files.toList()) {
                if (!file.toString().endsWith(".xml")) continue;
                final String name = file.getFileName().toString();
                check(SIZES.containsKey(name), "window is covered by the size table: " + name);
            }
        }
        for (final Map.Entry<String, int[]> entry : SIZES.entrySet()) {
            final String text = Files.readString(GUI.resolve(entry.getKey()));
            final Matcher size = Pattern.compile("<window[^>]*size=\"(\\d+) (\\d+)\"").matcher(text);
            check(size.find(), "window declares a size: " + entry.getKey());
            check(Integer.parseInt(size.group(1)) == entry.getValue()[0]
                            && Integer.parseInt(size.group(2)) == entry.getValue()[1],
                    "window size is " + entry.getValue()[0] + "x" + entry.getValue()[1]
                            + ": " + entry.getKey());
        }
    }

    /**
     * Paper windows use the wide sheet, never the portrait one stretched.
     *
     * <p>{@code builder_paper_sketch} is 190x244; pulled across a landscape window
     * it squashes both the texture and the border, which is what made controls
     * look like they sat outside the paper.</p>
     */
    private static void backgrounds() throws Exception {
        for (final String name : SIZES.keySet()) {
            final String text = Files.readString(GUI.resolve(name));
            check(!text.contains("builder_paper_sketch"), "no portrait paper in " + name);
            if (name.equals("windowroadstylelist.xml")) {
                continue;
            }
            check(text.contains("builder_paper_wide2.png"), "wide paper background in " + name);
        }
    }

    /** The style picker is the vanilla lightbox: no frame, no paper, ESC closes it. */
    private static void styleWindow() throws Exception {
        final String text = Files.readString(GUI.resolve("windowroadstylelist.xml"));
        check(text.contains("lightbox=\"true\""), "style window dims the world");
        check(!text.contains("builder_paper"), "style window draws no paper background");
        check(text.contains("id=\"filter\"") && text.contains("maxlength=\"25\""),
                "style window has the bounded search field");
        check(text.contains("id=\"cancel\"") && text.contains("id=\"styles\"")
                        && text.contains("id=\"series\""),
                "style window has cancel, card list and series list");
        final Matcher cards = Pattern.compile("id=\"box\\d\"").matcher(text);
        int boxes = 0;
        while (cards.find()) boxes++;
        check(boxes == 2, "style window lays out two cards per row: " + boxes);
    }

    /** Buttons keep their texture's native height; shrinking means a smaller tier. */
    private static void buttonTiers() throws Exception {
        int buttons = 0;
        for (final String name : SIZES.keySet()) {
            final String text = Files.readString(GUI.resolve(name));
            final Matcher button = Pattern.compile("<button\\b([^>]*)>").matcher(text);
            while (button.find()) {
                final String attrs = button.group(1);
                final Matcher source = Pattern.compile("source=\"([^\"]+)\"").matcher(attrs);
                if (!source.find()) continue;
                final String path = source.group(1);
                for (final Map.Entry<String, Integer> tier : TIER_HEIGHTS.entrySet()) {
                    if (!path.endsWith(tier.getKey() + ".png")) continue;
                    buttons++;
                    final Matcher size = Pattern.compile("size=\"(\\d+) (\\d+)\"").matcher(attrs);
                    check(size.find(), "button declares a size in " + name);
                    check(Integer.parseInt(size.group(2)) == tier.getValue(),
                            tier.getKey() + " keeps its native height " + tier.getValue()
                                    + " in " + name + " (was " + size.group(2) + ")");
                }
            }
        }
        check(buttons >= 30, "button tiers were inspected: " + buttons);
    }

    /** No control sits on the paper border: everything is inside the 8 px safe area. */
    private static void safeArea() throws Exception {
        final int pad = 8;
        int panes = 0;
        for (final Map.Entry<String, int[]> entry : SIZES.entrySet()) {
            if (entry.getKey().equals("windowroadstylelist.xml")) {
                // Vanilla lightbox layout: no paper border, so the safe area does not apply.
                continue;
            }
            final String text = Files.readString(GUI.resolve(entry.getKey()));
            // Row templates live inside a list and are positioned relative to the
            // row, not the window, so the safe area says nothing about them.
            final java.util.List<int[]> rows = new java.util.ArrayList<>();
            final Matcher list = Pattern.compile("<list\\b[^>]*>.*?</list>", Pattern.DOTALL).matcher(text);
            while (list.find()) rows.add(new int[]{list.start(), list.end()});
            final Matcher pane = Pattern.compile("<(text|button|list|box|input|image)\\b([^>]*)>").matcher(text);
            while (pane.find()) {
                boolean insideRow = false;
                for (final int[] range : rows) insideRow |= pane.start() > range[0] && pane.start() < range[1];
                if (insideRow) continue;
                final String attrs = pane.group(2);
                final Matcher pos = Pattern.compile("pos=\"(\\d+) (\\d+)\"").matcher(attrs);
                final Matcher size = Pattern.compile("size=\"(\\d+) (\\d+)\"").matcher(attrs);
                if (!pos.find() || !size.find()) continue;
                panes++;
                final int x = Integer.parseInt(pos.group(1));
                final int y = Integer.parseInt(pos.group(2));
                final int w = Integer.parseInt(size.group(1));
                final int h = Integer.parseInt(size.group(2));
                check(x >= pad && y >= pad, entry.getKey() + " keeps " + pad + "px on the left/top: "
                        + pane.group(1) + " at " + x + "," + y);
                check(x + w <= entry.getValue()[0] - pad && y + h <= entry.getValue()[1] - pad,
                        entry.getKey() + " keeps " + pad + "px on the right/bottom: "
                                + pane.group(1) + " ends at " + (x + w) + "," + (y + h));
            }
        }
        check(panes > 20, "panes were inspected for the safe area: " + panes);
    }

    /** Exactly one definition of "disabled looks disabled". */
    private static void oneDisabledColour() throws Exception {
        try (var files = Files.list(CLIENT)) {
            for (final Path file : files.toList()) {
                if (!file.toString().endsWith(".java") || file.getFileName().toString().equals("UiStyle.java")) {
                    continue;
                }
                final String text = Files.readString(file);
                check(!text.contains("setColors("),
                        "window takes its colours from UiStyle, not by hand: " + file.getFileName());
            }
        }
        final String style = Files.readString(STYLE);
        check(style.contains("INK_DISABLED = 0x9A9A9A"), "UiStyle declares the disabled grey");
        check(style.contains("builder_paper_wide2.png"), "UiStyle points at the wide paper sheet");
    }

    /** The numbers the XML files cannot share are at least declared once, in code. */
    private static void constants() throws Exception {
        final String style = Files.readString(STYLE);
        check(style.contains("int PAD = 8"), "safe area is 8 px");
        check(style.contains("int ROW = 20"), "list row is 20 px");
        check(style.contains("double TITLE = 0.9"), "title scale is 0.9");
        check(style.contains("double TEXT = 0.8"), "body scale is 0.8");
        check(style.contains("double SECONDARY = 0.7"), "secondary scale is 0.7");
    }

    private UiStyleRegression() { }
}
