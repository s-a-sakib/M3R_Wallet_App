package com.m3r.wallet.core.crypto;

import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;

public class PublicKeyGenerator {

    private static final ECParameterSpec SPEC = ECNamedCurveTable.getParameterSpec("secp256k1");

    public static byte[] compressed(byte[] privateKey32) {
        BigInteger d = new BigInteger(1, privateKey32);
        ECPoint Q = SPEC.getG().multiply(d).normalize();
        return Q.getEncoded(true); // compressed = 33 bytes
    }
}
