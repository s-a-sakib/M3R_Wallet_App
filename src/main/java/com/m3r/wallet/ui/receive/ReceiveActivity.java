package com.m3r.wallet.ui.receive;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.m3r.wallet.R;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;

public class ReceiveActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receive);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Receive BDT");
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        WalletRepository repo = WalletRepository.get(this);
        WalletStorage.StoredWallet wallet = repo.getStoredWallet();

        ImageView ivQr = findViewById(R.id.ivQrCode);
        TextView tvAddr = findViewById(R.id.tvReceiveAddress);
        TextView btnCopy = findViewById(R.id.btnCopyReceiveAddress);

        if (wallet == null)
            return;

        tvAddr.setText(wallet.addressBase58);

        // Generate QR code
        repo.getExecutor().submit(() -> {
            try {
                Bitmap qr = generateQr(wallet.addressBase58, 600);
                runOnUiThread(() -> ivQr.setImageBitmap(qr));
            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("M3R Address", wallet.addressBase58));
            Toast.makeText(this, "Address copied!", Toast.LENGTH_SHORT).show();
        });
    }

    private Bitmap generateQr(String content, int size) throws WriterException {
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size);

        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                // matrix.get(x, y) ? bg_primary : accent_cyan
                bmp.setPixel(x, y, matrix.get(x, y) ? 0xFF0B0E14 : 0xFF00F2FF);
            }
        }
        return bmp;
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
