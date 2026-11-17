Modified build of GrimAC 2.3.75 (upstream commit 8ec9b7d, GPL-3.0-or-later).
Change: Reach and Hitboxes checks are skipped while the player holds a spear or an end crystal and for 5 ticks afterwards
(common/src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java).
Upstream: https://github.com/GrimAnticheat/Grim

Test branch: also reverts upstream 0ea9b5a (setback injected client position packet), suspected cause of Bedrock INVALID_PLAYER_MOVEMENT kicks (issue #3007).
