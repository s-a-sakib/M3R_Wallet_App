package com.m3r.wallet.ui;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.m3r.wallet.R;
import com.m3r.wallet.ui.escrow.EscrowFragment;
import com.m3r.wallet.ui.history.HistoryFragment;
import com.m3r.wallet.ui.home.HomeFragment;
import com.m3r.wallet.ui.settings.SettingsFragment;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView nav = findViewById(R.id.bottomNav);

        if (savedInstanceState == null) {
            loadFragment(new HomeFragment());
        }

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            Fragment fragment;
            if      (id == R.id.nav_home)    fragment = new HomeFragment();
            else if (id == R.id.nav_escrow)  fragment = new EscrowFragment();
            else if (id == R.id.nav_history) fragment = new HistoryFragment();
            else if (id == R.id.nav_settings)fragment = new SettingsFragment();
            else return false;
            loadFragment(fragment);
            return true;
        });
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }
}
