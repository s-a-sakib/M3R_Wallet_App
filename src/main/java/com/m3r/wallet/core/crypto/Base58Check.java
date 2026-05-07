package com.m3r.wallet.core.crypto;

import java.util.Arrays;

public class Base58Check {

    public static String encode(byte version, byte[] payload) {
        byte[] data = new byte[1 + payload.length];
        data[0] = version;
        System.arraycopy(payload, 0, data, 1, payload.length);

        byte[] checksum = checksum(data);
        byte[] full = new byte[data.length + 4];
        System.arraycopy(data, 0, full, 0, data.length);
        System.arraycopy(checksum, 0, full, data.length, 4);

        return Base58.encode(full);
    }

    public static byte[] decodePayload(String encoded, byte expectedVersion) {
        byte[] full = Base58.decode(encoded);
        if (full.length < 5) throw new IllegalArgumentException("Too short");

        byte[] data = Arrays.copyOfRange(full, 0, full.length - 4);
        byte[] checksum = Arrays.copyOfRange(full, full.length - 4, full.length);
        byte[] expected = checksum(data);

        if (!Arrays.equals(checksum, expected)) throw new IllegalArgumentException("Bad checksum");
        if (data[0] != expectedVersion) throw new IllegalArgumentException("Bad version");

        return Arrays.copyOfRange(data, 1, data.length);
    }

    private static byte[] checksum(byte[] data) {
        byte[] h1 = Hash.SHA_256(data);
        byte[] h2 = Hash.SHA_256(h1);
        return Arrays.copyOfRange(h2, 0, 4);
    }
}
