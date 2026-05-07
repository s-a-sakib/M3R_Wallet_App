package com.m3r.wallet.core.network;

import com.m3r.wallet.core.crypto.M3RAddressFactory;
import com.m3r.wallet.core.transaction.TxBuilder;
import com.m3r.wallet.core.transaction.TxSchema;
import com.m3r.wallet.core.transaction.TxV1;
import com.m3r.wallet.core.crypto.Hash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class WalletNetwork {

    public static final class Config {
        public final String baseUrl;
        public final int connectMs;
        public final int readMs;

        public Config(String baseUrl, int connectMs, int readMs) {
            this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
            this.connectMs = connectMs;
            this.readMs = readMs;
        }
    }

    public enum SubmitStatus { ACCEPTED, REJECTED }
    public enum TxState { PENDING, CONFIRMED, REJECTED, UNKNOWN }

    public static final class FeePolicy {
        public final long broadcastFee;
        public final int percentFeeBps;
        public FeePolicy(long broadcastFee, int percentFeeBps) {
            this.broadcastFee = broadcastFee;
            this.percentFeeBps = percentFeeBps;
        }
        public long feeForAmount(long amount) {
            return broadcastFee + (amount * percentFeeBps) / 10_000L;
        }
    }

    public static final class AccountInfo {
        public final long balance;
        public final long nonce;
        public AccountInfo(long balance, long nonce) {
            this.balance = balance; this.nonce = nonce;
        }
    }

    public static final class SubmitResult {
        public final SubmitStatus status;
        public final String txHash;
        public final String message;
        public SubmitResult(SubmitStatus status, String txHash, String message) {
            this.status = status; this.txHash = txHash; this.message = message;
        }
        public boolean isAccepted() { return status == SubmitStatus.ACCEPTED; }
    }

    public static final class TxStatusResult {
        public final TxState state;
        public final String message;
        public TxStatusResult(TxState state, String message) {
            this.state = state; this.message = message;
        }
    }

    public static final class ArbiterResult {
        public final boolean ok;
        public final String arbiterAddress;
        public final String message;
        public ArbiterResult(boolean ok, String arbiterAddress, String message) {
            this.ok = ok; this.arbiterAddress = arbiterAddress; this.message = message;
        }
    }

    /**
     * One entry from the server-side participant ledger.
     * Mirrors the LedgerEntryDto returned by GET /{network}/tx/history.
     */
    public static final class LedgerEntry {
        public final String txHash;
        public final String type;      // SEND, RECEIVE, ESCROW_CREATE, ESCROW_RECEIVE, ESCROW_ARBITER, …
        public final long   amount;    // in currency base units (poisha)
        public final long   fee;
        public final String fromAddr;  // hex-20
        public final String toAddr;    // hex-20
        public final String escrowId;  // hex-64, or null
        public final String status;
        public final long   createdAt; // unix ms

        public LedgerEntry(String txHash, String type, long amount, long fee,
                           String fromAddr, String toAddr, String escrowId,
                           String status, long createdAt) {
            this.txHash    = txHash;
            this.type      = type;
            this.amount    = amount;
            this.fee       = fee;
            this.fromAddr  = fromAddr;
            this.toAddr    = toAddr;
            this.escrowId  = escrowId;
            this.status    = status;
            this.createdAt = createdAt;
        }
    }

    // ---- Fields ----
    private final Config cfg;
    private final TxSchema.ChainID chainId;

    public WalletNetwork(Config cfg, TxSchema.ChainID chainId) {
        this.cfg = cfg;
        this.chainId = chainId;
    }

    public String getBaseUrl() { return cfg.baseUrl; }
    public TxSchema.ChainID getChainId() { return chainId; }

    // ---- Core API ----

    public FeePolicy getFeePolicy() throws IOException {
        String json = httpGet(cfg.baseUrl + "/fee");
        Long fee = Json.getLong(json, "broadcastFee");
        Long bps = Json.getLong(json, "percentFeeBps");
        if (fee == null || bps == null) throw new IOException("Bad /fee response");
        return new FeePolicy(fee, bps.intValue());
    }

    public AccountInfo getAccount(byte[] addr20, String base58) throws IOException {
        String hex = toHex(addr20);
        IOException last = null;

        for (String query : new String[]{ "/account?addr=" + hex, "/account?addr=0x" + hex }) {
            try { return parseAccount(httpGet(cfg.baseUrl + query)); }
            catch (IOException e) { last = e; }
        }
        if (base58 != null && !base58.isBlank()) {
            try {
                return parseAccount(httpGet(cfg.baseUrl + "/account?address=" +
                        URLEncoder.encode(base58, StandardCharsets.UTF_8)));
            } catch (IOException e) { last = e; }
        }
        throw new IOException("Cannot fetch account: " + (last != null ? last.getMessage() : "?"), last);
    }

    public SubmitResult submitTx(TxV1 tx, byte[] pubKeyCompressed) throws IOException {
        String body = "{\"rawTxHex\":\"" + toHex(tx.encodeFull()) + "\","
                + "\"pubKeyCompressedHex\":\"" + toHex(pubKeyCompressed) + "\"}";
        String resp;
        try {
            resp = httpPost(cfg.baseUrl + "/tx/submit", body);
        } catch (IOException e) {
            // F16: Extract the body from the custom exception format if it was a 4xx
            String exMsg = e.getMessage();
            if (exMsg != null && exMsg.startsWith("HTTP ")) {
                int colonIdx = exMsg.indexOf(": ");
                if (colonIdx != -1) {
                    resp = exMsg.substring(colonIdx + 2);
                } else {
                    resp = "{\"status\":\"REJECTED\",\"message\":\"" + Json.escape(exMsg) + "\"}";
                }
            } else {
                throw e;
            }
        }
        
        String st = Json.getString(resp, "status");
        String hash = Json.getString(resp, "txHash");
        String msg = Json.getString(resp, "message");
        if (msg == null) msg = resp; // fallback to raw string if not JSON

        SubmitStatus status = "ACCEPTED".equalsIgnoreCase(st) ? SubmitStatus.ACCEPTED : SubmitStatus.REJECTED;
        return new SubmitResult(status, hash, msg);
    }

    public TxStatusResult getTxStatus(String txHashHex) throws IOException {
        String resp = httpGet(cfg.baseUrl + "/tx/status?hash=" +
                URLEncoder.encode(txHashHex, StandardCharsets.UTF_8));
        String st = Json.getString(resp, "status");
        String msg = Json.getString(resp, "message");
        TxState state;
        if (st == null) state = TxState.UNKNOWN;
        else switch (st.toUpperCase()) {
            case "PENDING":   state = TxState.PENDING;   break;
            case "CONFIRMED": state = TxState.CONFIRMED; break;
            case "REJECTED":  state = TxState.REJECTED;  break;
            default:          state = TxState.UNKNOWN;
        }
        return new TxStatusResult(state, msg);
    }

    // ---- High-level send helpers ----

    public SubmitResult sendTransfer(M3RAddressFactory.WalletKey wallet,
                                     byte[] toAddr20, long amount, long fee, long nonce,
                                     byte[] memo) throws IOException {
        TxV1 tx = TxBuilder.transfer(chainId, nonce, fee, wallet.payload20, toAddr20, amount, memo);
        tx.signSecp256k1(wallet.privateKey);
        return submitTx(tx, wallet.publicKeyCompressed);
    }

    public SubmitResult sendEscrowCreate(M3RAddressFactory.WalletKey wallet,
                                          byte[] escrowId32, byte[] buyer20, byte[] seller20,
                                          byte[] arbiter20, long amount, long expiryTs,
                                          int releaseMode, int disputeMode, byte[] metaHash32,
                                          long fee, long nonce, byte[] memo) throws IOException {
        TxV1 tx = TxBuilder.escrowCreate(chainId, nonce, fee, wallet.payload20,
                escrowId32, buyer20, seller20, arbiter20, amount, expiryTs,
                releaseMode, disputeMode, metaHash32, memo);
        tx.signSecp256k1(wallet.privateKey);
        return submitTx(tx, wallet.publicKeyCompressed);
    }

    public SubmitResult sendEscrowRelease(M3RAddressFactory.WalletKey wallet,
                                           byte[] escrowId32, byte[] toAddr20,
                                           long amount, long fee, long nonce, byte[] memo) throws IOException {
        TxV1 tx = TxBuilder.escrowRelease(chainId, nonce, fee, wallet.payload20,
                escrowId32, toAddr20, amount, memo);
        tx.signSecp256k1(wallet.privateKey);
        return submitTx(tx, wallet.publicKeyCompressed);
    }

    public SubmitResult sendEscrowRefund(M3RAddressFactory.WalletKey wallet,
                                          byte[] escrowId32, byte[] toAddr20,
                                          long amount, long fee, long nonce, byte[] memo) throws IOException {
        TxV1 tx = TxBuilder.escrowRefund(chainId, nonce, fee, wallet.payload20,
                escrowId32, toAddr20, amount, memo);
        tx.signSecp256k1(wallet.privateKey);
        return submitTx(tx, wallet.publicKeyCompressed);
    }

    public ArbiterResult requestArbiter(String buyerBase58, String sellerBase58,
                                         String type, String memo) throws IOException {
        String body = "{\"buyer\":\"" + Json.escape(buyerBase58) + "\","
                + "\"seller\":\"" + Json.escape(sellerBase58) + "\","
                + "\"type\":\"" + Json.escape(type) + "\","
                + "\"memo\":\"" + Json.escape(memo == null ? "" : memo) + "\"}";
        String resp = httpPost(cfg.baseUrl + "/arbiter/request", body);
        String st = Json.getString(resp, "status");
        String arb = Json.getString(resp, "arbiterAddress");
        String msg = Json.getString(resp, "message");
        boolean ok = "OK".equalsIgnoreCase(st) || "ACCEPTED".equalsIgnoreCase(st);
        return new ArbiterResult(ok, arb, msg);
    }

    /**
     * Fetch the full ledger history for the given hex-20 address from the server.
     * Calls GET /tx/history?addr=&lt;hex20&gt; and parses the JSON array.
     *
     * @param addr20 raw 20-byte address of the wallet
     * @return list of ledger entries ordered newest-first (empty if none)
     * @throws IOException on network failure
     */
    public java.util.List<LedgerEntry> getHistory(byte[] addr20) throws IOException {
        String hex = toHex(addr20);
        String resp = httpGet(cfg.baseUrl + "/tx/history?addr=" + hex);
        return parseHistory(resp);
    }

    // ---- Private: parse /tx/history response ----

    private java.util.List<LedgerEntry> parseHistory(String json) {
        java.util.List<LedgerEntry> result = new java.util.ArrayList<>();
        if (json == null || !json.contains("\"entries\"")) return result;

        // Locate the entries array
        int arrStart = json.indexOf('[', json.indexOf("\"entries\""));
        int arrEnd   = json.lastIndexOf(']');
        if (arrStart < 0 || arrEnd < arrStart) return result;

        // Split on objects — simple brace matching, no library needed
        String arrBody = json.substring(arrStart + 1, arrEnd);
        int depth = 0;
        int objStart = -1;
        for (int i = 0; i < arrBody.length(); i++) {
            char c = arrBody.charAt(i);
            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = arrBody.substring(objStart, i + 1);
                    LedgerEntry e = parseLedgerEntry(obj);
                    if (e != null) result.add(e);
                    objStart = -1;
                }
            }
        }
        return result;
    }

    private LedgerEntry parseLedgerEntry(String obj) {
        try {
            String txHash   = Json.getString(obj, "txHash");
            String type     = Json.getString(obj, "type");
            String amtStr   = Json.getString(obj, "amount");
            String feeStr   = Json.getString(obj, "fee");
            String fromAddr = Json.getString(obj, "fromAddr");
            String toAddr   = Json.getString(obj, "toAddr");
            String escrowId = Json.getString(obj, "escrowId");
            String status   = Json.getString(obj, "status");
            Long   created  = Json.getLong(obj, "createdAt");

            long amount = amtStr != null ? Long.parseLong(amtStr) : 0L;
            long fee    = feeStr != null ? Long.parseLong(feeStr)  : 0L;

            return new LedgerEntry(
                    txHash, type, amount, fee,
                    fromAddr, toAddr, escrowId,
                    status, created != null ? created : 0L);
        } catch (Exception e) {
            return null; // skip malformed entries
        }
    }

    // ---- HTTP helpers ----

    private AccountInfo parseAccount(String json) throws IOException {
        Long bal = Json.getLong(json, "balance");
        Long nonce = Json.getLong(json, "nonce");
        if (bal == null || nonce == null) throw new IOException("Bad /account response: " + json);
        return new AccountInfo(bal, nonce);
    }

    private String httpGet(String urlStr) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setConnectTimeout(cfg.connectMs);
        con.setReadTimeout(cfg.readMs);
        con.setRequestMethod("GET");
        return readResponse(con);
    }

    private String httpPost(String urlStr, String json) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(urlStr).openConnection();
        con.setConnectTimeout(cfg.connectMs);
        con.setReadTimeout(cfg.readMs);
        con.setRequestMethod("POST");
        con.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        con.setDoOutput(true);
        try (OutputStream os = con.getOutputStream()) {
            os.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(con);
    }

    private String readResponse(HttpURLConnection con) throws IOException {
        int code = con.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? con.getInputStream() : con.getErrorStream();
        String body = "";
        if (is != null) {
            body = readStream(is);
        }
        // F16: Throw on 4xx and 5xx so the error body is propagated
        if (code < 200 || code >= 400) throw new IOException("HTTP " + code + ": " + body);
        return body;
    }

    private static String readStream(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toString("UTF-8");
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- Tiny JSON parser ----
    static final class Json {
        static Long getLong(String json, String key) {
            if (json == null) return null;
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return null;
            int start = colon + 1;
            while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
            int end = start;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
            if (end <= start) return null;
            try { return Long.parseLong(json.substring(start, end)); } catch (Exception e) { return null; }
        }

        static String getString(String json, String key) {
            if (json == null) return null;
            String needle = "\"" + key + "\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int colon = json.indexOf(':', i + needle.length());
            if (colon < 0) return null;
            int q1 = json.indexOf('"', colon + 1);
            if (q1 < 0) return null;
            int q2 = json.indexOf('"', q1 + 1);
            if (q2 < 0) return null;
            return json.substring(q1 + 1, q2);
        }

        static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
