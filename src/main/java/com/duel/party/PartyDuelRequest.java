package com.duel.party;

import com.duel.models.Arena;

/**
 * طلب دول بارتي — ينتهي صلاحيته بعد 30 ثانية.
 * يحفظ الأرينا التي اختارها المتحدي.
 */
public class PartyDuelRequest {

    private final Party sender;   // البارتي التي أرسلت الطلب
    private final Party target;   // البارتي المستقبِلة
    private final Arena arena;    // الأرينا المختارة من المتحدي
    private final long  createdAt;
    private static final long EXPIRE_MS = 30_000L;

    public PartyDuelRequest(Party sender, Party target, Arena arena) {
        this.sender    = sender;
        this.target    = target;
        this.arena     = arena;
        this.createdAt = System.currentTimeMillis();
    }

    public Party getSenderParty()  { return sender; }
    public Party getTargetParty()  { return target; }
    public Arena getArena()        { return arena; }

    public boolean hasExpired() {
        return System.currentTimeMillis() - createdAt > EXPIRE_MS;
    }
}
