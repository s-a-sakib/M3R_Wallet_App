package com.m3r.wallet.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.m3r.wallet.R;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;

public class SettingsFragment extends Fragment {

    private WalletRepository repo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repo = WalletRepository.get(requireContext());

        WalletStorage.StoredWallet wallet = repo.getStoredWallet();

        TextView tvWalletAddress = view.findViewById(R.id.tvSettingsAddress);
        TextView tvWalletLabel   = view.findViewById(R.id.tvSettingsLabel);
        TextView tvNodeUrl       = view.findViewById(R.id.tvSettingsNodeUrl);
        TextView tvChainId       = view.findViewById(R.id.tvSettingsChainId);
        Switch   swMockNode      = view.findViewById(R.id.swMockNode);
        TextView btnCopyAddr     = view.findViewById(R.id.btnCopyAddr);
        TextView btnShowMnemonic = view.findViewById(R.id.btnShowMnemonic);
        TextView btnChangeNode   = view.findViewById(R.id.btnChangeNodeUrl);
        TextView btnMockStatus   = view.findViewById(R.id.tvMockStatus);
        TextView btnChangePin    = view.findViewById(R.id.btnChangePin);

        if (wallet != null) {
            tvWalletAddress.setText(wallet.addressBase58);
            tvWalletLabel.setText(wallet.label != null ? wallet.label : "My Wallet");
        }

        tvNodeUrl.setText(repo.getNodeProviderUrl());
        tvChainId.setText(repo.getChainId().label());
        swMockNode.setOnCheckedChangeListener(null);
        swMockNode.setChecked(repo.isUsingTestNet());
        updateMockStatus(btnMockStatus);

        btnCopyAddr.setOnClickListener(v -> {
            if (wallet != null) {
                ClipboardManager cm = (ClipboardManager)
                        requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("M3R Address", wallet.addressBase58));
                Toast.makeText(requireContext(), "Address copied!", Toast.LENGTH_SHORT).show();
            }
        });

        btnShowMnemonic.setOnClickListener(v -> {
            if (wallet == null) return;
            String info = wallet.mnemonic != null
                    ? "Mnemonic:\n" + wallet.mnemonic
                    : "Private Key:\n" + wallet.privateKeyHex
                    + "\n\n⚠️ Keep this SECRET!";
            new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                    .setTitle("Backup Info")
                    .setMessage(info)
                    .setPositiveButton("Copy", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager)
                                requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        String data = wallet.mnemonic != null ? wallet.mnemonic : wallet.privateKeyHex;
                        cm.setPrimaryClip(ClipData.newPlainText("M3R Backup", data));
                        Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", null)
                    .show();
        });

        btnChangeNode.setOnClickListener(v -> {
            EditText et = new EditText(requireContext());
            et.setText(repo.getNodeProviderUrl());
            et.setTextColor(0xFFFFFFFF);

            new AlertDialog.Builder(requireContext(), R.style.DialogTheme)
                    .setTitle("Node Provider URL")
                    .setView(et)
                    .setPositiveButton("Save", (d, w) -> {
                        String url = et.getText().toString().trim();
                        if (!url.isEmpty()) {
                            repo.setNodeProviderUrl(url);
                            tvNodeUrl.setText(url);
                            toast("Node Provider URL updated");
                            updateMockStatus(btnMockStatus);
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        swMockNode.setOnCheckedChangeListener((btn, checked) -> {
            repo.setUseTestNet(checked);
            if (checked) {
                toast("Switched to M3R Test Net");
            } else {
                toast("Switched to M3R Main Net");
            }
            updateMockStatus(btnMockStatus);
        });

        btnChangePin.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(requireContext(), com.m3r.wallet.ui.lock.PinSetupActivity.class);
            intent.putExtra(com.m3r.wallet.ui.lock.PinSetupActivity.EXTRA_CHANGE_MODE, true);
            startActivity(intent);
        });
    }

    private void updateMockStatus(TextView tv) {
        if (repo.isUsingTestNet()) {
            tv.setText("🟢 M3R Test Net Active: " + repo.getNodeProviderUrl() + "/testnet");
        } else {
            tv.setText("⚪ M3R Main Net Active: " + repo.getNodeProviderUrl() + "/mainnet");
        }
    }

    private void toast(String msg) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
    }
}
