package ir.anbar.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView web;

    private final ActivityResultLauncher<ScanOptions> scanner =
            registerForActivityResult(new ScanContract(), result -> {
                String c = result.getContents();
                callJs("onNativeScan", c == null ? "" : c);
            });

    private final ActivityResultLauncher<String> filePicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null) return;
                try (InputStream in = getContentResolver().openInputStream(uri);
                     BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                    callJs("onNativeFile", sb.toString());
                } catch (Exception e) {
                    callJs("onNativeFile", "");
                }
            });

    // ---------- خواندن متن (اسم برند) از عکس ----------
    private Uri photoUri;

    private final ActivityResultLauncher<Uri> takePhoto =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), ok -> {
                if (!ok || photoUri == null) { callJs("onNativeOcr", "[]"); return; }
                try {
                    InputImage img = InputImage.fromFilePath(this, photoUri);
                    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(img)
                            .addOnSuccessListener(t -> {
                                // بزرگ‌ترین نوشته‌ها (معمولاً اسم برند) اول بیایند
                                List<Object[]> lines = new ArrayList<>();
                                for (Text.TextBlock b : t.getTextBlocks())
                                    for (Text.Line l : b.getLines()) {
                                        Rect r = l.getBoundingBox();
                                        lines.add(new Object[]{l.getText(), r == null ? 0 : r.height()});
                                    }
                                lines.sort((a, c) -> Integer.compare((int) c[1], (int) a[1]));
                                JSONArray arr = new JSONArray();
                                for (Object[] o : lines) arr.put(o[0]);
                                callJs("onNativeOcr", arr.toString());
                            })
                            .addOnFailureListener(e -> callJs("onNativeOcr", "[]"));
                } catch (Exception e) {
                    callJs("onNativeOcr", "[]");
                }
            });

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) launchPhoto(); else callJs("onNativeError", "اجازه‌ی دوربین داده نشد");
            });

    private void launchPhoto() {
        try {
            File f = new File(getCacheDir(), "ocr.jpg");
            photoUri = FileProvider.getUriForFile(this, "ir.anbar.app.files", f);
            takePhoto.launch(photoUri);
        } catch (Exception e) {
            callJs("onNativeOcr", "[]");
        }
    }

    private void callJs(String fn, String arg) {
        String js = "window." + fn + "&&window." + fn + "(" + JSONObject.quote(arg) + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#f3f0e8"));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                web.evaluateJavascript("(window.onBack&&window.onBack())?'y':'n'", v -> {
                    if (v == null || !v.contains("y")) finish();
                });
            }
        });
    }

    private class Bridge {
        @JavascriptInterface
        public void scan() {
            runOnUiThread(() -> {
                ScanOptions o = new ScanOptions();
                o.setPrompt("بارکد را داخل کادر بگیرید");
                o.setBeepEnabled(true);
                o.setOrientationLocked(false);
                scanner.launch(o);
            });
        }

        @JavascriptInterface
        public void share(String text, String fileName) {
            runOnUiThread(() -> {
                try {
                    File f = new File(getCacheDir(), fileName);
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        if (fileName.endsWith(".csv")) out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                    Uri uri = FileProvider.getUriForFile(MainActivity.this, "ir.anbar.app.files", f);
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(fileName.endsWith(".csv") ? "text/csv" : "application/json");
                    i.putExtra(Intent.EXTRA_STREAM, uri);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(i, "ارسال فایل"));
                } catch (Exception e) {
                    callJs("onNativeError", "ارسال فایل انجام نشد");
                }
            });
        }

        @JavascriptInterface
        public void ocr() {
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchPhoto();
                else cameraPermission.launch(Manifest.permission.CAMERA);
            });
        }

        @JavascriptInterface
        public void pickFile() {
            runOnUiThread(() -> filePicker.launch("*/*"));
        }
    }
}
