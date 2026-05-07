package com.m3r.wallet.ui.onboarding;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.data.repository.WalletRepository;
import com.m3r.wallet.ui.lock.LockActivity;
import com.m3r.wallet.ui.lock.PinSetupActivity;
import androidx.core.splashscreen.SplashScreen;

public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);

        WalletRepository repo = WalletRepository.get(this);
        WalletStorage storage = new WalletStorage(this);

        Intent intent;
        if (!repo.hasWallet()) {
            // No wallet yet → onboarding
            intent = new Intent(this, OnboardingActivity.class);
        } else if (storage.hasPinSet()) {
            // Wallet exists + PIN set → lock screen
            intent = new Intent(this, LockActivity.class);
        } else {
            // Wallet exists but no PIN → force PIN setup (migration for existing users)
            intent = new Intent(this, PinSetupActivity.class);
        }
        startActivity(intent);
        finish();
    }
}

