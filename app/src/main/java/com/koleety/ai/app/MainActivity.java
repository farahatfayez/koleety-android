package com.koleety.ai.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import androidx.activity.ComponentActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Native KOLEETY shell. It intentionally has no TWA, Chrome Custom Tabs, or
 * JavaScript bridge. Only trusted HTTPS pages are kept in the embedded WebView.
 */
public class MainActivity extends ComponentActivity {
    private static final int FILE_CHOOSER_REQUEST = 9103;
    private static final int WEB_PERMISSION_REQUEST = 9104;
    private static final String STATE_PENDING_CAMERA_URI = "pending_camera_uri";
    private static final String STATE_FILE_CHOOSER_ACTIVE = "file_chooser_active";

    private WebView webView;
    private LinearLayout loadingPanel;
    private LinearLayout errorPanel;
    private MediaRequestViewModel mediaRequests;

    /**
     * The system camera, file picker, and runtime-permission dialogs may recreate an
     * activity. A ViewModel keeps their in-flight WebView callbacks alive during a
     * configuration change without retaining the Activity or WebView themselves.
     */
    public static final class MediaRequestViewModel extends ViewModel {
        ValueCallback<Uri[]> pendingFileCallback;
        Uri pendingCameraUri;
        PermissionRequest pendingWebPermissionRequest;
        boolean fileChooserActive;

        void clearFileChooser() {
            pendingFileCallback = null;
            pendingCameraUri = null;
            fileChooserActive = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(com.koleety.ai.app.R.layout.activity_main);

        webView = findViewById(com.koleety.ai.app.R.id.koleety_webview);
        loadingPanel = findViewById(com.koleety.ai.app.R.id.loading_panel);
        errorPanel = findViewById(com.koleety.ai.app.R.id.error_panel);
        mediaRequests = new ViewModelProvider(this).get(MediaRequestViewModel.class);
        Button retryButton = findViewById(com.koleety.ai.app.R.id.retry_button);
        retryButton.setOnClickListener(v -> reloadHome());

        configureWebView();
        if (savedInstanceState == null) {
            reloadHome();
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " KOLEETYNative/0.1");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true);
        }
        // Debugging stays disabled in the test build to avoid exposing WebView internals.
        WebView.setWebContentsDebuggingEnabled(false);

        webView.setWebViewClient(new TrustedWebViewClient());
        webView.setWebChromeClient(new KoleetyChromeClient());
    }

    private void reloadHome() {
        errorPanel.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.VISIBLE);
        webView.loadUrl(BuildConfig.KOLEETY_HOME_URL);
    }

    private boolean isTrustedHost(Uri uri) {
        String host = uri.getHost();
        if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(BuildConfig.KOLEETY_TRUSTED_HOST)
            || host.equals("koleety.com")
            || host.equals("www.koleety.com")
            || host.endsWith(".manus.im");
    }

    private void openExternally(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            errorPanel.setVisibility(View.VISIBLE);
            loadingPanel.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        outState.putBoolean(STATE_FILE_CHOOSER_ACTIVE, mediaRequests.fileChooserActive);
        if (mediaRequests.pendingCameraUri != null) {
            outState.putString(STATE_PENDING_CAMERA_URI, mediaRequests.pendingCameraUri.toString());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        String savedCameraUri = savedInstanceState.getString(STATE_PENDING_CAMERA_URI);
        if (mediaRequests.pendingCameraUri == null && savedCameraUri != null) {
            mediaRequests.pendingCameraUri = Uri.parse(savedCameraUri);
        }
        mediaRequests.fileChooserActive = mediaRequests.pendingFileCallback != null
            || savedInstanceState.getBoolean(STATE_FILE_CHOOSER_ACTIVE, false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || mediaRequests.pendingFileCallback == null) return;
        Uri[] result = null;
        if (resultCode == Activity.RESULT_OK && data == null && mediaRequests.pendingCameraUri != null) {
            // ACTION_IMAGE_CAPTURE writes to the FileProvider URI and often returns no data Intent.
            result = new Uri[] { mediaRequests.pendingCameraUri };
        } else {
            result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        }
        mediaRequests.pendingFileCallback.onReceiveValue(result);
        mediaRequests.clearFileChooser();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != WEB_PERMISSION_REQUEST) return;
        PermissionRequest request = mediaRequests.pendingWebPermissionRequest;
        mediaRequests.pendingWebPermissionRequest = null;
        if (request == null) return;
        grantAllowedWebResources(request);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void grantAllowedWebResources(PermissionRequest request) {
        List<String> grantedResources = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                grantedResources.add(resource);
            }
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && hasPermission(Manifest.permission.CAMERA)) {
                grantedResources.add(resource);
            }
        }
        if (grantedResources.isEmpty()) {
            request.deny();
        } else {
            request.grant(grantedResources.toArray(new String[0]));
        }
    }

    private final class TrustedWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!request.isForMainFrame() || isTrustedHost(uri)) return false;
            openExternally(uri);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            loadingPanel.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            loadingPanel.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                loadingPanel.setVisibility(View.GONE);
                errorPanel.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (request.isForMainFrame() && errorResponse.getStatusCode() >= 500) {
                loadingPanel.setVisibility(View.GONE);
                errorPanel.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
            loadingPanel.setVisibility(View.GONE);
            errorPanel.setVisibility(View.VISIBLE);
        }
    }

    private final class KoleetyChromeClient extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                if (!isTrustedHost(request.getOrigin())) {
                    request.deny();
                    return;
                }
                List<String> missingPermissions = new ArrayList<>();
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
                        missingPermissions.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !hasPermission(Manifest.permission.CAMERA)) {
                        missingPermissions.add(Manifest.permission.CAMERA);
                    }
                }
                if (missingPermissions.isEmpty()) {
                    grantAllowedWebResources(request);
                    return;
                }
                if (mediaRequests.pendingWebPermissionRequest != null) mediaRequests.pendingWebPermissionRequest.deny();
                mediaRequests.pendingWebPermissionRequest = request;
                ActivityCompat.requestPermissions(
                    MainActivity.this,
                    missingPermissions.toArray(new String[0]),
                    WEB_PERMISSION_REQUEST
                );
            });
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (mediaRequests.pendingWebPermissionRequest == request) mediaRequests.pendingWebPermissionRequest = null;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
            if (mediaRequests.pendingFileCallback != null) mediaRequests.pendingFileCallback.onReceiveValue(null);
            mediaRequests.pendingFileCallback = filePathCallback;
            mediaRequests.pendingCameraUri = null;
            mediaRequests.fileChooserActive = true;
            try {
                Intent intent = createFileChooserIntent(fileChooserParams);
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException | IllegalArgumentException exception) {
                mediaRequests.pendingFileCallback.onReceiveValue(null);
                mediaRequests.clearFileChooser();
                return false;
            }
        }

        private Intent createFileChooserIntent(FileChooserParams params) {
            Intent picker = params.createIntent();
            if (!acceptsImages(params) && !params.isCaptureEnabled()) return picker;

            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            File directory = new File(getExternalCacheDir(), "lecture-captures");
            if (!directory.exists() && !directory.mkdirs()) return picker;
            File output = new File(directory, "lecture-" + System.currentTimeMillis() + ".jpg");
            mediaRequests.pendingCameraUri = FileProvider.getUriForFile(
                MainActivity.this,
                getPackageName() + ".fileprovider",
                output
            );
            capture.putExtra(MediaStore.EXTRA_OUTPUT, mediaRequests.pendingCameraUri);
            capture.setClipData(ClipData.newRawUri("captured_lecture_image", mediaRequests.pendingCameraUri));
            capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(picker, "اختر صورة المحاضرة");
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[] { capture });
            return chooser;
        }

        private boolean acceptsImages(FileChooserParams params) {
            for (String type : params.getAcceptTypes()) {
                if (type != null && (type.startsWith("image/") || type.equals("*/*"))) return true;
            }
            return false;
        }
    }
}
