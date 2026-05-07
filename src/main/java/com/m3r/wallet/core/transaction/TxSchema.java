package com.m3r.wallet.core.transaction;

public final class TxSchema {

    public enum Version {
        ALPHA(1);
        public final int code;
        Version(int code) { this.code = code; }
    }

    public enum ChainID {
        MAINNET(1), TESTNET(2);
        public final int id;
        ChainID(int id) { this.id = id; }

        public String label() { return this == MAINNET ? "M3R Main Net" : "M3R Test Net"; }
    }

    public enum TxType {
        TRANSFER(0), ESCROW_CREATE(1), ESCROW_RELEASE(2), ESCROW_REFUND(3);
        public final int code;
        TxType(int code) { this.code = code; }
    }

    private TxSchema() {}
}
