package com.hmhamster.boardgameweb;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String TAG = "BoardgameWeb";
    private static final long POLL_INTERVAL_MS = 500L;
    private static final long MAX_WAIT_MS = 5L * 60L * 1000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService probeExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean probeInFlight = new AtomicBoolean(false);

    private WebView webView;
    private View statusOverlay;
    private ProgressBar progressBar;
    private TextView statusText;
    private TextView statusHint;
    private Button retryButton;
    private View floatingActions;
    private ImageButton refreshButton;
    private ImageButton consoleButton;

    private long startedAt;
    private boolean pageLoadStarted;
    private boolean mainFrameFailed;
    private volatile boolean destroyed;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        configureSystemBars();

        webView = findViewById(R.id.web_view);
        statusOverlay = findViewById(R.id.status_overlay);
        progressBar = findViewById(R.id.progress_bar);
        statusText = findViewById(R.id.status_text);
        statusHint = findViewById(R.id.status_hint);
        retryButton = findViewById(R.id.retry_button);
        floatingActions = findViewById(R.id.floating_actions);
        refreshButton = findViewById(R.id.refresh_button);
        consoleButton = findViewById(R.id.console_button);

        configureWebView();
        retryButton.setOnClickListener(view -> retry());
        refreshButton.setOnClickListener(view -> reloadPage());
        consoleButton.setOnClickListener(view ->
                startActivity(new Intent(MainActivity.this, ServerConsoleActivity.class)));

        NodeRuntime.start(getApplicationContext());

        startedAt = System.currentTimeMillis();
        pollForServer();
    }

    /**
     * Android 15 enforces edge-to-edge layout for targetSdk 35, which makes
     * the status bar transparent. Use dark system-bar icons so they remain
     * readable over the app's light background.
     */
    private void configureSystemBars() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                int lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                controller.setSystemBarsAppearance(lightBars, lightBars);
            }
            return;
        }

        int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        webView.setBackgroundColor(getColor(R.color.app_background));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();

                if (host != null && (host.equals("127.0.0.1") || host.equals("localhost"))) {
                    return false;
                }

                String scheme = uri.getScheme();
                if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } catch (ActivityNotFoundException error) {
                        Log.w(TAG, "No browser available for " + uri, error);
                    }
                    return true;
                }

                // Keep internal schemes (blob:, data:, about:, ...) inside the WebView.
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (pageLoadStarted && !mainFrameFailed) {
                    hideStatusOverlay();
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) {
                    return;
                }

                mainFrameFailed = true;
                String description = String.valueOf(error.getDescription());
                showError(getString(R.string.status_webview_error, description));
            }
        });
    }

    private void pollForServer() {
        if (destroyed || !probeInFlight.compareAndSet(false, true)) {
            return;
        }

        probeExecutor.execute(() -> {
            boolean ready = isServerReady();
            mainHandler.post(() -> {
                probeInFlight.set(false);

                if (destroyed) {
                    return;
                }

                if (ready) {
                    onServerReady();
                    return;
                }

                if (NodeRuntime.hasExited()) {
                    showError(getString(R.string.status_node_exited, NodeRuntime.getExitCode()));
                    return;
                }

                long elapsed = System.currentTimeMillis() - startedAt;
                if (elapsed > MAX_WAIT_MS) {
                    showError(getString(R.string.status_timeout));
                    return;
                }

                statusText.setText(getString(R.string.status_waiting, (int) (elapsed / 1000L)));
                mainHandler.postDelayed(this::pollForServer, POLL_INTERVAL_MS);
            });
        });
    }

    private boolean isServerReady() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(NodeRuntime.SERVER_URL).openConnection();
            connection.setConnectTimeout(1000);
            connection.setReadTimeout(1000);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);

            int statusCode = connection.getResponseCode();
            return statusCode >= 200 && statusCode < 500;
        } catch (IOException error) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void onServerReady() {
        if (pageLoadStarted) {
            return;
        }

        pageLoadStarted = true;
        mainFrameFailed = false;
        progressBar.setVisibility(View.VISIBLE);
        retryButton.setVisibility(View.GONE);
        floatingActions.setVisibility(View.GONE);
        statusText.setText(R.string.status_loading_ui);
        statusHint.setVisibility(View.GONE);
        webView.loadUrl(NodeRuntime.SERVER_URL);
    }

    private void reloadPage() {
        if (webView == null || !pageLoadStarted) {
            return;
        }

        mainFrameFailed = false;
        webView.reload();
    }

    private void retry() {
        mainFrameFailed = false;
        pageLoadStarted = false;
        progressBar.setVisibility(View.VISIBLE);
        statusHint.setVisibility(View.GONE);
        retryButton.setVisibility(View.GONE);
        floatingActions.setVisibility(View.GONE);
        statusText.setText(R.string.status_starting);
        startedAt = System.currentTimeMillis();
        pollForServer();
    }

    private void showError(String message) {
        statusOverlay.setVisibility(View.VISIBLE);
        statusOverlay.bringToFront();
        progressBar.setVisibility(View.GONE);
        statusHint.setVisibility(View.GONE);
        statusText.setText(message);
        retryButton.setVisibility(View.VISIBLE);
        floatingActions.setVisibility(View.GONE);
    }

    private void hideStatusOverlay() {
        statusOverlay.setVisibility(View.GONE);
        floatingActions.setVisibility(View.VISIBLE);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        probeExecutor.shutdownNow();

        if (webView != null) {
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }

        super.onDestroy();
    }
}
