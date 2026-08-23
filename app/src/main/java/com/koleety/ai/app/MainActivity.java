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
import android.os.SystemClock;
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
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.FileProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import android.webkit.MimeTypeMap;

/**
 * Native KOLEETY shell. It intentionally has no TWA, Chrome Custom Tabs, or
 * JavaScript bridge. Only trusted HTTPS pages are kept in the embedded WebView.
 */
public class MainActivity extends ComponentActivity {
    private static final int WEB_PERMISSION_REQUEST = 9104;
    private static final String STATE_PENDING_CAMERA_URI = "pending_camera_uri";
    private static final String STATE_FILE_CHOOSER_ACTIVE = "file_chooser_active";

    private WebView webView;
    private LinearLayout loadingPanel;
    private LinearLayout errorPanel;
    private MediaRequestViewModel mediaRequests;
    private ActivityResultLauncher<Intent> fileChooserLauncher;

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
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> deliverFileChooserResult(result.getResultCode(), result.getData())
        );
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
        // TTS is requested after the student's tap; allow the resulting audio to start
        // when it arrives instead of treating the asynchronous play call as autoplay.
        settings.setMediaPlaybackRequiresUserGesture(false);
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

    private void deliverFileChooserResult(int resultCode, Intent data) {
        if (mediaRequests.pendingFileCallback == null) return;
        Uri[] result = null;
        if (resultCode == Activity.RESULT_OK && hasCapturedCameraImage()) {
            // Camera implementations sometimes return an empty Intent and sometimes a non-null
            // Intent after writing to EXTRA_OUTPUT. The FileProvider URI is the only reliable
            // source in both cases, but only after confirming that it contains image bytes.
            result = new Uri[] { mediaRequests.pendingCameraUri };
        } else if (resultCode == Activity.RESULT_OK) {
            Uri thumbnailUri = saveCameraThumbnail(data);
            result = thumbnailUri == null ? copySelectedUrisToAppCache(data) : new Uri[] { thumbnailUri };
        }
        if (result == null || result.length == 0) {
            Toast.makeText(this, "تعذّر تسليم الملف للتطبيق. حاول اختيار صورة أو ملف آخر.", Toast.LENGTH_LONG).show();
            result = null;
        }
        mediaRequests.pendingFileCallback.onReceiveValue(result);
        mediaRequests.clearFileChooser();
    }

    private boolean hasCapturedCameraImage() {
        Uri capturedUri = mediaRequests.pendingCameraUri;
        if (capturedUri == null) return false;
        // Some camera apps signal RESULT_OK just before their final flush. Retry briefly
        // instead of treating a valid photo as a lost result and returning to the WebView empty.
        for (int attempt = 0; attempt < 4; attempt++) {
            try (InputStream input = getContentResolver().openInputStream(capturedUri)) {
                if (input != null && input.read() != -1) return true;
            } catch (IOException ignored) {
                // Try the next short read before declaring the capture unavailable.
            }
            SystemClock.sleep(150);
        }
        return false;
    }

    private Uri saveCameraThumbnail(Intent data) {
        if (mediaRequests.pendingCameraUri == null || data == null || data.getExtras() == null) return null;
        Object candidate = data.getExtras().get("data");
        if (!(candidate instanceof Bitmap)) return null;
        try (OutputStream output = getContentResolver().openOutputStream(mediaRequests.pendingCameraUri)) {
            Bitmap thumbnail = (Bitmap) candidate;
            return output != null && thumbnail.compress(Bitmap.CompressFormat.JPEG, 92, output)
                ? mediaRequests.pendingCameraUri
                : null;
        } catch (IOException | SecurityException ignored) {
            return null;
        }
    }

    /**
     * Gallery and document providers issue temporary URI grants. Copy selected files into
     * app-owned cache storage before handing them to the WebView file input, so that the
     * renderer cannot lose access after the picker closes. This handles multi-select too.
     */
    private Uri[] copySelectedUrisToAppCache(Intent data) {
        if (data == null) return null;
        List<Uri> selectedUris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri copied = copyUriToAppCache(clipData.getItemAt(index).getUri());
                if (copied != null) selectedUris.add(copied);
            }
        } else if (data.getData() != null) {
            Uri copied = copyUriToAppCache(data.getData());
            if (copied != null) selectedUris.add(copied);
        }
        return selectedUris.isEmpty() ? null : selectedUris.toArray(new Uri[0]);
    }

    private Uri copyUriToAppCache(Uri source) {
        if (source == null) return null;
        String mimeType = getContentResolver().getType(source);
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        if (extension == null || extension.trim().isEmpty()) extension = "bin";
        File directory = new File(getCacheDir(), "lecture-imports");
        if (!directory.exists() && !directory.mkdirs()) return null;
        File destination = new File(directory, "upload-" + System.currentTimeMillis() + "-" + Math.random() + "." + extension);

        try (InputStream input = getContentResolver().openInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) return null;
            byte[] buffer = new byte[16 * 1024];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.flush();
            if (destination.length() == 0L) {
                destination.delete();
                return null;
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", destination);
        } catch (IOException | SecurityException exception) {
            if (destination.exists()) destination.delete();
            return null;
        }
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
                fileChooserLauncher.launch(intent);
                return true;
            } catch (ActivityNotFoundException | IllegalArgumentException exception) {
                mediaRequests.pendingFileCallback.onReceiveValue(null);
                mediaRequests.clearFileChooser();
                return false;
            }
        }

        private Intent createFileChooserIntent(FileChooserParams params) {
            Intent picker = params.createIntent();
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!params.isCaptureEnabled()) return picker;

            Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            // Internal cache keeps the capture available to the WebView via FileProvider
            // without requiring broad media or storage permissions.
            File directory = new File(getCacheDir(), "lecture-captures");
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
            return capture;
        }
    }
}
