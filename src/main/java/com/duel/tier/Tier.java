package com.duel.tier;

/**
 * التيرات العشرة (من الأدنى LT5 إلى الأعلى HT1) — ordinal أكبر = تير أعلى.
 */
public enum Tier {
    LT5("&7", 0),
    HT5("&f", 1050),
    LT4("&a", 1100),
    HT4("&2", 1175),
    LT3("&b", 1250),
    HT3("&3", 1350),
    LT2("&d", 1450),
    HT2("&5", 1575),
    LT1("&6", 1700),
    HT1("&c&l", 1850);

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
    public String colored()        { return color + name(); }
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
