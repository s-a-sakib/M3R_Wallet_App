package com.m3r.wallet.data.repository;

import android.content.Context;

import com.m3r.wallet.core.crypto.M3RAddressFactory;
import com.m3r.wallet.core.network.WalletNetwork;
import com.m3r.wallet.core.transaction.TxSchema;
import com.m3r.wallet.data.local.WalletStorage;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single source of truth for all wallet operations.
 * Manages WalletNetwork and WalletStorage.
 */
public class WalletRepository {

    private static WalletRepository instance;

    private final WalletStorage storage;
    private WalletNetwork network;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public static final String DEFAULT_PROVIDER_URL = "http://10.0.2.2:3000";

    private WalletRepository(Context ctx) {
        storage = new WalletStorage(ctx.getApplicationContext());
        initNetwork();
    }

    public static synchronized WalletRepository get(Context ctx) {
        if (instance == null)
            instance = new WalletRepository(ctx);
        return instance;
    }

    private void initNetwork() {
        boolean useTestNet = isUsingTestNet();
        String providerUrl = getNodeProviderUrl();
        // Force no trailing slash on providerUrl
        if(providerUrl.endsWith("/")) providerUrl = providerUrl.substring(0, providerUrl.length() - 1);
        String fullUrl = providerUrl + (useTestNet ? "/testnet" : "/mainnet");
        
        TxSchema.ChainID chainId = storage.loadChainId();
        network = new WalletNetwork(
                new WalletNetwork.Config(fullUrl, 5000, 8000),
                chainId);
    }

    // ---- Wallet ----

    public M3RAddressFactory.WalletKey generateWallet(String mnemonic) {
        return M3RAddressFactory.generate(mnemonic);
    }

    public void saveWallet(M3RAddressFactory.WalletKey key, String label) {
        WalletStorage.StoredWallet stored = new WalletStorage.StoredWallet(
                key.addressBase58,
                key.privateKeyHex(),
                key.publicKeyHex(),
                key.payload20Hex(),
                key.mnemonic,
                label);
        storage.saveWallet(stored);
    }

    public WalletStorage.StoredWallet getStoredWallet() {
        return storage.loadWallet();
    }

    public boolean hasWallet() {
        return storage.hasWallet();
    }

    public M3RAddressFactory.WalletKey loadWalletKey() {
        WalletStorage.StoredWallet w = storage.loadWallet();
        if (w == null)
            return null;
        // Reconstruct from stored mnemonic or stored privateKey
        if (w.mnemonic != null && !w.mnemonic.isEmpty()) {
            return M3RAddressFactory.generate(w.mnemonic);
        }
        // Reconstruct from hex private key
        byte[] privKey = fromHex(w.privateKeyHex);
        byte[] pubKey = com.m3r.wallet.core.crypto.PublicKeyGenerator.compressed(privKey);
        byte[] k = com.m3r.wallet.core.crypto.Hash.KECCAK_256(pubKey);
        byte[] p20 = Arrays.copyOfRange(k, 12, 32);
        return new M3RAddressFactory.WalletKey(privKey, pubKey, w.addressBase58, k, p20,
                M3RAddressFactory.VERSION, null);
    }

    public void deleteWallet() {
        storage.deleteWallet();
        storage.clearTxHistory();
    }

    // ---- Network ----

    public WalletNetwork getNetwork() {
        return network;
    }

    public void setNodeProviderUrl(String url) {
        storage.saveNodeUrl(url);
        initNetwork();
    }

    public void setUseTestNet(boolean useTestNet) {
        storage.saveUseMockNode(useTestNet);
        initNetwork();
    }

    public boolean isUsingTestNet() {
        return storage.loadUseMockNode();
    }

    public void setChainId(TxSchema.ChainID chainId) {
        storage.saveChainId(chainId);
        initNetwork();
    }

    public TxSchema.ChainID getChainId() {
        return storage.loadChainId();
    }

    // ---- Transactions ----

    public void recordTx(WalletStorage.TxRecord record) {
        storage.saveTx(record);
    }

    public List<WalletStorage.TxRecord> getTxHistory() {
        return storage.loadTxHistory();
    }

    public void refreshPendingTransactions(Runnable onComplete) {
        executor.submit(() -> {
            // Step 1 – update status of any locally-pending transactions
            try {
                List<WalletStorage.TxRecord> list = storage.loadTxHistory();
                for (WalletStorage.TxRecord tx : list) {
                    if ("PENDING".equals(tx.status) && tx.txHash != null) {
                        try {
                            WalletNetwork.TxStatusResult result = network.getTxStatus(tx.txHash);
                            if (result.state == WalletNetwork.TxState.CONFIRMED
                                    || result.state == WalletNetwork.TxState.REJECTED) {
                                storage.updateTxStatus(tx.txHash, result.state.name());
                            }
                        } catch (Exception ignore) {
                            // Network error for this specific tx — skip silently
                        }
                    }
                }
            } catch (Exception ignore) { /* storage error — skip */ }

            // Step 2 – sync incoming entries from the server ledger
            syncIncomingHistory(onComplete);
        });
    }

    /**
     * Fetches the server-side participant ledger for this wallet's own address and
     * merges any entries not already stored locally.  Only "incoming" role types
     * are added by this path; outgoing entries are already written by the send/escrow
     * code paths at submission time.
     *
     * <p>Incoming types that are synced:</p>
     * <ul>
     *   <li>RECEIVE              – someone sent BDT to us</li>
     *   <li>ESCROW_RECEIVE       – someone opened an escrow naming us as seller</li>
     *   <li>ESCROW_ARBITER       – someone named us as arbiter in an escrow</li>
     *   <li>ESCROW_RELEASE_RECEIVED – escrow was released and funds arrived (seller)</li>
     *   <li>ESCROW_REFUND_RECEIVED  – escrow was refunded and funds arrived (buyer)</li>
     * </ul>
     *
     * <p>We also sync outgoing types (SEND, ESCROW_CREATE, etc.) so that the
     * server ledger acts as the ground truth and any locally-missing entries are
     * recovered (e.g. after clearing app data or reinstalling).</p>
     *
     * @param onComplete callback run on the executor thread after sync (may be null)
     */
    public void syncIncomingHistory(Runnable onComplete) {
        executor.submit(() -> {
            try {
                WalletStorage.StoredWallet w = storage.loadWallet();
                if (w == null || w.payload20Hex == null || w.payload20Hex.isEmpty()) {
                    return; // No wallet loaded yet
                }

                byte[] addr20 = fromHex(w.payload20Hex);
                List<WalletNetwork.LedgerEntry> serverEntries = network.getHistory(addr20);

                for (WalletNetwork.LedgerEntry entry : serverEntries) {
                    if (entry == null || entry.txHash == null || entry.type == null) continue;

                    // Skip if already present locally (same hash + same type)
                    if (storage.hasTxRecord(entry.txHash, entry.type)) continue;

                    // Map server ledger type to a WalletStorage.TxRecord type constant
                    String localType = mapLedgerType(entry.type);
                    if (localType == null) continue; // unknown type — skip

                    WalletStorage.TxRecord rec = new WalletStorage.TxRecord(
                            entry.txHash,
                            localType,
                            entry.amount,
                            entry.fee,
                            entry.toAddr,   // toAddress field
                            entry.fromAddr, // fromAddress field
                            null,           // no memo available from server
                            entry.status != null ? entry.status : "CONFIRMED"
                    );
                    rec.timestamp = entry.createdAt > 0 ? entry.createdAt : System.currentTimeMillis();

                    // Attach escrow ID if present
                    if (entry.escrowId != null && !entry.escrowId.isEmpty()) {
                        rec.escrowId = entry.escrowId;
                    }

                    storage.saveTx(rec);
                }
            } catch (Exception e) {
                // Network unavailable or server offline — sync silently fails;
                // the user can swipe-to-refresh again when connectivity is restored.
                android.util.Log.w("WalletRepository", "syncIncomingHistory failed: " + e.getMessage());
            } finally {
                if (onComplete != null) {
                    onComplete.run();
                }
            }
        });
    }

    /**
     * Maps a server ledger type string to the corresponding
     * {@link WalletStorage.TxRecord} TYPE_* constant.
     * Returns null for any unknown type (causing the entry to be skipped).
     */
    private static String mapLedgerType(String serverType) {
        if (serverType == null) return null;
        switch (serverType) {
            case "SEND":
                return WalletStorage.TxRecord.TYPE_SEND;
            case "RECEIVE":
                return WalletStorage.TxRecord.TYPE_RECEIVE;
            case "ESCROW_CREATE":
                return WalletStorage.TxRecord.TYPE_ESCROW_CREATE;
            case "ESCROW_RECEIVE":
                // Seller's view of an incoming escrow — shown as ESCROW_CREATE in UI
                // but clearly labelled by the existing "Escrow Created" label for that type.
                // We use a dedicated constant so the UI can distinguish buyer vs seller.
                return WalletStorage.TxRecord.TYPE_ESCROW_RECEIVE;
            case "ESCROW_ARBITER":
                return WalletStorage.TxRecord.TYPE_ESCROW_ARBITER;
            case "ESCROW_RELEASE":
                return WalletStorage.TxRecord.TYPE_ESCROW_RELEASE;
            case "ESCROW_RELEASE_RECEIVED":
                return WalletStorage.TxRecord.TYPE_ESCROW_RELEASE_RECEIVED;
            case "ESCROW_REFUND":
                return WalletStorage.TxRecord.TYPE_ESCROW_REFUND;
            case "ESCROW_REFUND_RECEIVED":
                return WalletStorage.TxRecord.TYPE_ESCROW_REFUND_RECEIVED;
            default:
                return null;
        }
    }

    // ---- Settings ----

    public String getNodeProviderUrl() {
        return storage.loadNodeUrl(DEFAULT_PROVIDER_URL);
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    // ---- Util ----
    private static byte[] fromHex(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
