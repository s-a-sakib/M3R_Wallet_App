package com.m3r.wallet.core.crypto;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DLSequence;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.bouncycastle.jce.spec.ECParameterSpec;
import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;

public class EcdsaSecp256k1 {

    private static final ECParameterSpec SPEC = ECNamedCurveTable.getParameterSpec("secp256k1");
    private static final ECDomainParameters DOMAIN = new ECDomainParameters(
            SPEC.getCurve(), SPEC.getG(), SPEC.getN(), SPEC.getH());

    private EcdsaSecp256k1() {}

    public static byte[] signDer(byte[] hash32, byte[] privKey32) {
        BigInteger d = new BigInteger(1, privKey32);
        ECPrivateKeyParameters priv = new ECPrivateKeyParameters(d, DOMAIN);

        ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
        signer.init(true, priv);

        BigInteger[] sig = signer.generateSignature(hash32);
        BigInteger r = sig[0], s = sig[1];

        // Low-S normalization
        BigInteger halfN = SPEC.getN().shiftRight(1);
        if (s.compareTo(halfN) > 0) s = SPEC.getN().subtract(s);

        return derEncode(r, s);
    }

    public static boolean verifyDer(byte[] hash32, byte[] derSig, byte[] compressedPubKey33) {
        try {
            BigInteger[] rs = derDecode(derSig);
            ECPoint Q = SPEC.getCurve().decodePoint(compressedPubKey33).normalize();
            ECPublicKeyParameters pub = new ECPublicKeyParameters(Q, DOMAIN);
            ECDSASigner verifier = new ECDSASigner();
            verifier.init(false, pub);
            return verifier.verifySignature(hash32, rs[0], rs[1]);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derEncode(BigInteger r, BigInteger s) {
        try {
            ASN1EncodableVector v = new ASN1EncodableVector();
            v.add(new ASN1Integer(r));
            v.add(new ASN1Integer(s));
            return new DERSequence(v).getEncoded();
        } catch (Exception e) {
            throw new RuntimeException("DER encode failed", e);
        }
    }

    private static BigInteger[] derDecode(byte[] der) {
        try (ASN1InputStream in = new ASN1InputStream(new ByteArrayInputStream(der))) {
            DLSequence seq = (DLSequence) in.readObject();
            return new BigInteger[]{
                    ((ASN1Integer) seq.getObjectAt(0)).getValue(),
                    ((ASN1Integer) seq.getObjectAt(1)).getValue()
            };
        } catch (Exception e) {
            throw new RuntimeException("DER decode failed", e);
        }
    }
}
