package com.m3r.wallet.ui.lock;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.m3r.wallet.R;
import com.m3r.wallet.core.crypto.Hash;
import com.m3r.wallet.data.local.WalletStorage;
import com.m3r.wallet.ui.MainActivity;

import java.util.concurrent.Executor;

public class LockActivity extends AppCompatActivity {

    private static final int PIN_LENGTH = 6;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 30_000L;

    // F10: Persistent lockout keys
    private static final String LOCKOUT_PREFS = "m3r_lockout_prefs";
    private static final String KEY_FAILED_ATTEMPTS = "failed_attempts";
    private static final String KEY_LOCKOUT_END_TIME = "lockout_end_time";

    private final View[] dots = new View[PIN_LENGTH];
    private final StringBuilder pinBuffer = new StringBuilder();

    private TextView tvSubtitle, tvLockout;
    private LinearLayout dotsContainer;
    private Button btnFingerprint;

    private WalletStorage storage;
    private SharedPreferences lockoutPrefs; // F10: persistent lockout state
    private int failedAttempts = 0;
    private boolean lockedOut = false;
    private CountDownTimer lockoutTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        storage = new WalletStorage(this);
        // F10: Use a separate non-encrypted SharedPreferences for lockout state
        // (must work even if encrypted prefs fail, and doesn't contain sensitive data)
        lockoutPrefs = getSharedPreferences(LOCKOUT_PREFS, MODE_PRIVATE);

        tvSubtitle = findViewById(R.id.tvLockSubtitle);
        tvLockout = findViewById(R.id.tvLockout);
        dotsContainer = findViewById(R.id.dotsContainer);
        btnFingerprint = findViewById(R.id.btnFingerprint);

        dots[0] = findViewById(R.id.dot1);
        dots[1] = findViewById(R.id.dot2);
        dots[2] = findViewById(R.id.dot3);
        dots[3] = findViewById(R.id.dot4);
        dots[4] = findViewById(R.id.dot5);
        dots[5] = findViewById(R.id.dot6);

        // F10: Restore persisted lockout state
        failedAttempts = lockoutPrefs.getInt(KEY_FAILED_ATTEMPTS, 0);
        long lockoutEndTime = lockoutPrefs.getLong(KEY_LOCKOUT_END_TIME, 0);
        long now = System.currentTimeMillis();

        if (lockoutEndTime > now) {
            // Still locked out from a previous session
            startLockoutWithRemaining(lockoutEndTime - now);
        } else if (lockoutEndTime > 0) {
            // Lockout expired — reset
            failedAttempts = 0;
            persistLockoutState(0, 0);
        }

        setupKeypad();
        setupBiometric();
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

    private void setupBiometric() {
        boolean biometricEnabled = storage.isBiometricEnabled();
        boolean biometricAvailable = BiometricManager.from(this)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                == BiometricManager.BIOMETRIC_SUCCESS;

        if (biometricEnabled && biometricAvailable) {
            btnFingerprint.setVisibility(View.VISIBLE);
            btnFingerprint.setOnClickListener(v -> showBiometricPrompt());
            // Auto-trigger on launch (only if not locked out)
            if (!lockedOut) {
                showBiometricPrompt();
            }
        } else {
            btnFingerprint.setVisibility(View.INVISIBLE);
        }
    }

    private void showBiometricPrompt() {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt prompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        // F10: Reset lockout on successful biometric auth
                        failedAttempts = 0;
                        persistLockoutState(0, 0);
                        onUnlockSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        // User cancelled or error — they can still use PIN
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        tvSubtitle.setText("Fingerprint not recognized");
                        tvSubtitle.setTextColor(getColor(R.color.error_red));
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock M3R Wallet")
                .setSubtitle("Use your fingerprint to access your wallet")
                .setNegativeButtonText("Use PIN instead")
                .build();

        prompt.authenticate(promptInfo);
    }

    private void onDigitPress(String digit) {
        if (lockedOut || pinBuffer.length() >= PIN_LENGTH) return;

        pinBuffer.append(digit);
        updateDots();

        if (pinBuffer.length() == PIN_LENGTH) {
            verifyPin();
        }
    }

    private void onBackspace() {
        if (lockedOut || pinBuffer.length() == 0) return;
        pinBuffer.deleteCharAt(pinBuffer.length() - 1);
        updateDots();
    }

    private void updateDots() {
        for (int i = 0; i < PIN_LENGTH; i++) {
            dots[i].setBackgroundResource(
                    i < pinBuffer.length() ? R.drawable.pin_dot_filled : R.drawable.pin_dot_empty);
        }
    }

    private void verifyPin() {
        String enteredPin = pinBuffer.toString();

        if (storage.verifyPin(enteredPin)) {
            // F17: If the unlock was successful using a legacy SHA-256 hash, upgrade it to PBKDF2 now
            if (storage.isLegacyPinHash()) {
                storage.savePinHash(enteredPin);
            }

            // F10: Reset lockout on successful PIN
            failedAttempts = 0;
            persistLockoutState(0, 0);
            onUnlockSuccess();
        } else {
            failedAttempts++;
            persistLockoutState(failedAttempts, 0); // Update attempts, no lockout yet
            onWrongPin();
        }
    }

    private void onUnlockSuccess() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
        overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    private void onWrongPin() {
        pinBuffer.setLength(0);

        // Shake animation
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        dotsContainer.startAnimation(shake);

        // Reset dots after shake
        shake.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationRepeat(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                updateDots();
            }
        });

        if (failedAttempts >= MAX_ATTEMPTS) {
            startLockout();
        } else {
            int remaining = MAX_ATTEMPTS - failedAttempts;
            tvSubtitle.setText("Wrong PIN. " + remaining + " attempts remaining");
            tvSubtitle.setTextColor(getColor(R.color.error_red));
        }
    }

    private void startLockout() {
        // F10: Persist lockout end time so it survives app restarts
        long lockoutEndTime = System.currentTimeMillis() + LOCKOUT_MS;
        persistLockoutState(failedAttempts, lockoutEndTime);
        startLockoutWithRemaining(LOCKOUT_MS);
    }

    private void startLockoutWithRemaining(long remainingMs) {
        lockedOut = true;
        tvLockout.setVisibility(View.VISIBLE);
        tvSubtitle.setText("Too many wrong attempts");
        tvSubtitle.setTextColor(getColor(R.color.error_red));

        if (lockoutTimer != null) {
            lockoutTimer.cancel();
        }

        lockoutTimer = new CountDownTimer(remainingMs, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                tvLockout.setText("Try again in " + (millisUntilFinished / 1000) + "s");
            }

            @Override
            public void onFinish() {
                lockedOut = false;
                failedAttempts = 0;
                persistLockoutState(0, 0);
                tvLockout.setVisibility(View.GONE);
                tvSubtitle.setText("Enter your 6-digit PIN to unlock");
                tvSubtitle.setTextColor(getColor(R.color.text_secondary));
            }
        };
        lockoutTimer.start();
    }

    // F10: Persist lockout state to SharedPreferences
    private void persistLockoutState(int attempts, long lockoutEndTime) {
        lockoutPrefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, attempts)
                .putLong(KEY_LOCKOUT_END_TIME, lockoutEndTime)
                .apply();
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (lockoutTimer != null) {
            lockoutTimer.cancel();
        }
    }

    @Override
    public void onBackPressed() {
        // Prevent going back from lock screen — force PIN or biometric
        moveTaskToBack(true);
    }
}
