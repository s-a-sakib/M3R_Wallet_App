package com.m3r.wallet;

import android.app.Application;
import com.m3r.wallet.data.repository.WalletRepository;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class M3RWalletApp extends Application {

    private int activityCount = 0;
    private long lastBackgroundTime = 0;
    private static final long LOCK_TIMEOUT_MS = 2000; // 2 seconds threshold

    @Override
    public void onCreate() {
        super.onCreate();

        // Register BouncyCastle
        Security.removeProvider("BC");
        Security.addProvider(new BouncyCastleProvider());

        // Init repository
        WalletRepository repo = WalletRepository.get(this);

        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityCreated(android.app.Activity activity, android.os.Bundle savedInstanceState) {}
            
            @Override
            public void onActivityStarted(android.app.Activity activity) {
                if (activityCount == 0) {
                    // App came to foreground
                    if (System.currentTimeMillis() - lastBackgroundTime > LOCK_TIMEOUT_MS) {
                        com.m3r.wallet.data.local.WalletStorage storage = new com.m3r.wallet.data.local.WalletStorage(M3RWalletApp.this);
                        if (storage.hasPinSet() 
                            && !(activity instanceof com.m3r.wallet.ui.lock.LockActivity) 
                            && !(activity instanceof com.m3r.wallet.ui.onboarding.SplashActivity)
                            && !(activity instanceof com.m3r.wallet.ui.onboarding.OnboardingActivity)
                            && !(activity instanceof com.m3r.wallet.ui.lock.PinSetupActivity)) {
                            
                            android.content.Intent intent = new android.content.Intent(activity, com.m3r.wallet.ui.lock.LockActivity.class);
                            activity.startActivity(intent);
                        }
                    }
                }
                activityCount++;
            }

            @Override
            public void onActivityResumed(android.app.Activity activity) {}
            @Override
            public void onActivityPaused(android.app.Activity activity) {}

            @Override
            public void onActivityStopped(android.app.Activity activity) {
                activityCount--;
                if (activityCount == 0) {
                    lastBackgroundTime = System.currentTimeMillis();
                }
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity activity, android.os.Bundle outState) {}
            @Override
            public void onActivityDestroyed(android.app.Activity activity) {}
        });
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
    }
}
