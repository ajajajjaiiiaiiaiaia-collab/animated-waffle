# Rounds system

Flow: /duel → arena → Duel GUI (head, country, arena, time) → **Continue** sends the request.
Rounds: Book & Quill under the Arena icon → Anvil GUI (paper / green glass, named with a single space, blank title)
→ type a number (digits only, max 99) → click the green glass → back to the Duel GUI with the number shown on the clock.
No number chosen = 1 round. ESC in the anvil = back to the Duel GUI unchanged.

- Each round ends when a player dies. Point to the winner, `rounds.break-seconds`, then a **fresh world** for the next round:
  it is taken from the world pool (or cloned) during the break, players (and spectators) are teleported into it,
  the old world is deleted, kits re-given, countdown, next round. `rounds.new-world-per-round: false` = old behaviour
  (same world + block reset). If the new world fails to load the round falls back to the block reset.
- **Same world always** (`rounds.same-world: true`): no condition anymore. Every change is recorded with its original block (break, place, bucket, fluid flow, explosion, fire, falling blocks, cobble/ice forming). During the break the map is fully restored (`resetArenaBlocks`: built blocks removed, original blocks back, drops/projectiles/TNT cleared), then both players (winner in the arena + loser in spectator) are pulled by velocity to their spawn points (`RoundPullManager`, `rounds.pull-ticks`), then the countdown. `same-world: false` = fresh world per round (old behaviour).
- After the last round: higher score wins. Tie → one extra deciding round.
- /leave or disconnect → the WHOLE match ends, opponent wins (config `rounds.leave-counts-as-loss`).
- Stats (win/loss) recorded once per match. Rematch keeps the same number of rounds.

Code: `RoundsAnvilGUI` (+ `RoundsAnvilListener`), `DuelConfirmGUI` (SLOT_ROUNDS = 31), `DuelManager` (prepareNextRoundWorld / beginRound).
