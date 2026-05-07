package com.m3r.wallet.core.crypto;

import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.digests.SHA512Digest;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

public class PrivateKeyGenerator {

    private static final BigInteger CURVE_N =
            new BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16);

    public static byte[] fromMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.trim().isEmpty())
            throw new IllegalArgumentException("Mnemonic cannot be empty");

        // Support any mnemonic/seed phrase length (not just BIP-39 12/24 words)
        PKCS5S2ParametersGenerator gen = new PKCS5S2ParametersGenerator(new SHA512Digest());
        byte[] salt = "mnemonic".getBytes(StandardCharsets.UTF_8);
        gen.init(mnemonic.trim().getBytes(StandardCharsets.UTF_8), salt, 2048);

        byte[] seed = ((KeyParameter) gen.generateDerivedParameters(512)).getKey();
        byte[] privateKey = Hash.SHA_256(seed);
        return normalize(privateKey);
    }

    public static byte[] random() {
        SecureRandom rng = new SecureRandom();
        byte[] key = new byte[32];
        do { rng.nextBytes(key); } while (!isValid(key));
        return key;
    }

    private static boolean isValid(byte[] key) {
        BigInteger k = new BigInteger(1, key);
        return k.compareTo(BigInteger.ONE) >= 0 && k.compareTo(CURVE_N) < 0;
    }

    private static byte[] normalize(byte[] key) {
        BigInteger k = new BigInteger(1, key).mod(CURVE_N.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        byte[] out = k.toByteArray();
        if (out.length > 32) out = Arrays.copyOfRange(out, out.length - 32, out.length);
        else if (out.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(out, 0, padded, 32 - out.length, out.length);
            out = padded;
        }
        return out;
    }
}
