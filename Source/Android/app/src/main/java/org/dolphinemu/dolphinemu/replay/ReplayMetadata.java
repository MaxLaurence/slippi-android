// Copyright 2026 Slippi
// Licensed under GPLv2+
package org.dolphinemu.dolphinemu.replay;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.util.SparseArray;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

    public static synchronized ReplayMetadata parse(Context ctx, ReplayItem item) {
        if (item == null) return UNPARSEABLE;
        if (item.isLocalFile()) return parse(item.file());
        String key = item.stableKey();
        long mtime = item.lastModified();
        long size = item.length();
        ReplayMetadata hit = CACHE.get(key);
        if (hit != null && hit.mtimeAtParse == mtime && hit.sizeAtParse == size) {
            return hit;
        }
        ReplayMetadata fresh = parseUncached(ctx, item.uri(), key, mtime, size);
        CACHE.put(key, fresh);
        return fresh;
    }

    private static ReplayMetadata parseUncached(File f, long mtime, long size) {
        if (size < 0x100) return UNPARSEABLE;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] head = new byte[Math.min(2048, (int) size)];
            raf.seek(0);
            raf.readFully(head);
            byte[] tail = new byte[(int) Math.min(4096, size)];
            raf.seek(size - tail.length);
            raf.readFully(tail);
            return parseBuffers(head, tail, mtime, size);
        } catch (IOException ex) {
            Log.w(TAG, "parse " + f + ": " + ex);
            return UNPARSEABLE;
        }
    }

    private static ReplayMetadata parseUncached(Context ctx, Uri uri, String key,
                                                long mtime, long size) {
        if (size < 0x100) return UNPARSEABLE;
        try {
            byte[] head = readRange(ctx, uri, 0, Math.min(2048, size));
            byte[] tail = readRange(ctx, uri, Math.max(0, size - 4096), Math.min(4096, size));
            return parseBuffers(head, tail, mtime, size);
        } catch (IOException ex) {
            Log.w(TAG, "parse " + key + ": " + ex);
            return UNPARSEABLE;
        }
    }

    private static ReplayMetadata parseBuffers(byte[] head, byte[] tail, long mtime, long size) {
        if (head == null || head.length < 0x100) return UNPARSEABLE;
        try {
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
            int lastFrame = readLastFrame(tail);
            return new ReplayMetadata(stage, chars, lastFrame, mtime, size, true);
        } catch (RuntimeException ex) {
            Log.w(TAG, "parse replay metadata failed: " + ex);
            return UNPARSEABLE;
        }
    }

    /**
     * The metadata block sits at end-of-file. Scan the last 4 KB for the
     * UBJSON key "lastFrame" → i32 marker. Falls back to -123 on miss,
     * which the UI displays as "?".
     */
    private static int readLastFrame(byte[] tail) {
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

    private static byte[] readRange(Context ctx, Uri uri, long offset, long length)
            throws IOException {
        byte[] out = new byte[(int) length];
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("openInputStream returned null");
            skipFully(in, offset);
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
            if (off == out.length) return out;
            byte[] shortOut = new byte[off];
            System.arraycopy(out, 0, shortOut, 0, off);
            return shortOut;
        }
    }

    private static void skipFully(InputStream in, long bytes) throws IOException {
        long remaining = bytes;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
                continue;
            }
            if (in.read() < 0) throw new IOException("EOF while skipping");
            remaining--;
        }
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
        // Melee internal-stage IDs, matching Project Slippi's public stage table.
        STAGES.put(2, "Fountain of Dreams");
        STAGES.put(3, "Pokemon Stadium");
        STAGES.put(4, "Princess Peach's Castle");
        STAGES.put(5, "Kongo Jungle");
        STAGES.put(6, "Brinstar");
        STAGES.put(7, "Corneria");
        STAGES.put(8, "Yoshi's Story");
        STAGES.put(9, "Onett");
        STAGES.put(10, "Mute City");
        STAGES.put(11, "Rainbow Cruise");
        STAGES.put(12, "Jungle Japes");
        STAGES.put(13, "Great Bay");
        STAGES.put(14, "Hyrule Temple");
        STAGES.put(15, "Brinstar Depths");
        STAGES.put(16, "Yoshi's Island");
        STAGES.put(17, "Green Greens");
        STAGES.put(18, "Fourside");
        STAGES.put(19, "Mushroom Kingdom I");
        STAGES.put(20, "Mushroom Kingdom II");
        STAGES.put(22, "Venom");
        STAGES.put(23, "Poke Floats");
        STAGES.put(24, "Big Blue");
        STAGES.put(25, "Icicle Mountain");
        STAGES.put(26, "Icetop");
        STAGES.put(27, "Flat Zone");
        STAGES.put(28, "Dream Land N64");
        STAGES.put(29, "Yoshi's Island N64");
        STAGES.put(30, "Kongo Jungle N64");
        STAGES.put(31, "Battlefield");
        STAGES.put(32, "Final Destination");
        STAGES.put(33, "Target Test (Mario)");
        STAGES.put(34, "Target Test (Captain Falcon)");
        STAGES.put(35, "Target Test (Young Link)");
        STAGES.put(36, "Target Test (Donkey Kong)");
        STAGES.put(37, "Target Test (Dr. Mario)");
        STAGES.put(38, "Target Test (Falco)");
        STAGES.put(39, "Target Test (Fox)");
        STAGES.put(40, "Target Test (Ice Climbers)");
        STAGES.put(41, "Target Test (Kirby)");
        STAGES.put(42, "Target Test (Bowser)");
        STAGES.put(43, "Target Test (Link)");
        STAGES.put(44, "Target Test (Luigi)");
        STAGES.put(45, "Target Test (Marth)");
        STAGES.put(46, "Target Test (Mewtwo)");
        STAGES.put(47, "Target Test (Ness)");
        STAGES.put(48, "Target Test (Peach)");
        STAGES.put(49, "Target Test (Pichu)");
        STAGES.put(50, "Target Test (Pikachu)");
        STAGES.put(51, "Target Test (Jigglypuff)");
        STAGES.put(52, "Target Test (Samus)");
        STAGES.put(53, "Target Test (Sheik)");
        STAGES.put(54, "Target Test (Yoshi)");
        STAGES.put(55, "Target Test (Zelda)");
        STAGES.put(56, "Target Test (Mr. Game & Watch)");
        STAGES.put(57, "Target Test (Roy)");
        STAGES.put(58, "Target Test (Ganondorf)");
        STAGES.put(84, "Home-Run Contest");

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
