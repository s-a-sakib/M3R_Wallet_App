package com.m3r.wallet.ui.lock;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.m3r.wallet.R;
import com.m3r.wallet.core.crypto.Hash;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.ui.MainActivity;

public class PinSetupActivity extends AppCompatActivity {

    private static final int PIN_LENGTH = 6;

    /** If true, we're changing an existing PIN (launched from Settings). */
    public static final String EXTRA_CHANGE_MODE = "change_mode";

    private final View[] dots = new View[PIN_LENGTH];
    private final StringBuilder pinBuffer = new StringBuilder();

    private TextView tvTitle, tvSubtitle;
    private LinearLayout dotsContainer;

    private WalletStorage storage;

    private enum State { VERIFY_OLD, CREATE, CONFIRM }
    private State state;
    private String firstPin = null;
    private boolean changeMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pin_setup);

        storage = new WalletStorage(this);
        changeMode = getIntent().getBooleanExtra(EXTRA_CHANGE_MODE, false);

        tvTitle = findViewById(R.id.tvSetupTitle);
        tvSubtitle = findViewById(R.id.tvSetupSubtitle);
        dotsContainer = findViewById(R.id.dotsContainer);

        dots[0] = findViewById(R.id.dot1);
        dots[1] = findViewById(R.id.dot2);
        dots[2] = findViewById(R.id.dot3);
        dots[3] = findViewById(R.id.dot4);
        dots[4] = findViewById(R.id.dot5);
        dots[5] = findViewById(R.id.dot6);

        setupKeypad();

        if (changeMode && storage.hasPinSet()) {
            state = State.VERIFY_OLD;
            tvTitle.setText("Current PIN");
            tvSubtitle.setText("Enter your current PIN to continue");
        } else {
            state = State.CREATE;
            tvTitle.setText("Create Your PIN");
            tvSubtitle.setText("Set a 6-digit PIN to secure your wallet");
        }
    }

    private void setupKeypad() {
        int[] btnIds = {
            R.id.btn0, R.id.btn1, R.id.btn2, R.id.btn3, R.id.btn4,
            R.id.btn5, R.id.btn6, R.id.btn7, R.id.btn8, R.id.btn9
        };
        for (int id : btnIds) {
            Button btn = findViewById(id);
            btn.setOnClickListener(v -> onDigitPress(((Button) v).getText().toString()));
        }
        findViewById(R.id.btnBackspace).setOnClickListener(v -> onBackspace());
    }

    private void onDigitPress(String digit) {
        if (pinBuffer.length() >= PIN_LENGTH) return;

        pinBuffer.append(digit);
        updateDots();

        if (pinBuffer.length() == PIN_LENGTH) {
            handlePinComplete();
        }
    }

    private void onBackspace() {
        if (pinBuffer.length() == 0) return;
        pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        updateDots();
    }

    private void updateDots() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            dots[i].setBackgroundResource(
                    i < pinBuffer.length() ? R.drawable.pin_dot_filled : R.drawable.pin_dot_empty);
        }
    }

    private void handlePinComplete() {
        String entered = pinBuffer.toString();

        switch (state) {
            case VERIFY_OLD:
                if (storage.verifyPin(entered)) {
                    // Correct — move to create new PIN
                    state = State.CREATE;
                    resetForNextStep("Create New PIN", "Enter a new 6-digit PIN");
                } else {
                    shakeAndReset("Wrong PIN. Try again.");
                }
                break;

            case CREATE:
                firstPin = entered;
                state = State.CONFIRM;
                resetForNextStep("Confirm PIN", "Re-enter your PIN to confirm");
                break;

            case CONFIRM:
                if (entered.equals(firstPin)) {
                    // Save PIN securely using PBKDF2
                    storage.savePinHash(entered);
                    Toast.makeText(this, "✅ PIN set successfully!", Toast.LENGTH_SHORT).show();
                    offerBiometric();
                } else {
                    // Mismatch — restart from CREATE
                    firstPin = null;
                    state = State.CREATE;
                    shakeAndReset("PINs didn't match. Try again.");
                    tvTitle.setText("Create Your PIN");
                }
                break;
        }
    }

    private void resetForNextStep(String title, String subtitle) {
        pinBuffer.setLength(0);
        updateDots();
        tvTitle.setText(title);
        tvSubtitle.setText(subtitle);
        tvSubtitle.setTextColor(getColor(R.color.text_secondary));
    }

    private void shakeAndReset(String message) {
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        dotsContainer.startAnimation(shake);
        shake.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                pinBuffer.setLength(0);
                updateDots();
            }
        });
        tvSubtitle.setText(message);
        tvSubtitle.setTextColor(getColor(R.color.error_red));
    }

    private void offerBiometric() {
        boolean canBiometric = BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;

        if (canBiometric) {
            new AlertDialog.Builder(this, R.style.DialogTheme)
                    .setTitle("Enable Fingerprint Unlock?")
                    .setMessage("Use your fingerprint to unlock the wallet quickly.")
                    .setPositiveButton("Enable", (d, w) -> {
                        storage.saveBiometricEnabled(true);
                        Toast.makeText(this, "👆 Fingerprint enabled!", Toast.LENGTH_SHORT).show();
                        navigateNext();
                    })
                    .setNegativeButton("Skip", (d, w) -> {
                        storage.saveBiometricEnabled(false);
                        navigateNext();
                    })
                    .setCancelable(false)
                    .show();
        } else {
            navigateNext();
        }
    }

    private void navigateNext() {
        if (changeMode) {
            // Came from Settings — just finish
            Toast.makeText(this, "PIN updated!", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            // First-time setup — go to MainActivity
            startActivity(new Intent(this, MainActivity.class));
            finish();
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @Override
    public void onBackPressed() {
        if (changeMode) {
            // Allow going back to Settings
            super.onBackPressed();
        } else {
            // During initial setup, don't allow skipping
            Toast.makeText(this, "Please set a PIN to continue", Toast.LENGTH_SHORT).show();
        }
    }
}
