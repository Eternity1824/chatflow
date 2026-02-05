package com.chatflow.server;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Fast ISO-8601 timestamp formatter with per-second caching.
 * Outputs: yyyy-MM-dd'T'HH:mm:ss.SSSZ (UTC, 'Z' suffix).
 */
public final class FastTimestampFormatter {
    private static final DateTimeFormatter SECOND_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                    .withZone(ZoneOffset.UTC);

    private static final class Cache {
        final long second;
        final byte[] prefixBytes; // 19 bytes, ASCII

        Cache(long second, byte[] prefixBytes) {
            this.second = second;
            this.prefixBytes = prefixBytes;
        }
    }

    private static volatile Cache cache = new Cache(-1, new byte[19]);
    private static final ThreadLocal<byte[]> BUF =
            ThreadLocal.withInitial(() -> new byte[24]);

    private FastTimestampFormatter() {
    }

    public static void writeIsoTimestamp(JsonGenerator gen) throws IOException {
        byte[] out = BUF.get();
        long now = System.currentTimeMillis();
        long sec = now / 1000;

        Cache c = cache;
        if (c.second != sec) {
            String prefix = SECOND_FMT.format(Instant.ofEpochSecond(sec));
            byte[] prefixBytes = prefix.getBytes(StandardCharsets.US_ASCII);
            c = new Cache(sec, prefixBytes);
            cache = c;
        }

        System.arraycopy(c.prefixBytes, 0, out, 0, 19);
        out[19] = '.';
        int ms = (int) (now - (sec * 1000));
        out[20] = (byte) ('0' + (ms / 100));
        out[21] = (byte) ('0' + ((ms / 10) % 10));
        out[22] = (byte) ('0' + (ms % 10));
        out[23] = 'Z';

        gen.writeRawUTF8String(out, 0, 24);
    }
}
