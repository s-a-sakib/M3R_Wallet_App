package com.m3r.wallet.core.crypto;

import org.bouncycastle.jcajce.provider.digest.Keccak;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

public class Hash {
    private Hash() {}

    public static byte[] SHA_256(byte[] text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(text);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] SHA_256(String text) {
        return SHA_256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static byte[] SHA_512(byte[] text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            return md.digest(text);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] KECCAK_256(byte[] text) {
        Keccak.Digest256 digest = new Keccak.Digest256();
        return digest.digest(text);
    }

    public static byte[] KECCAK_256(String text) {
        return KECCAK_256(text.getBytes(StandardCharsets.UTF_8));
    }
}
