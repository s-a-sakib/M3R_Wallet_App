package com.m3r.wallet.core.crypto;

public class Main {
    public static void main(String[] args) {
        M3RAddressFactory.WalletKey w =
                M3RAddressFactory.generate(
                        "ed177a698304af4f24417af506b65498319c760ce0b48052257c1f07636ece02"
                );

        System.out.println("Private : " + w.privateKeyHex());
        System.out.println("Public  : " + w.publicKeyHex());
        System.out.println("Payload : " + w.payload20Hex());
        System.out.println("Address : " + w.addressBase58);
    }
}
