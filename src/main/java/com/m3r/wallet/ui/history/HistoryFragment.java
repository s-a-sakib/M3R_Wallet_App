package com.m3r.wallet.ui.history;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.m3r.wallet.R;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class HistoryFragment extends Fragment {

    private RecyclerView recyclerView;
    private SwipeRefreshLayout swipeRefresh;
    private TextView tvEmpty;
    private WalletRepository repo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_history, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        repo = WalletRepository.get(requireContext());

        swipeRefresh = view.findViewById(R.id.swipeRefresh);
        recyclerView = view.findViewById(R.id.rvHistory);
        tvEmpty = view.findViewById(R.id.tvEmptyHistory);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        
        swipeRefresh.setOnRefreshListener(this::refreshData);
        
        loadHistory();
        
        swipeRefresh.setRefreshing(true);
        refreshData();
    }

    private void refreshData() {
        repo.refreshPendingTransactions(() -> {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    swipeRefresh.setRefreshing(false);
                    loadHistory();
                });
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        loadHistory();
    }

    private void loadHistory() {
        List<WalletStorage.TxRecord> list = repo.getTxHistory();
        if (list.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            tvEmpty.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            tvEmpty.setVisibility(View.GONE);
            recyclerView.setAdapter(new TxAdapter(list));
        }
    }

    // ---- Adapter ----

    static class TxAdapter extends RecyclerView.Adapter<TxAdapter.VH> {
        private final List<WalletStorage.TxRecord> items;
        private final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd HH:mm", Locale.US);

        TxAdapter(List<WalletStorage.TxRecord> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tx, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            WalletStorage.TxRecord tx = items.get(pos);
            h.tvType.setText(txIcon(tx.type) + " " + txLabel(tx.type));
            double bdt = tx.amount / 100.0;
            String amtStr = String.format(Locale.US, "%.2f BDT", bdt);
            h.tvTime.setText(sdf.format(new Date(tx.timestamp)));
            h.tvStatus.setText(tx.status);

            // Status color
            int color;
            switch (tx.status) {
                case "CONFIRMED":
                    color = 0xFF00C896;
                    break;
                case "REJECTED":
                    color = 0xFFFF4757;
                    break;
                default:
                    color = 0xFFFFA502; // PENDING
            }
            h.tvStatus.setTextColor(color);

            // Amount colour: green for incoming funds, red for outgoing
            boolean isFundsIn = WalletStorage.TxRecord.TYPE_RECEIVE.equals(tx.type)
                    || WalletStorage.TxRecord.TYPE_ESCROW_RELEASE_RECEIVED.equals(tx.type)
                    || WalletStorage.TxRecord.TYPE_ESCROW_REFUND_RECEIVED.equals(tx.type);
            if (isFundsIn) {
                h.tvAmount.setTextColor(0xFF00C896);
                h.tvAmount.setText("+" + amtStr);
            } else {
                h.tvAmount.setTextColor(0xFFFF4757);
                h.tvAmount.setText("-" + amtStr);
            }

            // Short hash
            String hash = tx.txHash != null ? tx.txHash : "—";
            h.tvHash.setText(
                    hash.length() > 16 ? hash.substring(0, 8) + "..." + hash.substring(hash.length() - 8) : hash);

            h.itemView.setOnClickListener(v -> {
                StringBuilder details = new StringBuilder();
                details.append("Status: ").append(tx.status).append("\n");
                details.append("Type: ").append(txLabel(tx.type)).append("\n");
                details.append("Time: ").append(sdf.format(new Date(tx.timestamp))).append("\n");
                details.append("Amount: ").append(amtStr).append("\n");
                details.append("Fee: ").append(String.format(Locale.US, "%.4f BDT", tx.fee / 100.0)).append("\n");
                details.append("From: ").append(tx.fromAddress != null ? tx.fromAddress : "—").append("\n");
                details.append("To: ").append(tx.toAddress != null ? tx.toAddress : "—").append("\n");
                details.append("TX Hash:\n").append(hash).append("\n");
                
                // Show escrow details for ALL escrow-related types
                boolean isEscrowType = tx.type != null && tx.type.startsWith("ESCROW_");
                if (isEscrowType && tx.escrowId != null) {
                    details.append("\n=== Escrow Details ===\n");
                    details.append("Escrow ID:\n").append(tx.escrowId).append("\n");
                    if (tx.escrowExpiry > 0) {
                        details.append("Expiry: ").append(sdf.format(new Date(tx.escrowExpiry * 1000L))).append("\n");
                    }
                }

                androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(h.itemView.getContext(), R.style.DialogTheme)
                        .setTitle("Transaction Details")
                        .setMessage(details.toString())
                        .setPositiveButton("Close", null);

                // Re-use the isEscrowType flag (no re-declaration)
                if (isEscrowType && tx.escrowId != null) {
                    builder.setNeutralButton("Copy ID", (d, w) -> {
                        android.content.ClipboardManager cb = (android.content.ClipboardManager) h.itemView.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Escrow ID", tx.escrowId));
                        android.widget.Toast.makeText(h.itemView.getContext(), "Escrow ID copied", android.widget.Toast.LENGTH_SHORT).show();
                    });
                } else if (hash != null && !hash.equals("—")) {
                    builder.setNeutralButton("Copy Hash", (d, w) -> {
                        android.content.ClipboardManager cb = (android.content.ClipboardManager) h.itemView.getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        cb.setPrimaryClip(android.content.ClipData.newPlainText("Tx Hash", hash));
                        android.widget.Toast.makeText(h.itemView.getContext(), "Tx Hash copied", android.widget.Toast.LENGTH_SHORT).show();
                    });
                }

                androidx.appcompat.app.AlertDialog dialog = builder.show();
                TextView tvMessage = dialog.findViewById(android.R.id.message);
                if (tvMessage != null) {
                    tvMessage.setTextIsSelectable(true);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private String txIcon(String type) {
            if (type == null) return "↗";
            switch (type) {
                case WalletStorage.TxRecord.TYPE_RECEIVE:
                    return "↙";
                case WalletStorage.TxRecord.TYPE_ESCROW_CREATE:
                    return "🔒";
                case WalletStorage.TxRecord.TYPE_ESCROW_RECEIVE:
                    return "📥";
                case WalletStorage.TxRecord.TYPE_ESCROW_ARBITER:
                    return "⚖️";
                case WalletStorage.TxRecord.TYPE_ESCROW_RELEASE:
                    return "🔓";
                case WalletStorage.TxRecord.TYPE_ESCROW_RELEASE_RECEIVED:
                    return "💰";
                case WalletStorage.TxRecord.TYPE_ESCROW_REFUND:
                    return "↩";
                case WalletStorage.TxRecord.TYPE_ESCROW_REFUND_RECEIVED:
                    return "↩";
                default:
                    return "↗";
            }
        }

        private String txLabel(String type) {
            if (type == null) return "Transfer";
            switch (type) {
                case WalletStorage.TxRecord.TYPE_RECEIVE:
                    return "Received";
                case WalletStorage.TxRecord.TYPE_ESCROW_CREATE:
                    return "Escrow Created";
                case WalletStorage.TxRecord.TYPE_ESCROW_RECEIVE:
                    return "Escrow Incoming";
                case WalletStorage.TxRecord.TYPE_ESCROW_ARBITER:
                    return "Arbiter Assigned";
                case WalletStorage.TxRecord.TYPE_ESCROW_RELEASE:
                    return "Escrow Released";
                case WalletStorage.TxRecord.TYPE_ESCROW_RELEASE_RECEIVED:
                    return "Escrow Paid Out";
                case WalletStorage.TxRecord.TYPE_ESCROW_REFUND:
                    return "Escrow Refunded";
                case WalletStorage.TxRecord.TYPE_ESCROW_REFUND_RECEIVED:
                    return "Refund Received";
                default:
                    return "Sent";
            }
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvType, tvAmount, tvTime, tvStatus, tvHash;

            VH(View v) {
                super(v);
                tvType = v.findViewById(R.id.tvTxType);
                tvAmount = v.findViewById(R.id.tvTxAmount);
                tvTime = v.findViewById(R.id.tvTxTime);
                tvStatus = v.findViewById(R.id.tvTxStatus);
                tvHash = v.findViewById(R.id.tvTxHash);
            }
        }
    }
}
