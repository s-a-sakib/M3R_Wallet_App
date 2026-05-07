package com.m3r.wallet.core.transaction;

import java.io.ByteArrayOutputStream;

public final class Codec {
    private Codec() {}

    public static void putI8(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
    }

    public static void putU16(ByteArrayOutputStream out, int v) {
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static void putU32(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public static void putU64(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) out.write((int) ((v >> (i * 8)) & 0xFF));
    }

    public static void write(ByteArrayOutputStream out, byte[] data) {
        try { out.write(data); } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static void putVarBytes(ByteArrayOutputStream out, byte[] data) {
        putU32(out, data.length);
        write(out, data);
    }
}
