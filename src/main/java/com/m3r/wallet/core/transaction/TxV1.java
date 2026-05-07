package com.m3r.wallet.core.transaction;

import com.m3r.wallet.core.crypto.EcdsaSecp256k1;
import com.m3r.wallet.core.crypto.Hash;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

public final class TxV1 {

    public static final int ADDRESS_LEN = 20;
    public static final int HASH_LEN = 32;

    private final TxSchema.Version version;
    private final TxSchema.ChainID chainId;
    private final TxSchema.TxType type;
    private final long nonce;
    private final long fee;
    private final long timestamp;
    private final byte[] fromAddr20;
    private final byte[] payload;
    private final byte[] memo;
    private final byte sigScheme;
    private byte[] signature;

    public TxV1(TxSchema.Version version, TxSchema.ChainID chainId, TxSchema.TxType type,
                long nonce, long fee, long timestamp,
                byte[] fromAddr20, byte[] payload, byte[] memo, byte sigScheme) {
        this.version = version;
        this.chainId = chainId;
        this.type = type;
        this.nonce = nonce;
        this.fee = fee;
        this.timestamp = timestamp;
        this.fromAddr20 = requireLen(fromAddr20, ADDRESS_LEN, "fromAddr20");
        this.payload = payload == null ? new byte[0] : payload.clone();
        this.memo = memo == null ? new byte[0] : memo.clone();
        this.sigScheme = sigScheme;
    }

    public byte[] encodeForSigning() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Codec.putU16(out, version.code);
        Codec.putU32(out, chainId.id);
        Codec.putI8(out, type.code);
        Codec.putU64(out, nonce);
        Codec.putU64(out, fee);
        Codec.putU64(out, timestamp);
        Codec.write(out, fromAddr20);
        Codec.putVarBytes(out, payload);
        Codec.putVarBytes(out, memo);
        Codec.putI8(out, sigScheme & 0xFF);
        return out.toByteArray();
    }

    public byte[] encodeFull() {
        if (signature == null) throw new IllegalStateException("Not signed");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Codec.write(out, encodeForSigning());
        Codec.putVarBytes(out, signature);
        return out.toByteArray();
    }

    public byte[] txHashKeccak256() {
        return Hash.KECCAK_256(encodeForSigning());
    }

    public void signSecp256k1(byte[] privateKey32) {
        this.signature = EcdsaSecp256k1.signDer(txHashKeccak256(), privateKey32);
    }

    public String txHashHex() {
        byte[] h = txHashKeccak256();
        StringBuilder sb = new StringBuilder();
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] requireLen(byte[] x, int len, String name) {
        if (x == null || x.length != len)
            throw new IllegalArgumentException(name + " must be " + len + " bytes");
        return x.clone();
    }

    public TxSchema.TxType getType() { return type; }
    public long getNonce() { return nonce; }
    public long getFee() { return fee; }
    public long getTimestamp() { return timestamp; }
    public byte[] getFromAddr20() { return fromAddr20.clone(); }
    public byte[] getSignature() { return signature == null ? null : signature.clone(); }
}
