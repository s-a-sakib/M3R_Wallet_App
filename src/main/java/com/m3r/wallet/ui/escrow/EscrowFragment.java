package com.m3r.wallet.ui.escrow;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.m3r.wallet.R;
import com.m3r.wallet.core.crypto.Hash;
import com.m3r.wallet.core.crypto.M3RAddressFactory;
import com.m3r.wallet.core.network.WalletNetwork;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;

import java.nio.charset.StandardCharsets;

public class EscrowFragment extends Fragment {

    private WalletRepository repo;
    private ProgressBar progressBar;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_escrow, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repo = WalletRepository.get(requireContext());

        progressBar = view.findViewById(R.id.escrowProgress);

        view.findViewById(R.id.btnCreateEscrow).setOnClickListener(v -> showCreateEscrowDialog());
        view.findViewById(R.id.btnReleaseEscrow).setOnClickListener(v -> showReleaseDialog());
        view.findViewById(R.id.btnRefundEscrow).setOnClickListener(v -> showRefundDialog());
        view.findViewById(R.id.btnRequestArbiter).setOnClickListener(v -> showArbiterDialog());
    }

    // ---- Create Escrow ----
    private void showCreateEscrowDialog() {
        View dv = getLayoutInflater().inflate(R.layout.dialog_escrow_create, null);
        EditText etSeller = dv.findViewById(R.id.etSellerAddress);
        EditText etArbiter = dv.findViewById(R.id.etArbiterAddress);
        EditText etAmount = dv.findViewById(R.id.etEscrowAmount);
        EditText etHours = dv.findViewById(R.id.etExpiryHours);
        EditText etMeta = dv.findViewById(R.id.etMetaInfo);

        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Create Escrow")
                .setView(dv)
                .setPositiveButton("Create", (d, w) -> {
                    String seller = etSeller.getText().toString().trim();
                    String arbiter = etArbiter.getText().toString().trim();
                    String amtStr = etAmount.getText().toString().trim();
                    String hrStr = etHours.getText().toString().trim();
                    String meta = etMeta.getText().toString().trim();

                    if (seller.isEmpty() || amtStr.isEmpty()) {
                        toast("Fill seller and amount");
                        return;
                    }
                    try {
                        double bdtAmount = Double.parseDouble(amtStr);
                        long amountPoisha = (long) (bdtAmount * 100);
                        long hours = hrStr.isEmpty() ? 24 : Long.parseLong(hrStr);
                        long expiry = (System.currentTimeMillis() / 1000L) + hours * 3600L;
                        createEscrow(seller, arbiter, amountPoisha, expiry, meta);
                    } catch (NumberFormatException e) {
                        toast("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createEscrow(String sellerBase58, String arbiterBase58,
            long amount, long expiryTs, String metaInfo) {
        setLoading(true);

        repo.getExecutor().submit(() -> {
            String escrowIdHex = null;
            WalletStorage.TxRecord rec = null;
            try {
                M3RAddressFactory.WalletKey key = repo.loadWalletKey();
                WalletNetwork net = repo.getNetwork();

                WalletNetwork.AccountInfo info = net.getAccount(key.payload20, key.addressBase58);
                WalletNetwork.FeePolicy fp = net.getFeePolicy();
                long fee = fp.feeForAmount(amount);
                long nonce = info.nonce + 1;

                byte[] seller20 = M3RAddressFactory.payload20FromAddress(sellerBase58);
                byte[] arbiter20 = arbiterBase58.isEmpty() ? new byte[20] : M3RAddressFactory.payload20FromAddress(arbiterBase58);

                // escrowId = keccak256(buyer|seller|timestamp)
                String escrowSeed = key.addressBase58 + "|" + sellerBase58 + "|" + (System.currentTimeMillis() / 1000L);
                byte[] escrowId32 = Hash.KECCAK_256(escrowSeed.getBytes(StandardCharsets.UTF_8));
                escrowIdHex = toHex(escrowId32);

                String metaStr = metaInfo.isEmpty() ? "escrow" : metaInfo;
                byte[] metaHash32 = Hash.KECCAK_256(metaStr.getBytes(StandardCharsets.UTF_8));

                WalletNetwork.SubmitResult result = net.sendEscrowCreate(
                        key, escrowId32,
                        key.payload20, seller20, arbiter20,
                        amount, expiryTs,
                        1, 1, metaHash32,
                        fee, nonce,
                        "escrow-create".getBytes(StandardCharsets.UTF_8));

                rec = new WalletStorage.TxRecord(
                        result.txHash, WalletStorage.TxRecord.TYPE_ESCROW_CREATE,
                        amount, fee, sellerBase58, key.addressBase58, metaInfo,
                        result.isAccepted() ? "PENDING" : "REJECTED");
                rec.escrowId = escrowIdHex;
                rec.escrowExpiry = expiryTs;
                repo.recordTx(rec);

                final boolean accepted = result.isAccepted();
                final String txHash = result.txHash;
                final String msg = result.message;
                final String finalEscrowId = escrowIdHex;

                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    if (accepted) {
                        // Success popup with option to copy Escrow ID
                        String detail = "Amount: " + String.format(java.util.Locale.US, "%.2f BDT", amount / 100.0)
                                + "\nSeller: " + sellerBase58
                                + "\nEscrow ID:\n" + finalEscrowId
                                + "\nTx Hash:\n" + txHash;
                        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                                .setTitle("✅ Escrow Created!")
                                .setMessage(detail)
                                .setPositiveButton("Close", null)
                                .setNeutralButton("Copy Escrow ID", (d, w) -> {
                                    ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                                    cb.setPrimaryClip(ClipData.newPlainText("Escrow ID", finalEscrowId));
                                    Toast.makeText(requireContext(), "Escrow ID copied!", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                        TextView tvMsg = dialog.findViewById(android.R.id.message);
                        if (tvMsg != null) tvMsg.setTextIsSelectable(true);
                    } else {
                        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                                .setTitle("❌ Escrow Failed")
                                .setMessage(msg)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });

            } catch (Exception e) {
                // Always save a FAILED record so history is not lost
                if (escrowIdHex != null && rec == null) {
                    try {
                        WalletStorage.TxRecord failRec = new WalletStorage.TxRecord(
                                null, WalletStorage.TxRecord.TYPE_ESCROW_CREATE,
                                amount, 0, sellerBase58, "", metaInfo, "FAILED");
                        failRec.escrowId = escrowIdHex;
                        repo.recordTx(failRec);
                    } catch (Exception ignore) {}
                }
                if (getActivity() == null) return;
                final String errMsg = e.getMessage();
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                            .setTitle("❌ Escrow Error")
                            .setMessage(errMsg)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    // ---- Release Escrow ----
    private void showReleaseDialog() {
        View dv = getLayoutInflater().inflate(R.layout.dialog_escrow_action, null);
        ((TextView) dv.findViewById(R.id.tvEscrowActionTitle)).setText("Release Escrow");
        EditText etEscrowId = dv.findViewById(R.id.etEscrowId);
        EditText etToAddr = dv.findViewById(R.id.etActionToAddress);
        EditText etAmount = dv.findViewById(R.id.etActionAmount);

        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Release Escrow to Seller")
                .setView(dv)
                .setPositiveButton("Release", (d, w) -> {
                    String escrowIdHex = etEscrowId.getText().toString().trim();
                    String toAddr = etToAddr.getText().toString().trim();
                    String amtStr = etAmount.getText().toString().trim();
                    if (escrowIdHex.isEmpty() || toAddr.isEmpty() || amtStr.isEmpty()) {
                        toast("Fill all fields");
                        return;
                    }
                    try {
                        byte[] escrowId32 = fromHex(escrowIdHex);
                        double bdtAmount = Double.parseDouble(amtStr);
                        long amountPoisha = (long) (bdtAmount * 100);
                        doEscrowAction("release", escrowId32, toAddr, amountPoisha);
                    } catch (Exception e) {
                        toast("Invalid input: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ---- Refund Escrow ----
    private void showRefundDialog() {
        View dv = getLayoutInflater().inflate(R.layout.dialog_escrow_action, null);
        ((TextView) dv.findViewById(R.id.tvEscrowActionTitle)).setText("Refund Escrow");
        EditText etEscrowId = dv.findViewById(R.id.etEscrowId);
        EditText etToAddr = dv.findViewById(R.id.etActionToAddress);
        EditText etAmount = dv.findViewById(R.id.etActionAmount);

        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Refund Escrow to Buyer")
                .setView(dv)
                .setPositiveButton("Refund", (d, w) -> {
                    String escrowIdHex = etEscrowId.getText().toString().trim();
                    String toAddr = etToAddr.getText().toString().trim();
                    String amtStr = etAmount.getText().toString().trim();
                    if (escrowIdHex.isEmpty() || toAddr.isEmpty() || amtStr.isEmpty()) {
                        toast("Fill all fields");
                        return;
                    }
                    try {
                        byte[] escrowId32 = fromHex(escrowIdHex);
                        double bdtAmount = Double.parseDouble(amtStr);
                        long amountPoisha = (long) (bdtAmount * 100);
                        doEscrowAction("refund", escrowId32, toAddr, amountPoisha);
                    } catch (Exception e) {
                        toast("Invalid input: " + e.getMessage());
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void doEscrowAction(String action, byte[] escrowId32, String toAddrBase58, long amount) {
        setLoading(true);

        repo.getExecutor().submit(() -> {
            String txType = "release".equals(action)
                    ? WalletStorage.TxRecord.TYPE_ESCROW_RELEASE
                    : WalletStorage.TxRecord.TYPE_ESCROW_REFUND;
            String actionLabel = "release".equals(action) ? "Released" : "Refunded";
            try {
                M3RAddressFactory.WalletKey key = repo.loadWalletKey();
                WalletNetwork net = repo.getNetwork();
                WalletNetwork.AccountInfo info = net.getAccount(key.payload20, key.addressBase58);
                WalletNetwork.FeePolicy fp = net.getFeePolicy();
                long fee = fp.feeForAmount(amount);
                long nonce = info.nonce + 1;
                byte[] toAddr20 = M3RAddressFactory.payload20FromAddress(toAddrBase58);

                WalletNetwork.SubmitResult result;
                if ("release".equals(action)) {
                    result = net.sendEscrowRelease(key, escrowId32, toAddr20, amount, fee, nonce, null);
                } else {
                    result = net.sendEscrowRefund(key, escrowId32, toAddr20, amount, fee, nonce, null);
                }

                repo.recordTx(new WalletStorage.TxRecord(
                        result.txHash, txType, amount, fee,
                        toAddrBase58, key.addressBase58, action,
                        result.isAccepted() ? "PENDING" : "REJECTED"));

                final boolean accepted = result.isAccepted();
                final String txHash = result.txHash;
                final String msg = result.message;

                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    if (accepted) {
                        String detail = "Escrow " + actionLabel + " successfully!"
                                + "\nAmount: " + String.format(java.util.Locale.US, "%.2f BDT", amount / 100.0)
                                + "\nTo: " + toAddrBase58
                                + "\nTx Hash:\n" + txHash;
                        AlertDialog dialog = new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                                .setTitle("✅ Escrow " + actionLabel + "!")
                                .setMessage(detail)
                                .setPositiveButton("Close", null)
                                .setNeutralButton("Copy Hash", (d, w) -> {
                                    ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                                    cb.setPrimaryClip(ClipData.newPlainText("Tx Hash", txHash));
                                    Toast.makeText(requireContext(), "Tx Hash copied!", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                        TextView tvMsg = dialog.findViewById(android.R.id.message);
                        if (tvMsg != null) tvMsg.setTextIsSelectable(true);
                    } else {
                        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                                .setTitle("❌ Escrow " + actionLabel + " Failed")
                                .setMessage(msg)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                // Save a FAILED record so the attempt appears in history
                try {
                    repo.recordTx(new WalletStorage.TxRecord(
                            null, txType, amount, 0,
                            toAddrBase58, "", action, "FAILED"));
                } catch (Exception ignore) {}
                if (getActivity() == null) return;
                final String errMsg = e.getMessage();
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                            .setTitle("❌ Escrow Error")
                            .setMessage(errMsg)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    // ---- Arbiter ----
    private void showArbiterDialog() {
        View dv = getLayoutInflater().inflate(R.layout.dialog_arbiter, null);
        EditText etSeller = dv.findViewById(R.id.etArbiterSeller);
        EditText etMemo = dv.findViewById(R.id.etArbiterMemo);

        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                .setTitle("Request Arbiter")
                .setView(dv)
                .setPositiveButton("Request", (d, w) -> {
                    String seller = etSeller.getText().toString().trim();
                    String memo = etMemo.getText().toString().trim();
                    if (seller.isEmpty()) {
                        toast("Enter seller address");
                        return;
                    }
                    requestArbiter(seller, memo);
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void requestArbiter(String sellerBase58, String memo) {
        setLoading(true);

        repo.getExecutor().submit(() -> {
            try {
                M3RAddressFactory.WalletKey key = repo.loadWalletKey();
                WalletNetwork.ArbiterResult result = repo.getNetwork()
                        .requestArbiter(key.addressBase58, sellerBase58, "TIME_LOCK", memo);

                final boolean ok = result.ok;
                final String arbAddr = result.arbiterAddress;
                final String msg = result.message;

                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    if (ok) {
                        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                                .setTitle("✅ Arbiter Assigned!")
                                .setMessage("Arbiter Address:\n" + arbAddr)
                                .setPositiveButton("Close", null)
                                .setNeutralButton("Copy", (d, w) -> {
                                    ClipboardManager cb = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                                    cb.setPrimaryClip(ClipData.newPlainText("Arbiter", arbAddr));
                                    Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                    } else {
                        new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                                .setTitle("❌ Arbiter Request Failed")
                                .setMessage(msg)
                                .setPositiveButton("OK", null)
                                .show();
                    }
                });
            } catch (Exception e) {
                if (getActivity() == null) return;
                final String errMsg = e.getMessage();
                getActivity().runOnUiThread(() -> {
                    setLoading(false);
                    new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                            .setTitle("❌ Arbiter Error")
                            .setMessage(errMsg)
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> progressBar.setVisibility(loading ? View.VISIBLE : View.GONE));
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        if (hex.startsWith("0x"))
            hex = hex.substring(2);
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            out[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        return out;
    }
}
