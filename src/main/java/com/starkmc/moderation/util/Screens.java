package com.starkmc.moderation.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.FileConfiguration;

public final class Screens {
   private static final MiniMessage MM = MiniMessage.miniMessage();
   private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

   private Screens() {
   }

   public static Component buildBanScreen(FileConfiguration cfg, String playerName, String reason, String by, long bannedAt, long expiryMs) {
      String noReason = cfg.getString("screens.ban.no-reason", "ɴᴏ ʀᴇᴀꜱᴏɴ");
      String unknown = cfg.getString("screens.ban.unknown", "Unknown");
      String never = cfg.getString("screens.ban.never", "Never");
      String discord = cfg.getString("discord.link", "");
      String finalReason = reason != null && !reason.isBlank() ? reason : noReason;
      String finalBy = by != null && !by.isBlank() ? by : unknown;
      String finalDate = formatDate(bannedAt);
      String finalExp = expiryMs == -1L ? never : DurationUtil.remainingHms(expiryMs);
      List<String> lines = cfg.getStringList("screens.ban.lines");
      return buildScreen(lines, playerName, finalReason, finalBy, finalDate, finalExp, discord);
   }

   public static Component buildBlacklistScreen(FileConfiguration cfg, String playerName, String reason, String by, long at) {
      String noReason = cfg.getString("screens.blacklist.no-reason", "ɴᴏ ʀᴇᴀꜱᴏɴ");
      String unknown = cfg.getString("screens.blacklist.unknown", "Unknown");
      String discord = cfg.getString("discord.link", "");
      String finalReason = reason != null && !reason.isBlank() ? reason : noReason;
      String finalBy = by != null && !by.isBlank() ? by : unknown;
      List<String> lines = cfg.getStringList("screens.blacklist.lines");
      return buildScreen(lines, playerName, finalReason, finalBy, formatDate(at), null, discord);
   }

   public static Component buildKickScreen(FileConfiguration cfg, String playerName, String reason, String by) {
      String noReason = cfg.getString("screens.kick.no-reason", "ɴᴏ ʀᴇᴀꜱᴏɴ");
      String discord = cfg.getString("discord.link", "");
      String finalReason = reason != null && !reason.isBlank() ? reason : noReason;
      String finalDate = formatDate(System.currentTimeMillis());
      List<String> lines = cfg.getStringList("screens.kick.lines");
      return buildScreen(lines, playerName, finalReason, by, finalDate, null, discord);
   }

   public static Component buildMuteNotice(FileConfiguration cfg, String reason, long expiryMs) {
      String template = cfg.getString("mute.notify", "<red>\ud83d\udd07 You are muted. <gray>Reason: <white><reason> <gray>| Expires: <white><expiry>");
      String noReason = cfg.getString("mute.no-reason", "ɴᴏ ʀᴇᴀꜱᴏɴ");
      String never = cfg.getString("mute.never", "Never");
      String finalReason = reason != null && !reason.isBlank() ? reason : noReason;
      String finalExpiry = expiryMs == -1L ? never : DurationUtil.remaining(expiryMs);
      return MM.deserialize(template.replace("<reason>", finalReason).replace("<expiry>", finalExpiry));
   }

   public static Component buildFreezeNotice(FileConfiguration cfg, String adminName, long expiryMs) {
      String template = expiryMs == -1L
         ? cfg.getString("freeze.notify-permanent", "<aqua>❄ You are frozen by <white><admin></white>.")
         : cfg.getString("freeze.notify-timed", "<aqua>❄ You are frozen by <white><admin></white>. <gray>(<time> left)");
      return MM.deserialize(template.replace("<admin>", adminName).replace("<time>", DurationUtil.remaining(expiryMs)));
   }

   private static Component buildScreen(List<String> lines, String n, String reason, String admin, String date, String expires, String appeal) {
      StringBuilder sb = new StringBuilder();

      for (String line : lines) {
         sb.append(
               line.replace("<n>", n != null ? n : "")
                  .replace("<reason>", reason != null ? reason : "")
                  .replace("<admin>", admin != null ? admin : "")
                  .replace("<date>", date != null ? date : "")
                  .replace("<expires>", expires != null ? expires : "")
                  .replace("<discord>", appeal != null ? appeal : "")
                  .replace("<appeal>", appeal != null ? appeal : "")
            )
            .append("\n");
      }

      return MM.deserialize(sb.toString().trim());
   }

   private static String formatDate(long epochMs) {
      return DATE_FMT.format(Instant.ofEpochMilli(epochMs));
   }
}
