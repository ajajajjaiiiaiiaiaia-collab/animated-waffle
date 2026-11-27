package com.starkmc.moderation.data;

public record WarnEntry(int id, String reason, String by, long at) {
}
