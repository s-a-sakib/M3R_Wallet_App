package com.m3r.wallet.core.crypto;

import java.util.Arrays;

public class M3RAddressFactory {

    public static final byte VERSION = (byte) 0x35;

    public static WalletKey generate(String mnemonic) {
        byte[] privateKey;
        String usedMnemonic = null;

        if (mnemonic == null || mnemonic.trim().isEmpty()) {
            privateKey = PrivateKeyGenerator.random();
        } else {
            usedMnemonic = mnemonic.trim();
            privateKey = PrivateKeyGenerator.fromMnemonic(usedMnemonic);
        }

        byte[] publicKeyCompressed = PublicKeyGenerator.compressed(privateKey);
        byte[] k = Hash.KECCAK_256(publicKeyCompressed);
        byte[] payload20 = Arrays.copyOfRange(k, 12, 32);
        String address = Base58Check.encode(VERSION, payload20);

        return new WalletKey(privateKey, publicKeyCompressed, address, k, payload20, VERSION, usedMnemonic);
    }

    public static byte[] payload20FromAddress(String base58Address) {
        return Base58Check.decodePayload(base58Address, VERSION);
    }

    public static class WalletKey {
        public final byte[] privateKey;
        public final byte[] publicKeyCompressed;
        public final String addressBase58;
        public final byte[] keccak256OfPub;
        public final byte[] payload20;
        public final byte version;
        public final String mnemonic;

        public WalletKey(byte[] privateKey, byte[] publicKeyCompressed, String addressBase58,
                         byte[] keccak256OfPub, byte[] payload20, byte version, String mnemonic) {
            this.privateKey = privateKey;
            this.publicKeyCompressed = publicKeyCompressed;
            this.addressBase58 = addressBase58;
            this.keccak256OfPub = keccak256OfPub;
            this.payload20 = payload20;
            this.version = version;
            this.mnemonic = mnemonic;
        }

        public String privateKeyHex() { return HexUtil.toHex(privateKey); }
        public String publicKeyHex()  { return HexUtil.toHex(publicKeyCompressed); }
        public String payload20Hex()  { return HexUtil.toHex(payload20); }
    }

    public static class HexUtil {
        public static String toHex(byte[] b) {
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        }
    }
}
