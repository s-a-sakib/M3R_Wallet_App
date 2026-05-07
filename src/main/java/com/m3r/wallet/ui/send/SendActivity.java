package com.m3r.wallet.ui.send;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import com.m3r.wallet.R;
import com.m3r.wallet.core.crypto.M3RAddressFactory;
import com.m3r.wallet.core.network.WalletNetwork;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;

import java.nio.charset.StandardCharsets;

public class SendActivity extends AppCompatActivity {

    private static final String TAG = "SendActivity";

    private EditText etToAddress, etAmount, etMemo;
    private TextView tvFeeEstimate;
    private Button btnSend, btnScanQr;
    private ProgressBar progressBar;
    private WalletRepository repo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_send);

        repo = WalletRepository.get(this);

        etToAddress = findViewById(R.id.etToAddress);
        etAmount    = findViewById(R.id.etAmount);
        etMemo      = findViewById(R.id.etMemo);
        tvFeeEstimate = findViewById(R.id.tvFeeEstimate);
        btnSend     = findViewById(R.id.btnSend);
        btnScanQr   = findViewById(R.id.btnScanQr);
        progressBar = findViewById(R.id.progressBar);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Send BDT");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        etAmount.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) estimateFee();
        });

        btnScanQr.setOnClickListener(v -> {
            IntentIntegrator integrator = new IntentIntegrator(this);
            integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
            integrator.setPrompt("Scan M3R address QR code");
            integrator.setCameraId(0);
            integrator.setBeepEnabled(true);
            integrator.setOrientationLocked(true);
            integrator.initiateScan();
        });

        btnSend.setOnClickListener(v -> sendTransaction());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            etToAddress.setText(result.getContents());
            estimateFee();
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void estimateFee() {
        String amtStr = etAmount.getText().toString().trim();
        if (amtStr.isEmpty()) return;
        try {
            double bdtAmount = Double.parseDouble(amtStr);
            long amountPoisha = (long) (bdtAmount * 100);
            repo.getExecutor().submit(() -> {
                try {
                    WalletNetwork.FeePolicy fp = repo.getNetwork().getFeePolicy();
                    long fee = fp.feeForAmount(amountPoisha);
                    runOnUiThread(() -> tvFeeEstimate.setText("Estimated fee: " + fee + " Poisha (~" + (fee / 100.0) + " BDT)"));
                } catch (Exception e) {
                    runOnUiThread(() -> tvFeeEstimate.setText("Fee: unknown (node offline)"));
                }
            });
        } catch (NumberFormatException ignored) {}
    }

    private void sendTransaction() {
        String toAddr = etToAddress.getText().toString().trim();
        String amtStr = etAmount.getText().toString().trim();
        String memo   = etMemo.getText().toString().trim();

        if (toAddr.isEmpty()) { toast("Enter recipient address"); return; }
        if (amtStr.isEmpty()) { toast("Enter amount"); return; }

        long amountPoisha;
        try {
            double bdtAmount = Double.parseDouble(amtStr);
            amountPoisha = (long) (bdtAmount * 100);
        } catch (NumberFormatException e) {
            toast("Invalid amount");
            return;
        }
        if (amountPoisha <= 0) { toast("Amount must be > 0"); return; }

        // ---- Validate recipient address BEFORE background thread ----
        byte[] toAddr20;
        try {
            toAddr20 = M3RAddressFactory.payload20FromAddress(toAddr);
        } catch (Exception e) {
            Log.e(TAG, "Address validation failed: " + e.getMessage(), e);
            showError("Invalid Recipient Address",
                    "The address you entered is not a valid M3R address.\n\n" +
                    "Detail: " + e.getMessage() + "\n\n" +
                    "Tip: Use QR scan or copy-paste from the Receive screen.");
            return;
        }

        setLoading(true);

        final long finalAmount = amountPoisha;
        final byte[] finalToAddr20 = toAddr20;

        repo.getExecutor().submit(() -> {
            try {
                M3RAddressFactory.WalletKey key = repo.loadWalletKey();
                if (key == null) throw new Exception("Wallet key not found. Please re-import your wallet.");

                WalletNetwork net = repo.getNetwork();
                Log.d(TAG, "Sending via: " + net.getBaseUrl());

                WalletNetwork.AccountInfo info = net.getAccount(key.payload20, key.addressBase58);
                Log.d(TAG, "Balance: " + info.balance + " Nonce: " + info.nonce);

                WalletNetwork.FeePolicy fp = net.getFeePolicy();
                long fee = fp.feeForAmount(finalAmount);
                long nonce = info.nonce + 1;
                Log.d(TAG, "Sending amount=" + finalAmount + " fee=" + fee + " nonce=" + nonce);

                byte[] memoBytes = memo.isEmpty() ? new byte[0] : memo.getBytes(StandardCharsets.UTF_8);
                WalletNetwork.SubmitResult result = net.sendTransfer(key, finalToAddr20, finalAmount, fee, nonce, memoBytes);
                Log.d(TAG, "Submit result: " + result.status + " hash=" + result.txHash + " msg=" + result.message);

                // Always record tx to local history
                repo.recordTx(new WalletStorage.TxRecord(
                        result.txHash, WalletStorage.TxRecord.TYPE_SEND,
                        finalAmount, fee, toAddr, key.addressBase58, memo,
                        result.isAccepted() ? "PENDING" : "REJECTED"));

                runOnUiThread(() -> {
                    setLoading(false);
                    if (result.isAccepted()) {
                        new AlertDialog.Builder(SendActivity.this, R.style.DialogTheme)
                                .setTitle("✅ Transaction Sent!")
                                .setMessage("Amount: " + (finalAmount / 100.0) + " BDT\n"
                                        + "Fee: " + (fee / 100.0) + " BDT\n\n"
                                        + "Tx Hash:\n" + result.txHash)
                                .setPositiveButton("Back to Home", (dialog, which) -> finish())
                                .setCancelable(false)
                                .show();
                    } else {
                        showError("Transaction Rejected", "Server rejected the transaction:\n\n" + result.message);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "Send failed: " + e.getMessage(), e);
                runOnUiThread(() -> {
                    setLoading(false);
                    showError("Send Failed", e.getMessage() != null ? e.getMessage() : "Unknown error occurred.\nCheck your internet connection and node URL in Settings.");
                });
            }
        });
    }

    private void showError(String title, String message) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("❌ " + title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show();
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSend.setEnabled(!loading);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.fade_in, R.anim.slide_out_left);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
