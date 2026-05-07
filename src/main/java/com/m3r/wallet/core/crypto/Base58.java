package com.m3r.wallet.core.crypto;

import java.util.Arrays;

public class Base58 {
    private static final char[] ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();
    private static final int[] INDEXES = new int[128];

    static {
        Arrays.fill(INDEXES, -1);
        for (int i = 0; i < ALPHABET.length; i++)
            INDEXES[ALPHABET[i]] = i;
    }

    public static String encode(byte[] input) {
        if (input.length == 0) return "";
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) zeros++;

        byte[] temp = Arrays.copyOf(input, input.length);
        char[] encoded = new char[temp.length * 2];
        int outputStart = encoded.length;

        for (int inputStart = zeros; inputStart < temp.length; ) {
            encoded[--outputStart] = ALPHABET[divmod(temp, inputStart, 256, 58)];
            if (temp[inputStart] == 0) inputStart++;
        }
        while (outputStart < encoded.length && encoded[outputStart] == ALPHABET[0]) outputStart++;
        while (zeros-- > 0) encoded[--outputStart] = ALPHABET[0];
        return new String(encoded, outputStart, encoded.length - outputStart);
    }

    public static byte[] decode(String input) {
        if (input.isEmpty()) return new byte[0];
        byte[] input58 = new byte[input.length()];
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int d = c < 128 ? INDEXES[c] : -1;
            if (d < 0) throw new IllegalArgumentException("Invalid Base58 char: " + c);
            input58[i] = (byte) d;
        }
        int zeros = 0;
        while (zeros < input58.length && input58[zeros] == 0) zeros++;
        byte[] decoded = new byte[input.length()];
        int outputStart = decoded.length;
        for (int inputStart = zeros; inputStart < input58.length; ) {
            decoded[--outputStart] = divmod(input58, inputStart, 58, 256);
            if (input58[inputStart] == 0) inputStart++;
        }
        while (outputStart < decoded.length && decoded[outputStart] == 0) outputStart++;
        return Arrays.copyOfRange(decoded, outputStart - zeros, decoded.length);
    }

    private static byte divmod(byte[] number, int firstDigit, int base, int divisor) {
        int remainder = 0;
        for (int i = firstDigit; i < number.length; i++) {
            int d = (base * remainder) + (number[i] & 0xFF);
            number[i] = (byte) (d / divisor);
            remainder = d % divisor;
        }
        return (byte) remainder;
    }
}
