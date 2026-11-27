package com.starkmc.moderation.data;

public record KickEntry(int id, String reason, String by, long at) {
}
