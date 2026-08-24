package com.koleety.ai.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.database.Cursor;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.MimeTypeMap;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
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

/**
 * KOLEETY native Android shell.
 *
 * File input is deliberately split into two lifecycle-aware contracts:
 * TakePicture handles the camera URI directly and StartActivityForResult handles
 * gallery/documents. This avoids relying on the fragile camera Intent embedded in
 * WebChromeClient's generic chooser.
 */
public class MainActivity extends ComponentActivity {
    private static final int WEB_PERMISSION_REQUEST = 9104;
    private static final int CAMERA_LAUNCH_PERMISSION_REQUEST = 9105;
    private static final int NATIVE_AUDIO_PERMISSION_REQUEST = 9106;
    private static final String STATE_CAMERA_URI = "pending_camera_uri";
    private static final String STATE_FILE_CHOOSER_ACTIVE = "file_chooser_active";

    private WebView webView;
    private LinearLayout loadingPanel;
    private LinearLayout errorPanel;
    private MediaRequestViewModel mediaRequests;
    private ActivityResultLauncher<Uri> cameraCaptureLauncher;
    private ActivityResultLauncher<Intent> filePickerLauncher;
    private MediaRecorder nativeAudioRecorder;
    private File nativeAudioFile;
    private boolean nativeAudioStartPending;

    public static final class MediaRequestViewModel extends ViewModel {
        ValueCallback<Uri[]> pendingFileCallback;
        Uri pendingCameraUri;
        PermissionRequest pendingWebPermissionRequest;
        boolean fileChooserActive;
        boolean cameraLaunchPending;

        void clearFileChooser() {
            pendingFileCallback = null;
            pendingCameraUri = null;
            fileChooserActive = false;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.koleety_webview);
        loadingPanel = findViewById(R.id.loading_panel);
        errorPanel = findViewById(R.id.error_panel);
        mediaRequests = new ViewModelProvider(this).get(MediaRequestViewModel.class);
        registerInputLaunchers();

        Button retryButton = findViewById(R.id.retry_button);
        retryButton.setOnClickListener(view -> reloadHome());
        configureWebView();

        if (savedInstanceState == null) {
            reloadHome();
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void registerInputLaunchers() {
        cameraCaptureLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(),
            this::deliverCameraCaptureResult
        );
        filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> deliverPickerResult(result.getResultCode(), result.getData())
        );
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
        // TTS starts only after a student presses the listen button, but the URL arrives
        // asynchronously; WebView must not classify that as unwanted autoplay.
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportMultipleWindows(false);
        settings.setUserAgentString(settings.getUserAgentString() + " KOLEETYNative/0.2");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true);
        }
        WebView.setWebContentsDebuggingEnabled(false);
        webView.setWebViewClient(new TrustedWebViewClient());
        webView.setWebChromeClient(new KoleetyChromeClient());
        webView.addJavascriptInterface(new KoleetyNativeAudioBridge(), "KoleetyNativeAudio");
    }

    private void reloadHome() {
        errorPanel.setVisibility(View.GONE);
        loadingPanel.setVisibility(View.VISIBLE);
        webView.loadUrl(BuildConfig.KOLEETY_HOME_URL);
    }

    private boolean isTrustedHost(Uri uri) {
        String host = uri == null ? null : uri.getHost();
        if (host == null || !"https".equalsIgnoreCase(uri.getScheme())) return false;
        host = host.toLowerCase(Locale.ROOT);
        return host.equals(BuildConfig.KOLEETY_TRUSTED_HOST)
            || host.equals("koleety.com")
            || host.equals("www.koleety.com")
            || host.endsWith(".manus.im");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        outState.putBoolean(STATE_FILE_CHOOSER_ACTIVE, mediaRequests.fileChooserActive);
        if (mediaRequests.pendingCameraUri != null) {
            outState.putString(STATE_CAMERA_URI, mediaRequests.pendingCameraUri.toString());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        String savedCameraUri = savedInstanceState.getString(STATE_CAMERA_URI);
        if (mediaRequests.pendingCameraUri == null && savedCameraUri != null) {
            mediaRequests.pendingCameraUri = Uri.parse(savedCameraUri);
        }
        mediaRequests.fileChooserActive = mediaRequests.pendingFileCallback != null
            || savedInstanceState.getBoolean(STATE_FILE_CHOOSER_ACTIVE, false);
    }

    private void launchCameraCapture() {
        if (!hasPermission(Manifest.permission.CAMERA)) {
            mediaRequests.cameraLaunchPending = true;
            ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.CAMERA },
                CAMERA_LAUNCH_PERMISSION_REQUEST
            );
            return;
        }
        launchCameraCaptureWithPermission();
    }

    private void launchCameraCaptureWithPermission() {
        Uri outputUri = createCameraOutputUri();
        if (outputUri == null) {
            deliverFileResult(null, "تعذّر تجهيز الكاميرا. حاول مرة أخرى.");
            return;
        }
        mediaRequests.pendingCameraUri = outputUri;
        try {
            cameraCaptureLauncher.launch(outputUri);
        } catch (SecurityException | ActivityNotFoundException | IllegalArgumentException exception) {
            deliverFileResult(null, "تعذّر فتح الكاميرا. تحقق من إذن الكاميرا ثم حاول مرة أخرى.");
        }
    }

    private Uri createCameraOutputUri() {
        File directory = new File(getCacheDir(), "lecture-captures");
        if (!directory.exists() && !directory.mkdirs()) return null;
        try {
            File output = File.createTempFile("lecture-", ".jpg", directory);
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", output);
        } catch (IOException exception) {
            return null;
        }
    }

    private void deliverCameraCaptureResult(Boolean captureSucceeded) {
        Uri[] result = Boolean.TRUE.equals(captureSucceeded) && hasCapturedCameraImage()
            ? new Uri[] { mediaRequests.pendingCameraUri }
            : null;
        deliverFileResult(result, result == null ? "لم تصل صورة من الكاميرا. تحقق من الإذن وحاول مرة أخرى." : null);
    }

    private boolean hasCapturedCameraImage() {
        Uri uri = mediaRequests.pendingCameraUri;
        if (uri == null) return false;
        for (int attempt = 0; attempt < 5; attempt++) {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input != null && input.read() != -1) return true;
            } catch (IOException ignored) {
                // The camera can report success before the final bytes are flushed.
            }
            SystemClock.sleep(150);
        }
        return false;
    }

    private void launchFilePicker(WebChromeClient.FileChooserParams params) {
        Intent picker = params.createIntent();
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        filePickerLauncher.launch(picker);
    }

    private void deliverPickerResult(int resultCode, Intent data) {
        Uri[] result = resultCode == Activity.RESULT_OK ? copySelectedUrisToAppCache(data) : null;
        deliverFileResult(result, result == null ? "تعذّر قراءة الصورة أو الملف المختار. حاول اختيار ملف آخر." : null);
    }

    private void deliverFileResult(Uri[] result, String errorMessage) {
        ValueCallback<Uri[]> callback = mediaRequests.pendingFileCallback;
        if (callback == null) return;
        callback.onReceiveValue(result);
        mediaRequests.clearFileChooser();
        if (errorMessage != null) Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show();
    }

    private Uri[] copySelectedUrisToAppCache(Intent data) {
        if (data == null) return null;
        List<Uri> outputUris = new ArrayList<>();
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int index = 0; index < clipData.getItemCount(); index++) {
                Uri copied = copyUriToAppCache(clipData.getItemAt(index).getUri());
                if (copied != null) outputUris.add(copied);
            }
        } else if (data.getData() != null) {
            Uri copied = copyUriToAppCache(data.getData());
            if (copied != null) outputUris.add(copied);
        }
        return outputUris.isEmpty() ? null : outputUris.toArray(new Uri[0]);
    }

    private Uri copyUriToAppCache(Uri source) {
        if (source == null) return null;
        File directory = new File(getCacheDir(), "lecture-imports");
        if (!directory.exists() && !directory.mkdirs()) return null;
        String displayName = getDisplayName(source);
        String extension = getFileExtension(displayName, getContentResolver().getType(source));
        File target = new File(
            directory,
            "upload-" + System.currentTimeMillis() + "-" + System.nanoTime()
                + (extension.isEmpty() ? ".bin" : "." + extension)
        );
        try (InputStream input = getContentResolver().openInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            if (input == null) return null;
            byte[] buffer = new byte[16 * 1024];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) output.write(buffer, 0, bytesRead);
            output.flush();
            if (target.length() == 0L) {
                target.delete();
                return null;
            }
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", target);
        } catch (IOException | SecurityException exception) {
            if (target.exists()) target.delete();
            return null;
        }
    }

    private String getDisplayName(Uri source) {
        try (Cursor cursor = getContentResolver().query(
            source,
            new String[] { OpenableColumns.DISPLAY_NAME },
            null,
            null,
            null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null) return name;
                }
            }
        } catch (SecurityException ignored) {
            // The extension fallback below keeps the copied file usable.
        }
        return "";
    }

    private String getFileExtension(String displayName, String mimeType) {
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0 && dot < displayName.length() - 1) {
            return displayName.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType);
        return extension == null ? "" : extension.toLowerCase(Locale.ROOT);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_LAUNCH_PERMISSION_REQUEST) {
            boolean launchPending = mediaRequests.cameraLaunchPending;
            mediaRequests.cameraLaunchPending = false;
            if (launchPending && hasPermission(Manifest.permission.CAMERA)) {
                launchCameraCaptureWithPermission();
            } else if (launchPending) {
                deliverFileResult(null, "يلزم السماح بإذن الكاميرا لالتقاط الصورة.");
            }
            return;
        }
        if (requestCode == NATIVE_AUDIO_PERMISSION_REQUEST) {
            boolean startPending = nativeAudioStartPending;
            nativeAudioStartPending = false;
            if (startPending && hasPermission(Manifest.permission.RECORD_AUDIO)) {
                startNativeAudioRecording();
            } else if (startPending) {
                dispatchNativeAudioError("يلزم السماح بإذن الميكروفون للتسجيل.");
            }
            return;
        }
        if (requestCode != WEB_PERMISSION_REQUEST) return;
        PermissionRequest request = mediaRequests.pendingWebPermissionRequest;
        mediaRequests.pendingWebPermissionRequest = null;
        if (request != null) grantAllowedWebResources(request);
    }

    private boolean hasPermission(String permission) {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void grantAllowedWebResources(PermissionRequest request) {
        List<String> granted = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && hasPermission(Manifest.permission.RECORD_AUDIO)) granted.add(resource);
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && hasPermission(Manifest.permission.CAMERA)) granted.add(resource);
        }
        if (granted.isEmpty()) request.deny();
        else request.grant(granted.toArray(new String[0]));
    }

    private void launchNativeAudioRecording() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            nativeAudioStartPending = true;
            ActivityCompat.requestPermissions(
                this,
                new String[] { Manifest.permission.RECORD_AUDIO },
                NATIVE_AUDIO_PERMISSION_REQUEST
            );
            return;
        }
        startNativeAudioRecording();
    }

    private void startNativeAudioRecording() {
        stopNativeAudioRecording(false);
        File directory = new File(getCacheDir(), "lecture-audio");
        if (!directory.exists() && !directory.mkdirs()) {
            dispatchNativeAudioError("تعذّر تجهيز ملف التسجيل.");
            return;
        }
        try {
            nativeAudioFile = File.createTempFile("lecture-", ".m4a", directory);
            nativeAudioRecorder = new MediaRecorder();
            nativeAudioRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            nativeAudioRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            nativeAudioRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            nativeAudioRecorder.setAudioEncodingBitRate(128000);
            nativeAudioRecorder.setAudioSamplingRate(44100);
            nativeAudioRecorder.setOutputFile(nativeAudioFile.getAbsolutePath());
            nativeAudioRecorder.prepare();
            nativeAudioRecorder.start();
            Toast.makeText(this, "بدأ تسجيل الصوت", Toast.LENGTH_SHORT).show();
        } catch (IOException | IllegalStateException error) {
            stopNativeAudioRecording(false);
            dispatchNativeAudioError("تعذّر بدء التسجيل الصوتي.");
        }
    }

    private void stopNativeAudioRecording(boolean sendToWeb) {
        MediaRecorder recorder = nativeAudioRecorder;
        nativeAudioRecorder = null;
        if (recorder == null) return;
        try {
            recorder.stop();
        } catch (RuntimeException ignored) {
            // Android throws when a recording is stopped before any samples exist.
        } finally {
            recorder.reset();
            recorder.release();
        }
        if (!sendToWeb || nativeAudioFile == null || nativeAudioFile.length() == 0L) {
            if (sendToWeb) dispatchNativeAudioError("لم يُسجَّل صوت صالح. حاول التسجيل لثانيتين على الأقل.");
            return;
        }
        try (InputStream input = new java.io.FileInputStream(nativeAudioFile)) {
            byte[] bytes = new byte[(int) nativeAudioFile.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != bytes.length) throw new IOException("incomplete audio read");
            String base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('koleety-native-audio',{detail:{base64:'"
                    + base64 + "',mimeType:'audio/mp4'}}));",
                null
            );
        } catch (IOException error) {
            dispatchNativeAudioError("تعذّر تسليم التسجيل إلى المحاضرة.");
        }
    }

    private void dispatchNativeAudioError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        webView.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('koleety-native-audio',{detail:{error:true}}));",
            null
        );
    }

    private final class KoleetyNativeAudioBridge {
        @JavascriptInterface public boolean isAvailable() { return true; }
        @JavascriptInterface public void start() { runOnUiThread(MainActivity.this::launchNativeAudioRecording); }
        @JavascriptInterface public void stop() { runOnUiThread(() -> stopNativeAudioRecording(true)); }
    }

    private final class TrustedWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!request.isForMainFrame() || isTrustedHost(uri)) return false;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (ActivityNotFoundException ignored) {
                errorPanel.setVisibility(View.VISIBLE);
            }
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
            loadingPanel.setVisibility(View.VISIBLE);
            errorPanel.setVisibility(View.GONE);
        }

        @Override public void onPageFinished(WebView view, String url) {
            loadingPanel.setVisibility(View.GONE);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                loadingPanel.setVisibility(View.GONE);
                errorPanel.setVisibility(View.VISIBLE);
            }
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                loadingPanel.setVisibility(View.GONE);
                errorPanel.setVisibility(View.VISIBLE);
            }
        }

        @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
            handler.cancel();
            loadingPanel.setVisibility(View.GONE);
            errorPanel.setVisibility(View.VISIBLE);
        }
    }

    private final class KoleetyChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (mediaRequests.pendingFileCallback != null) mediaRequests.pendingFileCallback.onReceiveValue(null);
            mediaRequests.pendingFileCallback = callback;
            mediaRequests.pendingCameraUri = null;
            mediaRequests.fileChooserActive = true;
            try {
                if (params.isCaptureEnabled()) launchCameraCapture();
                else launchFilePicker(params);
                return true;
            } catch (ActivityNotFoundException | IllegalArgumentException exception) {
                deliverFileResult(null, "لا يتوفر تطبيق مناسب لالتقاط الصورة أو اختيار الملف.");
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            runOnUiThread(() -> {
                if (!isTrustedHost(request.getOrigin())) {
                    request.deny();
                    return;
                }
                List<String> missing = new ArrayList<>();
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource) && !hasPermission(Manifest.permission.RECORD_AUDIO)) missing.add(Manifest.permission.RECORD_AUDIO);
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource) && !hasPermission(Manifest.permission.CAMERA)) missing.add(Manifest.permission.CAMERA);
                }
                if (missing.isEmpty()) {
                    grantAllowedWebResources(request);
                    return;
                }
                if (mediaRequests.pendingWebPermissionRequest != null) mediaRequests.pendingWebPermissionRequest.deny();
                mediaRequests.pendingWebPermissionRequest = request;
                ActivityCompat.requestPermissions(MainActivity.this, missing.toArray(new String[0]), WEB_PERMISSION_REQUEST);
            });
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (mediaRequests.pendingWebPermissionRequest == request) mediaRequests.pendingWebPermissionRequest = null;
        }
    }
}
