package com.duel.party;

public enum PartyPrivacy {

    OPEN("Open"),
    CLOSED("Closed");

    private final String readable;

    PartyPrivacy(String readable) {
        this.readable = readable;
    }

    public String getReadable() { return readable; }
}
