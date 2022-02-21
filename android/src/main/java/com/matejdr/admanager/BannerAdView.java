package com.matejdr.admanager;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.facebook.react.views.view.ReactViewGroup;
import com.google.ads.mediation.admob.AdMobAdapter;
import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.RequestConfiguration;
import com.google.android.gms.ads.admanager.AdManagerAdRequest;
import com.google.android.gms.ads.admanager.AdManagerAdView;
import com.google.android.gms.ads.admanager.AppEventListener;
import com.matejdr.admanager.customClasses.CustomTargeting;
import com.matejdr.admanager.utils.Targeting;

import java.util.ArrayList;
import java.util.List;

class BannerAdView extends ReactViewGroup implements AppEventListener, LifecycleEventListener {

    protected AdManagerAdView adView;
    Activity currentActivityContext = null;
    String[] testDevices;
    AdSize[] validAdSizes;
    String adUnitID;
    AdSize adSize;

    // Targeting
    Boolean hasTargeting = false;
    CustomTargeting[] customTargeting;
    String[] categoryExclusions;
    String[] keywords;
    String contentURL;
    String publisherProvidedID;
    Location location;
    String correlator;

    public BannerAdView(final Context context, ReactApplicationContext applicationContext) {
        super(context);
        currentActivityContext = applicationContext.getCurrentActivity();
        applicationContext.addLifecycleEventListener(this);
        this.createAdView();
    }

    private void createAdView() {
        if (this.adView != null) this.adView.destroy();

        this.adView = new AdManagerAdView(currentActivityContext);

        this.adView.setAppEventListener(this);
        this.adView.setAdListener(new AdListener() {
            @Override
            public void onAdLoaded() {
                AdSize adSize = adView.getAdSize();

                int width = adSize.getWidthInPixels(getContext());
                int height = adSize.getHeightInPixels(getContext());

                if (!isFluid()) {
                    int left = adView.getLeft();
                    int top = adView.getTop();

                    View parent = (View) adView.getParent();

                    if (parent != null) {
                        int parentWidth = parent.getWidth();

                        left = (parentWidth - width) / 2;
                    }

                    adView.measure(width, height);
                    adView.layout(left, top, left + width, top + height);
                } else {
                    AdManagerAdView.LayoutParams layoutParams = new AdManagerAdView.LayoutParams(
                        ReactViewGroup.LayoutParams.MATCH_PARENT,
                        ReactViewGroup.LayoutParams.MATCH_PARENT);
                    adView.setLayoutParams(layoutParams);
                }

                updateLayout();

                sendOnSizeChangeEvent();
                WritableMap ad = Arguments.createMap();
                ad.putString("type", "banner");

                WritableMap gadSize = Arguments.createMap();
                gadSize.putDouble("width", adView.getAdSize().getWidth());
                gadSize.putDouble("height", adView.getAdSize().getHeight());

                ad.putMap("gadSize", gadSize);

                sendEvent(RNAdManagerBannerViewManager.EVENT_AD_LOADED, ad);
            }

            @Override
            public void onAdFailedToLoad(LoadAdError adError) {
                String errorMessage = "Unknown error";
                switch (adError.getCode()) {
                    case AdManagerAdRequest.ERROR_CODE_INTERNAL_ERROR:
                        errorMessage = "Internal error, an invalid response was received from the ad server.";
                        break;
                    case AdManagerAdRequest.ERROR_CODE_INVALID_REQUEST:
                        errorMessage = "Invalid ad request, possibly an incorrect ad unit ID was given.";
                        break;
                    case AdManagerAdRequest.ERROR_CODE_NETWORK_ERROR:
                        errorMessage = "The ad request was unsuccessful due to network connectivity.";
                        break;
                    case AdManagerAdRequest.ERROR_CODE_NO_FILL:
                        errorMessage = "The ad request was successful, but no ad was returned due to lack of ad inventory.";
                        break;
                }
                WritableMap event = Arguments.createMap();
                WritableMap error = Arguments.createMap();
                error.putString("message", errorMessage);
                event.putMap("error", error);
                sendEvent(RNAdManagerBannerViewManager.EVENT_AD_FAILED_TO_LOAD, event);
            }

            @Override
            public void onAdOpened() {
                sendEvent(RNAdManagerBannerViewManager.EVENT_AD_OPENED, null);
            }

            @Override
            public void onAdClosed() {
                sendEvent(RNAdManagerBannerViewManager.EVENT_AD_CLOSED, null);
            }

        });

        this.addView(this.adView);
    }

    private class MeasureAndLayoutRunnable implements Runnable {
        @Override
        public void run() {
            updateLayout();
        }
    }

    private boolean isFluid() {
        if (this.adSize != null && this.adSize.equals(AdSize.FLUID)) {
            return true;
        }

        if (this.adView == null) {
            return false;
        }

        AdSize adSize = this.adView.getAdSize();

        if (adSize == null) {
            return false;
        }

        boolean isFluid = adSize.isFluid();

        return isFluid;
    }

    @Override
    public void requestLayout() {
        super.requestLayout();

        if (isFluid()) {
            post(new MeasureAndLayoutRunnable());
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        if (isFluid()) {
            post(new MeasureAndLayoutRunnable());
        }
    }

    private static void measureAndLayout(View view, int width, int height) {
        int left = 0;
        int top = 0;

        view.measure(width, height);
        view.layout(left, top, left + width, top + height);
        view.requestLayout();
        view.invalidate();
        view.forceLayout();
    }

    int cachedWidth = 0;
    int cachedHeight = 0;

    private void updateLayout() {
        try {
            if (!isFluid()) {
                return;
            }

            if (adView == null) {
                return;
            }

            View parent = (View) adView.getParent();

            if (parent == null) {
                return;
            }

            int width = parent.getWidth();
            int height = parent.getHeight();

            if (cachedWidth == width && cachedHeight == height) {
                return;
            }

            cachedWidth = width;
            cachedHeight = height;

            // In case of fluid ads, every GAD view and their subviews must be laid out by hand,
            // otherwise the web view won't align to the container bounds.
            measureAndLayout(adView, width, height);

            ViewGroup child = (ViewGroup) adView.getChildAt(0);

            if (child != null) {
                measureAndLayout(child, width, height);

                ViewGroup webView = (ViewGroup) child.getChildAt(0);

                if (webView != null) {
                    measureAndLayout(webView, width, height);

                    ViewGroup internalChild = (ViewGroup) webView.getChildAt(0);

                    if (internalChild != null) {
                        measureAndLayout(internalChild, width, height);

                        ViewGroup leafNode = (ViewGroup) internalChild.getChildAt(0);

                        if (leafNode != null) {
                            measureAndLayout(leafNode, width, height);
                        }
                    }
                }
            }
        } catch (Exception exception) {
        }
    }

    private void sendOnSizeChangeEvent() {
        int width;
        int height;
        ReactContext reactContext = (ReactContext) getContext();
        WritableMap event = Arguments.createMap();
        AdSize adSize = this.adView.getAdSize();
        width = adSize.getWidth();
        height = adSize.getHeight();
        event.putString("type", "banner");
        event.putDouble("width", width);
        event.putDouble("height", height);
        sendEvent(RNAdManagerBannerViewManager.EVENT_SIZE_CHANGE, event);
    }

    private void sendEvent(String name, @Nullable WritableMap event) {
        ReactContext reactContext = (ReactContext) getContext();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                name,
                event);
    }

    public void loadBanner() {
        ArrayList<AdSize> adSizes = new ArrayList<AdSize>();
        if (this.adSize != null) {
            adSizes.add(this.adSize);
        }
        if (this.validAdSizes != null) {
            for (int i = 0; i < this.validAdSizes.length; i++) {
                if (!adSizes.contains(this.validAdSizes[i])) {
                    adSizes.add(this.validAdSizes[i]);
                }
            }
        }

        if (adSizes.size() == 0) {
            adSizes.add(AdSize.BANNER);
        }

        AdSize[] adSizesArray = adSizes.toArray(new AdSize[adSizes.size()]);
        this.adView.setAdSizes(adSizesArray);

        AdManagerAdRequest.Builder adRequestBuilder = new AdManagerAdRequest.Builder();

        List<String> testDevicesList = new ArrayList<>();
        if (testDevices != null) {
            for (int i = 0; i < testDevices.length; i++) {
                String testDevice = testDevices[i];
                if (testDevice == "SIMULATOR") {
                    testDevice = AdManagerAdRequest.DEVICE_ID_EMULATOR;
                }
                testDevicesList.add(testDevice);
            }
            RequestConfiguration requestConfiguration
                    = new RequestConfiguration.Builder()
                    .setTestDeviceIds(testDevicesList)
                    .build();
            MobileAds.setRequestConfiguration(requestConfiguration);
        }

        if (correlator == null) {
            correlator = (String) Targeting.getCorelator(adUnitID);
        }
        Bundle bundle = new Bundle();
        bundle.putString("correlator", correlator);

        adRequestBuilder.addNetworkExtrasBundle(AdMobAdapter.class, bundle);


        // Targeting
        if (hasTargeting) {
            if (customTargeting != null && customTargeting.length > 0) {
                for (int i = 0; i < customTargeting.length; i++) {
                    String key = customTargeting[i].key;
                    if (!key.isEmpty()) {
                        if (customTargeting[i].value != null && !customTargeting[i].value.isEmpty()) {
                            adRequestBuilder.addCustomTargeting(key, customTargeting[i].value);
                        } else if (customTargeting[i].values != null && !customTargeting[i].values.isEmpty()) {
                            adRequestBuilder.addCustomTargeting(key, customTargeting[i].values);
                        }
                    }
                }
            }
            if (categoryExclusions != null && categoryExclusions.length > 0) {
                for (int i = 0; i < categoryExclusions.length; i++) {
                    String categoryExclusion = categoryExclusions[i];
                    if (!categoryExclusion.isEmpty()) {
                        adRequestBuilder.addCategoryExclusion(categoryExclusion);
                    }
                }
            }
            if (keywords != null && keywords.length > 0) {
                for (int i = 0; i < keywords.length; i++) {
                    String keyword = keywords[i];
                    if (!keyword.isEmpty()) {
                        adRequestBuilder.addKeyword(keyword);
                    }
                }
            }
            if (contentURL != null) {
                adRequestBuilder.setContentUrl(contentURL);
            }
            if (publisherProvidedID != null) {
                adRequestBuilder.setPublisherProvidedId(publisherProvidedID);
            }
            if (location != null) {
                adRequestBuilder.setLocation(location);
            }
        }

        AdManagerAdRequest adRequest = adRequestBuilder.build();
        this.adView.loadAd(adRequest);
    }

    public void setAdUnitID(String adUnitID) {
        if (this.adUnitID != null) {
            // We can only set adUnitID once, so when it was previously set we have
            // to recreate the view
            this.createAdView();
        }
        this.adUnitID = adUnitID;
        this.adView.setAdUnitId(adUnitID);
    }

    public void setTestDevices(String[] testDevices) {
        this.testDevices = testDevices;
    }

    // Targeting
    public void setCustomTargeting(CustomTargeting[] customTargeting) {
        this.customTargeting = customTargeting;
    }

    public void setCategoryExclusions(String[] categoryExclusions) {
        this.categoryExclusions = categoryExclusions;
    }

    public void setKeywords(String[] keywords) {
        this.keywords = keywords;
    }

    public void setContentURL(String contentURL) {
        this.contentURL = contentURL;
    }

    public void setPublisherProvidedID(String publisherProvidedID) {
        this.publisherProvidedID = publisherProvidedID;
    }

    public void setLocation(Location location) {
        this.location = location;
    }

    public void setAdSize(AdSize adSize) {
        this.adSize = adSize;
    }

    public void setValidAdSizes(AdSize[] adSizes) {
        this.validAdSizes = adSizes;
    }

    public void setCorrelator(String correlator) {
        this.correlator = correlator;
    }

    @Override
    public void onAppEvent(String name, String info) {
        WritableMap event = Arguments.createMap();
        event.putString("name", name);
        event.putString("info", info);
        sendEvent(RNAdManagerBannerViewManager.EVENT_APP_EVENT, event);
    }

    @Override
    public void onHostResume() {
        if (this.adView != null) {
            this.adView.resume();
        }
    }

    @Override
    public void onHostPause() {
        if (this.adView != null) {
            this.adView.pause();
        }
    }

    @Override
    public void onHostDestroy() {
        if (this.adView != null) {
            this.currentActivityContext = null;
            this.adView.destroy();
        }
    }
}
