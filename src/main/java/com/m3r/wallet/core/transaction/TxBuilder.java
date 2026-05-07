package com.m3r.wallet.core.transaction;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class TxBuilder {

    public static final byte SIG_ECDSA = 1;

    private TxBuilder() {}

    public static TxV1 transfer(TxSchema.ChainID chainId, long nonce, long fee,
                                 byte[] fromAddr20, byte[] toAddr20, long amount, byte[] memo) {
        byte[] payload = encodeTransfer(toAddr20, amount);
        return new TxV1(TxSchema.Version.ALPHA, chainId, TxSchema.TxType.TRANSFER,
                nonce, fee, nowSec(), fromAddr20, payload, memo, SIG_ECDSA);
    }

    public static TxV1 escrowCreate(TxSchema.ChainID chainId, long nonce, long fee,
                                     byte[] fromAddr20, byte[] escrowId32,
                                     byte[] buyer20, byte[] seller20, byte[] arbiter20,
                                     long amount, long expiryTs,
                                     int releaseMode, int disputeMode,
                                     byte[] metaHash32, byte[] memo) {
        byte[] payload = encodeEscrowCreate(escrowId32, buyer20, seller20, arbiter20,
                amount, expiryTs, releaseMode, disputeMode, metaHash32);
        return new TxV1(TxSchema.Version.ALPHA, chainId, TxSchema.TxType.ESCROW_CREATE,
                nonce, fee, nowSec(), fromAddr20, payload, memo, SIG_ECDSA);
    }

    public static TxV1 escrowRelease(TxSchema.ChainID chainId, long nonce, long fee,
                                      byte[] fromAddr20, byte[] escrowId32,
                                      byte[] toAddr20, long amount, byte[] memo) {
        byte[] payload = encodeEscrowRelease(escrowId32, toAddr20, amount);
        return new TxV1(TxSchema.Version.ALPHA, chainId, TxSchema.TxType.ESCROW_RELEASE,
                nonce, fee, nowSec(), fromAddr20, payload, memo, SIG_ECDSA);
    }

    public static TxV1 escrowRefund(TxSchema.ChainID chainId, long nonce, long fee,
                                     byte[] fromAddr20, byte[] escrowId32,
                                     byte[] toAddr20, long amount, byte[] memo) {
        byte[] payload = encodeEscrowRefund(escrowId32, toAddr20, amount);
        return new TxV1(TxSchema.Version.ALPHA, chainId, TxSchema.TxType.ESCROW_REFUND,
                nonce, fee, nowSec(), fromAddr20, payload, memo, SIG_ECDSA);
    }

    // ---- Payload encoders ----

    private static byte[] encodeTransfer(byte[] toAddr20, long amount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Codec.write(out, requireLen(toAddr20, 20, "toAddr20"));
        Codec.putU64(out, amount);
        return out.toByteArray();
    }

    private static byte[] encodeEscrowCreate(byte[] escrowId32, byte[] buyer20, byte[] seller20,
                                              byte[] arbiter20, long amount, long expiryTs,
                                              int releaseMode, int disputeMode, byte[] metaHash32) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Codec.write(out, requireLen(escrowId32, 32, "escrowId32"));
        Codec.write(out, requireLen(buyer20, 20, "buyer20"));
        Codec.write(out, requireLen(seller20, 20, "seller20"));
        Codec.write(out, requireLen(arbiter20, 20, "arbiter20"));
        Codec.putU64(out, amount);
        Codec.putU64(out, expiryTs);
        Codec.putI8(out, releaseMode);
        Codec.putI8(out, disputeMode);
        Codec.write(out, requireLen(metaHash32, 32, "metaHash32"));
        return out.toByteArray();
    }

    private static byte[] encodeEscrowRelease(byte[] escrowId32, byte[] toAddr20, long amount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Codec.write(out, requireLen(escrowId32, 32, "escrowId32"));
        Codec.write(out, requireLen(toAddr20, 20, "toAddr20"));
        Codec.putU64(out, amount);
        return out.toByteArray();
    }

    private static byte[] encodeEscrowRefund(byte[] escrowId32, byte[] toAddr20, long amount) {
        return encodeEscrowRelease(escrowId32, toAddr20, amount); // same structure
    }

    private static byte[] requireLen(byte[] x, int len, String name) {
        if (x == null || x.length != len)
            throw new IllegalArgumentException(name + " must be " + len + " bytes, got " + (x == null ? "null" : x.length));
        return x;
    }

    private static long nowSec() {
        return System.currentTimeMillis() / 1000L;
    }
}
