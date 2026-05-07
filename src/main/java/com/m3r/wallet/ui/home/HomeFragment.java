package com.m3r.wallet.ui.home;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.SwitchCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.m3r.wallet.R;
import com.m3r.wallet.core.crypto.M3RAddressFactory;
import com.m3r.wallet.core.network.WalletNetwork;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;
import com.m3r.wallet.ui.receive.ReceiveActivity;
import com.m3r.wallet.ui.send.SendActivity;

import java.util.Locale;

public class HomeFragment extends Fragment {

    private TextView tvBalance, tvAddress, tvNonce, tvChainId, tvNodeStatus;
    private SwitchCompat swHomeMock;
    private SwipeRefreshLayout swipeRefresh;
    private WalletRepository repo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        repo = WalletRepository.get(requireContext());

        tvBalance = view.findViewById(R.id.tvBalance);
        tvAddress = view.findViewById(R.id.tvAddress);
        tvNonce = view.findViewById(R.id.tvNonce);
        tvChainId = view.findViewById(R.id.tvChainId);
        tvNodeStatus = view.findViewById(R.id.tvNodeStatus);
        swHomeMock = view.findViewById(R.id.swHomeMock);
        swipeRefresh = view.findViewById(R.id.swipeRefresh);

        Button btnSend = view.findViewById(R.id.btnSend);
        Button btnReceive = view.findViewById(R.id.btnReceive);
        ImageButton btnCopy = view.findViewById(R.id.btnCopyAddress);

        WalletStorage.StoredWallet wallet = repo.getStoredWallet();
        if (wallet != null) {
            String shortAddr = wallet.addressBase58.length() > 20
                    ? wallet.addressBase58.substring(0, 10) + "..." +
                            wallet.addressBase58.substring(wallet.addressBase58.length() - 10)
                    : wallet.addressBase58;
            tvAddress.setText(shortAddr);
        }

        tvChainId.setText(repo.getChainId().label());

        btnCopy.setOnClickListener(v -> {
            if (wallet != null) {
                ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("M3R Address", wallet.addressBase58));
                Toast.makeText(requireContext(), "Address copied!", Toast.LENGTH_SHORT).show();
            }
        });

        btnSend.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), SendActivity.class));
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out);
        });

        btnReceive.setOnClickListener(v -> {
            startActivity(new Intent(requireContext(), ReceiveActivity.class));
            requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.fade_out);
        });

        swHomeMock.setOnCheckedChangeListener(null);
        swHomeMock.setChecked(repo.isUsingTestNet());
        swHomeMock.setOnCheckedChangeListener((btn, checked) -> {
            repo.setUseTestNet(checked);
            if (checked) {
                Toast.makeText(requireContext(), "M3R Test Net Enabled", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "M3R Main Net Enabled", Toast.LENGTH_SHORT).show();
            }
            refreshBalance();
        });

        swipeRefresh.setOnRefreshListener(this::refreshBalance);
        swipeRefresh.setColorSchemeResources(R.color.accent_cyan);

        refreshBalance();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBalance();
    }

    private void refreshBalance() {
        WalletStorage.StoredWallet wallet = repo.getStoredWallet();
        if (wallet == null) {
            tvBalance.setText("No Wallet");
            swipeRefresh.setRefreshing(false);
            return;
        }

        tvNodeStatus.setText("Fetching...");

        repo.getExecutor().submit(() -> {
            try {
                M3RAddressFactory.WalletKey key = repo.loadWalletKey();
                if (key == null)
                    return;

                WalletNetwork.AccountInfo info = repo.getNetwork().getAccount(key.payload20, key.addressBase58);

                if (!isAdded())
                    return;
                requireActivity().runOnUiThread(() -> {
                    tvBalance.setText(formatAmount(info.balance));
                    tvNonce.setText("Nonce: " + info.nonce);
                    String status = repo.isUsingTestNet() ? "🟢 M3R Test Net" : "🟢 M3R Main Net";
                    tvNodeStatus.setText(status);
                    swHomeMock.setChecked(repo.isUsingTestNet());
                    swipeRefresh.setRefreshing(false);
                });
            } catch (Exception e) {
                if (!isAdded())
                    return;
                requireActivity().runOnUiThread(() -> {
                    tvBalance.setText("--");
                    tvNodeStatus.setText("🔴 Node Offline");
                    swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    private String formatAmount(long amount) {
        double bdt = amount / 100.0;
        return String.format(Locale.US, "%.2f BDT", bdt);
    }
}
