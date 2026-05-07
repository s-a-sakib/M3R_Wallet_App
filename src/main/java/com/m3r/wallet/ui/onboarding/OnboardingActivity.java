package com.m3r.wallet.ui.onboarding;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.m3r.wallet.R;
import com.m3r.wallet.core.crypto.M3RAddressFactory;
import com.m3r.wallet.data.repository.WalletRepository;
import com.m3r.wallet.ui.lock.PinSetupActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OnboardingActivity extends AppCompatActivity {

    private WalletRepository repo;
    private ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        repo = WalletRepository.get(this);

        Button btnCreate = findViewById(R.id.btnCreateWallet);
        Button btnImport = findViewById(R.id.btnImportWallet);

        btnCreate.setOnClickListener(v -> showCreateDialog());
        btnImport.setOnClickListener(v -> showImportDialog());
    }

    private void showCreateDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_create_wallet, null);
        TextView tvMnemonic = dialogView.findViewById(R.id.tvGeneratedMnemonic);
        Button btnGenerate = dialogView.findViewById(R.id.btnGenerateRandom);
        EditText etLabel = dialogView.findViewById(R.id.etWalletLabel);

        final M3RAddressFactory.WalletKey[] generatedKey = {null};

        btnGenerate.setOnClickListener(v -> {
            executor.submit(() -> {
                generatedKey[0] = repo.generateWallet(null); // random
                runOnUiThread(() -> {
                    tvMnemonic.setText("Address: " + generatedKey[0].addressBase58
                            + "\n\nPrivate Key: " + generatedKey[0].privateKeyHex()
                            + "\n\n⚠️ Save this PRIVATE KEY securely! It cannot be recovered.");
                    tvMnemonic.setVisibility(View.VISIBLE);
                });
            });
        });

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Create New Wallet")
                .setView(dialogView)
                .setPositiveButton("Save Wallet", (d, w) -> {
                    if (generatedKey[0] == null) {
                        Toast.makeText(this, "Please generate a wallet first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String label = etLabel.getText().toString().trim();
                    if (label.isEmpty()) label = "My Wallet";
                    final String finalLabel = label;
                    executor.submit(() -> {
                        repo.saveWallet(generatedKey[0], finalLabel);
                        runOnUiThread(this::goToPinSetup);
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showImportDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_import_wallet, null);
        EditText etMnemonic = dialogView.findViewById(R.id.etMnemonicOrKey);
        EditText etLabel    = dialogView.findViewById(R.id.etImportLabel);

        new AlertDialog.Builder(this, R.style.DialogTheme)
                .setTitle("Import Wallet")
                .setView(dialogView)
                .setPositiveButton("Import", (d, w) -> {
                    String input = etMnemonic.getText().toString().trim();
                    if (input.isEmpty()) {
                        Toast.makeText(this, "Enter mnemonic phrase or private key", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String label = etLabel.getText().toString().trim();
                    if (label.isEmpty()) label = "Imported Wallet";
                    final String finalLabel = label;

                    executor.submit(() -> {
                        try {
                            M3RAddressFactory.WalletKey key = repo.generateWallet(input);
                            repo.saveWallet(key, finalLabel);
                            runOnUiThread(this::goToPinSetup);
                        } catch (Exception e) {
                            runOnUiThread(() ->
                                    Toast.makeText(this, "Import failed: " + e.getMessage(),
                                            Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void goToPinSetup() {
        startActivity(new Intent(this, PinSetupActivity.class));
        finish();
    }
}
