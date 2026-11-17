package com.duel.tier;

/**
 * التيرات العشرة (من الأدنى LT5 إلى الأعلى HT1) — ordinal أكبر = تير أعلى.
 */
public enum Tier {
    LT5("&7", 0),
    HT5("&7", 1050),
    LT4("&7", 1100),
    HT4("&7", 1175),
    LT3("&e", 1250),
    HT3("&e&l", 1350),
    LT2("&6", 1450),
    HT2("&6&l", 1575),
    LT1("&d", 1700),
    HT1("&d&l", 1850);

    private final String color;
    private final int defaultThreshold;

    Tier(String color, int defaultThreshold) {
        this.color = color;
        this.defaultThreshold = defaultThreshold;
    }

    /** كود اللون بصيغة & */
    public String color()          { return color; }
    public int defaultThreshold()  { return defaultThreshold; }

    /** الاسم مع اللون بصيغة & (مثل &6HT1) */
    public String colored() {
        String[] stops = STOPS[ordinal()];
        boolean bold = ordinal() >= HT3.ordinal();
        String n = name();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            double t = n.length() == 1 ? 0 : (double) i / (n.length() - 1);
            sb.append(hex(stops, t)).append(bold ? "&l" : "").append(n.charAt(i));
        }
        return sb.toString();
    }

    private static final String[][] STOPS = {
        {"8E979C", "8E979C"},                 // LT5
        {"A3ACB1", "A3ACB1"},                 // HT5
        {"B4BCC1", "B4BCC1"},                 // LT4
        {"C9D0D4", "C9D0D4"},                 // HT4
        {"FFF176", "FFD740"},                 // LT3
        {"FFD740", "FFB300"},                 // HT3
        {"FFB300", "E69100"},                 // LT2
        {"E69100", "B26A00"},                 // HT2
        {"FF7EB6", "B65CFF", "FF4D6D"},       // LT1
        {"FF4FA3", "9D4DFF", "FF2E3F"}        // HT1
    };

    private static String hex(String[] stops, double t) {
        double pos = t * (stops.length - 1);
        int a = Math.min((int) pos, stops.length - 2);
        double f = pos - a;
        int c1 = Integer.parseInt(stops[a], 16), c2 = Integer.parseInt(stops[a + 1], 16);
        int r = (int) Math.round(((c1 >> 16) & 255) * (1 - f) + ((c2 >> 16) & 255) * f);
        int g = (int) Math.round(((c1 >> 8) & 255) * (1 - f) + ((c2 >> 8) & 255) * f);
        int b = (int) Math.round((c1 & 255) * (1 - f) + (c2 & 255) * f);
        String h = String.format("%02X%02X%02X", r, g, b);
        StringBuilder o = new StringBuilder("&x");
        for (char ch : h.toCharArray()) o.append('&').append(ch);
        return o.toString();
    }

    /** الاسم مع اللون بصيغة § للإرسال المباشر */
    public String chat()           { return colored().replace('&', '§'); }

    public Tier next() { return this == HT1 ? null : values()[ordinal() + 1]; }
    public Tier prev() { return this == LT5 ? null : values()[ordinal() - 1]; }

    public static Tier parse(String s, Tier fallback) {
        if (s == null) return fallback;
        try { return valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
