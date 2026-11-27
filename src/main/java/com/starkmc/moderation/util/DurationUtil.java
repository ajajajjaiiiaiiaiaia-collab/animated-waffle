package com.starkmc.moderation.util;

import java.util.concurrent.TimeUnit;

public final class DurationUtil {
   private DurationUtil() {
   }

   public static long parse(String input) {
      if (input == null) {
         return -2L;
      } else if (!input.equalsIgnoreCase("permanent") && !input.equalsIgnoreCase("perm")) {
         try {
            char unit = Character.toLowerCase(input.charAt(input.length() - 1));
            long value = Long.parseLong(input.substring(0, input.length() - 1));

            return switch (unit) {
               case 'd' -> TimeUnit.DAYS.toMillis(value);
               case 'h' -> TimeUnit.HOURS.toMillis(value);
               case 'm' -> TimeUnit.MINUTES.toMillis(value);
               case 's' -> TimeUnit.SECONDS.toMillis(value);
               case 'w' -> TimeUnit.DAYS.toMillis(value * 7L);
               default -> -2L;
            };
         } catch (StringIndexOutOfBoundsException | NumberFormatException var4) {
            return -2L;
         }
      } else {
         return -1L;
      }
   }

   public static String format(long ms) {
      if (ms == -1L) {
         return "Permanent";
      } else {
         long seconds = ms / 1000L;
         long days = seconds / 86400L;
         seconds %= 86400L;
         long hours = seconds / 3600L;
         seconds %= 3600L;
         long minutes = seconds / 60L;
         seconds %= 60L;
         StringBuilder sb = new StringBuilder();
         if (days > 0L) {
            sb.append(days).append("d ");
         }

         if (hours > 0L) {
            sb.append(hours).append("h ");
         }

         if (minutes > 0L) {
            sb.append(minutes).append("m ");
         }

         if (seconds > 0L) {
            sb.append(seconds).append("s ");
         }

         return sb.toString().trim();
      }
   }

   public static String remaining(long expiryMs) {
      if (expiryMs == -1L) {
         return "Permanent";
      } else {
         long diff = expiryMs - System.currentTimeMillis();
         return diff <= 0L ? "Expired" : format(diff);
      }
   }

   public static String remainingHms(long expiryMs) {
      if (expiryMs == -1L) {
         return "Permanent";
      } else {
         long seconds = Math.max(0L, (expiryMs - System.currentTimeMillis() + 999L) / 1000L);
         long hours = seconds / 3600L;
         long minutes = seconds % 3600L / 60L;
         long secs = seconds % 60L;
         return hours + "h " + minutes + "m " + secs + "s";
      }
   }
}
