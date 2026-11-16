package com.duel.party;

import java.util.UUID;

public class PartyInvite {

    private final UUID uuid;
    private final long expiresAt = System.currentTimeMillis() + 30_000L;

    public PartyInvite(UUID uuid) {
        this.uuid = uuid;
    }

    public UUID getUuid()      { return uuid; }
    public boolean hasExpired(){ return System.currentTimeMillis() >= expiresAt; }
}
