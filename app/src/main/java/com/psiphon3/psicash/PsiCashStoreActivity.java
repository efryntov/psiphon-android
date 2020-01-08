package com.psiphon3.psicash;

import android.content.Context;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.design.widget.TabLayout;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.view.ViewPager;
import android.widget.TextView;

import com.psiphon3.psiphonlibrary.LocalizedActivities;
import com.psiphon3.subscription.R;

import java.util.ArrayList;
import java.util.Locale;

import io.reactivex.Single;

public class PsiCashStoreActivity extends LocalizedActivities.AppCompatActivity {

    public static final String PSICASH_BALANCE_EXTRA = "PSICASH_BALANCE_EXTRA";
    public static final String PSICASH_SKU_DETAILS_LIST_EXTRA = "PSICASH_SKU_DETAILS_LIST_EXTRA";
    public static final String PURCHASE_SPEEDBOOST = "PURCHASE_SPEEDBOOST";
    static final String PURCHASE_SPEEDBOOST_DISTINGUISHER = "PURCHASE_SPEEDBOOST_DISTINGUISHER";
    static final String PURCHASE_SPEEDBOOST_EXPECTED_PRICE = "PURCHASE_SPEEDBOOST_EXPECTED_PRICE";
    public static final String PURCHASE_PSICASH = "PURCHASE_PSICASH";
    static final String PURCHASE_PSICASH_GET_FREE = "PURCHASE_PSICASH_GET_FREE";
    public static final String PURCHASE_PSICASH_SKU_DETAILS_JSON = "PURCHASE_PSICASH_SKU_DETAILS_JSON";
    public static final String SPEEDBOOST_CONNECT_PSIPHON_EXTRA = "SPEEDBOOST_CONNECT_PSIPHON_EXTRA";


    TabLayout tabLayout;
    ViewPager viewPager;
    PageAdapter pageAdapter;

    static Single<PsiCashClient> getPsiCashClientSingle(final Context context) {
        return Single.fromCallable(() -> PsiCashClient.getInstance(context));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.psicash_store_activity);

        TextView balanceLabel = findViewById(R.id.psicash_balance_label);
        int uiBalance = getIntent().getIntExtra(PSICASH_BALANCE_EXTRA, 0);
        balanceLabel.setText(String.format(Locale.US, "%d", uiBalance));

        tabLayout = findViewById(R.id.psicash_store_tablayout);

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                viewPager.setCurrentItem(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        ArrayList<String> jsonSkuDetailsList = getIntent().getStringArrayListExtra(PSICASH_SKU_DETAILS_LIST_EXTRA);
        pageAdapter = new PageAdapter(getSupportFragmentManager(), tabLayout.getTabCount(), jsonSkuDetailsList);
        viewPager = findViewById(R.id.psicash_store_viewpager);
        viewPager.setAdapter(pageAdapter);
        viewPager.addOnPageChangeListener(new TabLayout.TabLayoutOnPageChangeListener(tabLayout));
    }

    static class PageAdapter extends FragmentPagerAdapter {
        private final ArrayList<String> jsonSkuDetailsList;
        private int numOfTabs;

        PageAdapter(FragmentManager fm, int numOfTabs, ArrayList<String> jsonSkuDetailsList) {
            super(fm);
            this.numOfTabs = numOfTabs;
            this.jsonSkuDetailsList = jsonSkuDetailsList;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    PsiCashInAppPurchaseFragment psiCashInAppPurchaseFragment = new PsiCashInAppPurchaseFragment();
                    Bundle data = new Bundle();
                    data.putStringArrayList(PSICASH_SKU_DETAILS_LIST_EXTRA, jsonSkuDetailsList);
                    psiCashInAppPurchaseFragment.setArguments(data);
                    return psiCashInAppPurchaseFragment;
                case 1:
                    return new PurchaseSpeedBoostFragment();
                default:
                    return null;
            }
        }

        @Override
        public int getCount() {
            return numOfTabs;
        }
    }

}
