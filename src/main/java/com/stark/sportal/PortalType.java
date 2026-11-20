package com.stark.sportal;

public enum PortalType {
    COLLISION,
    TOUCH,
    JUMP,
    ALL;

    public static PortalType fromString(String s) {
        if (s == null) return null;
        for (PortalType t : values()) {
            if (t.name().equalsIgnoreCase(s)) return t;
        }
        return null;
    }
}
