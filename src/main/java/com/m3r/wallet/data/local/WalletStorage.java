package com.m3r.wallet.data.local;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.m3r.wallet.core.transaction.TxSchema;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * Encrypted local storage for wallet data.
 * Uses Android EncryptedSharedPreferences (AES-256-GCM).
 */
public class WalletStorage {

    private static final String PREFS_NAME = "m3r_wallet_secure";
    private static final String KEY_WALLET = "wallet_data";
    private static final String KEY_TX_LIST = "tx_history";
    private static final String KEY_NODE_URL = "node_url";
    private static final String KEY_CHAIN_ID = "chain_id";
    private static final String KEY_USE_MOCK = "use_mock_node";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public WalletStorage(Context context) {
        SharedPreferences tempPrefs = null;
        try {
            tempPrefs = initEncryptedPrefs(context);
        } catch (Exception e) {
            // Failed to init encrypted storage (common on some devices/OS updates)
            // Attempt to recover by clearing the corrupted prefs file AND the MasterKey
            android.util.Log.e("WalletStorage", "EncryptedSharedPreferences init failed, attempting recovery", e);
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit();
                deleteMasterKey(); // Clear the Keystore entry
                tempPrefs = initEncryptedPrefs(context);
                android.util.Log.i("WalletStorage", "Recovery succeeded — encrypted storage re-initialized");
            } catch (Exception e2) {
                // F9: NEVER fall back to plaintext storage — private keys would be exposed
                android.util.Log.e("WalletStorage", "CRITICAL: EncryptedSharedPreferences recovery failed", e2);
                throw new RuntimeException(
                        "Cannot initialize secure storage. Your device may have a corrupted Keystore. " +
                        "Please clear app data or reinstall the app.", e2);
            }
        }
        this.prefs = tempPrefs;
    }

    private SharedPreferences initEncryptedPrefs(Context context) throws Exception {
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();

        return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    private void deleteMasterKey() {
        try {
            java.security.KeyStore keyStore = java.security.KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---- Wallet ----

    public void saveWallet(StoredWallet wallet) {
        prefs.edit().putString(KEY_WALLET, gson.toJson(wallet)).apply();
    }

    public StoredWallet loadWallet() {
        String json = prefs.getString(KEY_WALLET, null);
        if (json == null)
            return null;
        return gson.fromJson(json, StoredWallet.class);
    }

    public boolean hasWallet() {
        return prefs.contains(KEY_WALLET);
    }

    public void deleteWallet() {
        prefs.edit().remove(KEY_WALLET).apply();
    }

    // ---- Transactions ----

    public void updateTxStatus(String txHash, String newStatus) {
        if (txHash == null || newStatus == null) return;
        List<TxRecord> list = loadTxHistory();
        boolean changed = false;
        for (TxRecord tx : list) {
            if (txHash.equals(tx.txHash)) {
                if (!newStatus.equals(tx.status)) {
                    tx.status = newStatus;
                    changed = true;
                }
                break;
            }
        }
        if (changed) {
            prefs.edit().putString(KEY_TX_LIST, gson.toJson(list)).apply();
        }
    }

    public void saveTx(TxRecord record) {
        List<TxRecord> list = loadTxHistory();
        list.add(0, record); // newest first
        if (list.size() > 200)
            list = list.subList(0, 200); // cap at 200
        prefs.edit().putString(KEY_TX_LIST, gson.toJson(list)).apply();
    }

    public List<TxRecord> loadTxHistory() {
        String json = prefs.getString(KEY_TX_LIST, null);
        if (json == null)
            return new ArrayList<>();
        Type type = new TypeToken<List<TxRecord>>() {
        }.getType();
        List<TxRecord> list = gson.fromJson(json, type);
        return list != null ? list : new ArrayList<>();
    }

    public void clearTxHistory() {
        prefs.edit().remove(KEY_TX_LIST).apply();
    }

    /**
     * Returns true if a TxRecord with the same txHash AND type already exists locally.
     * Used by the history-sync path to avoid inserting duplicates.
     *
     * @param txHash the transaction hash to look for (may be null → returns false)
     * @param type   the record type to match (e.g. "RECEIVE", "ESCROW_RECEIVE")
     */
    public boolean hasTxRecord(String txHash, String type) {
        if (txHash == null || type == null) return false;
        for (TxRecord tx : loadTxHistory()) {
            if (txHash.equals(tx.txHash) && type.equals(tx.type)) {
                return true;
            }
        }
        return false;
    }

    // ---- Settings ----

    public void saveNodeUrl(String url) {
        prefs.edit().putString(KEY_NODE_URL, url).apply();
    }

    public String loadNodeUrl(String defaultUrl) {
        return prefs.getString(KEY_NODE_URL, defaultUrl);
    }

    public void saveChainId(TxSchema.ChainID chainId) {
        prefs.edit().putString(KEY_CHAIN_ID, chainId.name()).apply();
    }

    public TxSchema.ChainID loadChainId() {
        String name = prefs.getString(KEY_CHAIN_ID, TxSchema.ChainID.TESTNET.name());
        try {
            return TxSchema.ChainID.valueOf(name);
        } catch (Exception e) {
            return TxSchema.ChainID.TESTNET;
        }
    }

    public void saveUseMockNode(boolean useMock) {
        prefs.edit().putBoolean(KEY_USE_MOCK, useMock).apply();
    }

    public boolean loadUseMockNode() {
        return prefs.getBoolean(KEY_USE_MOCK, true); // default: use mock
    }

    // ---- PIN & Biometric ----

    // F17: PBKDF2 PIN hashing (100k iterations)
    private static final int PBKDF2_ITERATIONS = 100_000;
    private static final int PBKDF2_KEY_LENGTH = 256;

    public void savePinHash(String pin) {
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH);
            javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] hash = skf.generateSecret(spec).getEncoded();
            
            // Store as base64 to avoid encoding issues, prefix with v2 to differentiate
            String hashBase64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP);
            String saltBase64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP);
            
            prefs.edit()
                 .putString(KEY_PIN_HASH, "v2:" + hashBase64)
                 .putString(KEY_PIN_SALT, saltBase64)
                 .apply();
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash PIN", e);
        }
    }

    public boolean verifyPin(String pin) {
        String storedHash = prefs.getString(KEY_PIN_HASH, null);
        if (storedHash == null) return false;

        if (storedHash.startsWith("v2:")) {
            try {
                String storedSalt = prefs.getString(KEY_PIN_SALT, "");
                byte[] salt = android.util.Base64.decode(storedSalt, android.util.Base64.NO_WRAP);
                
                javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                        pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH);
                javax.crypto.SecretKeyFactory skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                byte[] testHash = skf.generateSecret(spec).getEncoded();
                
                String testHashBase64 = android.util.Base64.encodeToString(testHash, android.util.Base64.NO_WRAP);
                return storedHash.substring(3).equals(testHashBase64);
            } catch (Exception e) {
                return false;
            }
        } else {
            // F17: Legacy SHA-256 fallback (will auto-upgrade in LockActivity if successful)
            String enteredHash = toHex(com.m3r.wallet.core.crypto.Hash.SHA_256(pin));
            return storedHash.equals(enteredHash);
        }
    }

    public boolean isLegacyPinHash() {
        String storedHash = prefs.getString(KEY_PIN_HASH, null);
        return storedHash != null && !storedHash.startsWith("v2:");
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public boolean hasPinSet() {
        return prefs.getString(KEY_PIN_HASH, null) != null;
    }

    public void saveBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }

    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    // ---- Data classes ----

    public static class StoredWallet {
        public String addressBase58;
        public String privateKeyHex;
        public String publicKeyHex;
        public String payload20Hex;
        public String mnemonic; // null if no mnemonic (random key)
        public long createdAt;
        public String label;

        public StoredWallet() {
        }

        public StoredWallet(String addressBase58, String privateKeyHex, String publicKeyHex,
                String payload20Hex, String mnemonic, String label) {
            this.addressBase58 = addressBase58;
            this.privateKeyHex = privateKeyHex;
            this.publicKeyHex = publicKeyHex;
            this.payload20Hex = payload20Hex;
            this.mnemonic = mnemonic;
            this.createdAt = System.currentTimeMillis();
            this.label = label != null ? label : "My Wallet";
        }
    }

    public static class TxRecord {
        public static final String TYPE_SEND                   = "SEND";
        public static final String TYPE_RECEIVE                = "RECEIVE";
        // Buyer-initiated escrow (this wallet created the escrow)
        public static final String TYPE_ESCROW_CREATE          = "ESCROW_CREATE";
        // Seller's view: an escrow was opened naming this wallet as seller
        public static final String TYPE_ESCROW_RECEIVE         = "ESCROW_RECEIVE";
        // Arbiter's view: this wallet was designated as arbiter
        public static final String TYPE_ESCROW_ARBITER         = "ESCROW_ARBITER";
        // Buyer/arbiter released escrow → this wallet (buyer/arbiter) recorded the action
        public static final String TYPE_ESCROW_RELEASE         = "ESCROW_RELEASE";
        // Seller's view: escrow was released and funds arrived in this wallet
        public static final String TYPE_ESCROW_RELEASE_RECEIVED = "ESCROW_RELEASE_RECEIVED";
        // Seller/arbiter refunded escrow → this wallet recorded the action
        public static final String TYPE_ESCROW_REFUND          = "ESCROW_REFUND";
        // Buyer's view: escrow was refunded and funds arrived back in this wallet
        public static final String TYPE_ESCROW_REFUND_RECEIVED = "ESCROW_REFUND_RECEIVED";

        public String txHash;
        public String type; // TYPE_* constants
        public long amount;
        public long fee;
        public String toAddress;
        public String fromAddress;
        public String memo;
        public long timestamp; // unix ms
        public String status; // PENDING / CONFIRMED / REJECTED
        public String escrowId;
        public long escrowExpiry;

        public TxRecord() {
        }

        public TxRecord(String txHash, String type, long amount, long fee,
                String toAddress, String fromAddress, String memo, String status) {
            this.txHash = txHash;
            this.type = type;
            this.amount = amount;
            this.fee = fee;
            this.toAddress = toAddress;
            this.fromAddress = fromAddress;
            this.memo = memo;
            this.timestamp = System.currentTimeMillis();
            this.status = status;
        }
    }
}
