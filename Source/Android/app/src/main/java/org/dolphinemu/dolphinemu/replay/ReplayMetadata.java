// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Lightweight pure-Java .slp header parser. Reads just enough of the
 * UBJSON-wrapped binary stream to produce a single-line summary for the
 * browser row — stage, character matchups, mtime — without pulling in
 * the C++ SlippiGame parser (which would require a JNI hop per row).
 *
 * Format ref: https://github.com/project-slippi/slippi-wiki/blob/master/SPEC.md
 * - File starts with UBJSON: {"raw": [u8[N]] then payload-typed events.
 * - First payload event is EVENT_GAME_INIT (0x36). Stage at offset 0x13
 *   (u16 BE). Per-port chunk starts at offset 0x65, 0x24 bytes each.
 *   characterId u8 @ +0x0, characterColor u8 @ +0x3,
 *   playerType u8 @ +0x1 (0=human, 1=cpu, 2=demo, 3=empty).
 * - End of file: a UBJSON object with "metadata" → "lastFrame" (i32).
 *
 * This intentionally only parses fields the browser displays. Anything
 * weird (truncated file, unknown version) falls through to a "couldn't
 * read" placeholder; the row still renders.
 */
public final class ReplayMetadata {

    public final String stageName;
    public final String[] charNames;   // per-port short name (e.g. "Marth"), null if empty/CPU-only
    public final int lastFrame;        // -123 means unknown / couldn't read
    public final long mtimeAtParse;    // for cache invalidation
    public final long sizeAtParse;
    public final boolean ok;

    private ReplayMetadata(String stage, String[] chars, int lastFrame,
                           long mtime, long size, boolean ok) {
        this.stageName = stage;
        this.charNames = chars;
        this.lastFrame = lastFrame;
        this.mtimeAtParse = mtime;
        this.sizeAtParse = size;
        this.ok = ok;
    }

    private static final ReplayMetadata UNPARSEABLE =
            new ReplayMetadata(null, null, -123, 0, 0, false);

    private static final String TAG = "ReplayMetadata";
    private static final Map<String, ReplayMetadata> CACHE = new HashMap<>();

    /** Cached by absolute path; invalidated when mtime or size changes. */
    public static synchronized ReplayMetadata parse(File f) {
        if (f == null || !f.isFile()) return UNPARSEABLE;
        String key = f.getAbsolutePath();
        long mtime = f.lastModified();
        long size = f.length();
        ReplayMetadata hit = CACHE.get(key);
        if (hit != null && hit.mtimeAtParse == mtime && hit.sizeAtParse == size) {
            return hit;
        }
        ReplayMetadata fresh = parseUncached(f, mtime, size);
        CACHE.put(key, fresh);
        return fresh;
    }

    private static ReplayMetadata parseUncached(File f, long mtime, long size) {
        if (size < 0x100) return UNPARSEABLE;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[Math.min(2048, (int) size)];
            raf.seek(0);
            raf.readFully(head);

            // Skip the UBJSON wrapper. Per SlippiGame.cpp's
            // getRawDataPosition, the raw event stream starts at byte 0
            // if the file begins with 0x36, or at byte 15 if it begins
            // with '{' (the standard UBJSON-wrapped layout). Anything
            // else is unrecognised.
            int rawStart;
            if (head[0] == 0x36) {
                rawStart = 0;
            } else if (head[0] == '{') {
                rawStart = 15;
            } else {
                return UNPARSEABLE;
            }

            // The first event in the stream is always EVENT_PAYLOADS
            // (0x35), which describes the payload sizes of subsequent
            // events. Skip past it to find the real GAME_INIT (0x36)
            // — a naive "find the first 0x36 byte" matches size fields
            // inside this table instead.
            int gameInitOff;
            if (rawStart > 0) {
                if (rawStart + 1 >= head.length || head[rawStart] != 0x35) {
                    return UNPARSEABLE;
                }
                int payloadSizesLen = head[rawStart + 1] & 0xFF;  // length byte counts itself + triples
                gameInitOff = rawStart + 1 + payloadSizesLen;
            } else {
                gameInitOff = 0;
            }
            if (gameInitOff + 1 >= head.length || head[gameInitOff] != 0x36) {
                return UNPARSEABLE;
            }

            // Layout per SlippiGame.cpp's handleGameInit, byte-indexed
            // from the start of the payload (right after the 0x36
            // command byte). All multi-byte ints are big-endian.
            //
            // The GAME_INFO_HEADER is treated as a u32[] in the C++
            // parser, indexed by `gameInfoHeader[N]`:
            //   - 4-byte version prefix at payload[0..3]
            //   - Header starts at payload[4]
            //   - gameInfoHeader[3] holds the stage in its low 16 bits
            //     → bytes payload[16..19], with stage at payload[18..19]
            //   - gameInfoHeader[24 + 9*i] holds player i's character word:
            //     → base byte = 4 + (24 + 9*i)*4 = 100 + 36*i = 0x64+0x24*i
            //     → characterId    = byte +0 (high byte of BE word)
            //     → playerType     = byte +1 (next byte down)
            //     → characterColor = byte +3 (low byte of BE word)
            int payload = gameInitOff + 1;
            int portsBase = payload + 0x64;
            int stageOff = payload + 0x12;
            if (portsBase + 0x24 * 4 > head.length || stageOff + 2 > head.length) {
                return UNPARSEABLE;
            }

            int stageId = readU16BE(head, stageOff);
            String stage = STAGES.get(stageId, "Stage " + stageId);
            String[] chars = new String[4];
            for (int port = 0; port < 4; port++) {
                int off = portsBase + 0x24 * port;
                int charId = head[off] & 0xFF;
                int playerType = head[off + 0x1] & 0xFF;
                // playerType: 0 = human, 1 = CPU, 2 = demo, 3 = empty
                if (playerType <= 1) {
                    chars[port] = CHARS.get(charId, "Char " + charId);
                }
            }
            int lastFrame = readLastFrame(raf, size);
            return new ReplayMetadata(stage, chars, lastFrame, mtime, size, true);
        } catch (IOException ex) {
            Log.w(TAG, "parse " + f + ": " + ex);
            return UNPARSEABLE;
        }
    }

    /**
     * The metadata block sits at end-of-file. Scan the last 4 KB for the
     * UBJSON key "lastFrame" → i32 marker. Falls back to -123 on miss,
     * which the UI displays as "?".
     */
    private static int readLastFrame(RandomAccessFile raf, long size) throws IOException {
        int tailLen = (int) Math.min(4096, size);
        byte[] tail = new byte[tailLen];
        raf.seek(size - tailLen);
        raf.readFully(tail);
        byte[] needle = "lastFrame".getBytes(StandardCharsets.US_ASCII);
        int idx = indexOf(tail, needle, 0);
        if (idx < 0) return -123;
        // After "lastFrame" we expect: 'l' marker + i32 BE. Walk forward
        // a handful of bytes to be permissive about minor format drift.
        for (int p = idx + needle.length; p < idx + needle.length + 8 && p + 4 < tail.length; p++) {
            if (tail[p] == 'l') {
                return ((tail[p + 1] & 0xFF) << 24)
                        | ((tail[p + 2] & 0xFF) << 16)
                        | ((tail[p + 3] & 0xFF) << 8)
                        | (tail[p + 4] & 0xFF);
            }
        }
        return -123;
    }

    private static int findFirstByte(byte[] buf, byte b, int from, int to) {
        for (int i = from; i < to; i++) if (buf[i] == b) return i;
        return -1;
    }

    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = from; i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static int readU16BE(byte[] buf, int off) {
        return ((buf[off] & 0xFF) << 8) | (buf[off + 1] & 0xFF);
    }

    /** Short, recognisable name suitable for a one-line row title. */
    public String shortTitle() {
        if (!ok) return null;
        StringBuilder players = new StringBuilder();
        for (String c : charNames) {
            if (c == null) continue;
            if (players.length() > 0) players.append(" vs ");
            players.append(c);
        }
        if (players.length() == 0) return stageName;
        return players + " on " + stageName;
    }

    public String durationLabel() {
        if (lastFrame <= -123) return "";
        int seconds = Math.max(0, (lastFrame + 123)) / 60;
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    public boolean isPlayable() {
        return ok && lastFrame > -123;
    }

    // --- ID → name tables -------------------------------------------------

    private static final SparseArray<String> STAGES = new SparseArray<>();
    private static final SparseArray<String> CHARS = new SparseArray<>();

    static {
        // Melee internal-stage IDs (legal tournament + common subset).
        STAGES.put(2, "Fountain of Dreams");
        STAGES.put(3, "Pokemon Stadium");
        STAGES.put(8, "Yoshi's Story");
        STAGES.put(28, "Dream Land");
        STAGES.put(31, "Battlefield");
        STAGES.put(32, "Final Destination");
        // The rest get rendered as "Stage N" — better than guessing.

        // External Melee character IDs (the EXTERNAL set is what GAME_INIT carries).
        CHARS.put(0,  "Falcon");
        CHARS.put(1,  "DK");
        CHARS.put(2,  "Fox");
        CHARS.put(3,  "Game & Watch");
        CHARS.put(4,  "Kirby");
        CHARS.put(5,  "Bowser");
        CHARS.put(6,  "Link");
        CHARS.put(7,  "Luigi");
        CHARS.put(8,  "Mario");
        CHARS.put(9,  "Marth");
        CHARS.put(10, "Mewtwo");
        CHARS.put(11, "Ness");
        CHARS.put(12, "Peach");
        CHARS.put(13, "Pikachu");
        CHARS.put(14, "Ice Climbers");
        CHARS.put(15, "Jigglypuff");
        CHARS.put(16, "Samus");
        CHARS.put(17, "Yoshi");
        CHARS.put(18, "Zelda");
        CHARS.put(19, "Sheik");
        CHARS.put(20, "Falco");
        CHARS.put(21, "Young Link");
        CHARS.put(22, "Dr. Mario");
        CHARS.put(23, "Roy");
        CHARS.put(24, "Pichu");
        CHARS.put(25, "Ganondorf");
    }
}
