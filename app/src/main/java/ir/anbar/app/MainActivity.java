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
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;
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
                if (faMode) new Thread(this::runTess).start(); else new Thread(this::runOcr).start();
            });

    // متن عکس دو بار خوانده می‌شود: یک بار با کیفیت کامل (برای نوشته‌های ریز)
    // و یک بار کوچک‌شده (برای عددها و نوشته‌های خیلی درشت مثل 50K) و نتیجه‌ها ترکیب می‌شوند
    private boolean faMode = false;

    // خواندن متن فارسی با Tesseract (برای لیست‌های فارسی)
    private void runTess() {
        JSONArray arr = new JSONArray();
        try {
            File dir = new File(getFilesDir(), "tess");
            File td = new File(dir, "tessdata");
            td.mkdirs();
            File f = new File(td, "fas.traineddata");
            if (!f.exists() || f.length() < 1000) {
                try (InputStream in = getAssets().open("tessdata/fas.traineddata");
                     FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
            }
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(photoUri)) {
                android.media.ExifInterface ex = new android.media.ExifInterface(in);
                int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            } catch (Exception ignored) { }
            android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(photoUri)) { android.graphics.BitmapFactory.decodeStream(in, null, bo); }
            int full = Math.max(bo.outWidth, bo.outHeight), sample = 1;
            while (full / (sample * 2) >= 2000) sample *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = sample;
            android.graphics.Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(photoUri)) { bmp = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
            if (rot != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(rot);
                android.graphics.Bitmap r = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                bmp.recycle();
                bmp = r;
            }
            com.googlecode.tesseract.android.TessBaseAPI tess = new com.googlecode.tesseract.android.TessBaseAPI();
            if (!tess.init(dir.getAbsolutePath(), "fas")) throw new Exception("init");
            tess.setPageSegMode(com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_AUTO);
            tess.setImage(bmp);
            String txt = tess.getUTF8Text();
            tess.recycle();
            bmp.recycle();
            if (txt != null) for (String line : txt.split("\n")) if (!line.trim().isEmpty()) arr.put(line.trim());
        } catch (Throwable e) {
            callJs("onNativeError", "خواندن متن فارسی انجام نشد");
        }
        callJs("onNativeOcr", arr.toString());
    }

    private void runOcr() {
        JSONArray arr = new JSONArray();
        try {
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(photoUri)) {
                android.media.ExifInterface ex = new android.media.ExifInterface(in);
                int o = ex.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1);
                rot = o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            } catch (Exception ignored) { }
            android.graphics.BitmapFactory.Options bo = new android.graphics.BitmapFactory.Options();
            bo.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(photoUri)) { android.graphics.BitmapFactory.decodeStream(in, null, bo); }
            int full = Math.max(bo.outWidth, bo.outHeight);
            com.google.mlkit.vision.text.TextRecognizer rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
            int[] targets = {2400, 1000};
            int pass = 0;
            for (int target : targets) {
                int sample = 1;
                while (full / (sample * 2) >= target) sample *= 2;
                android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
                o2.inSampleSize = sample;
                android.graphics.Bitmap bmp;
                try (InputStream in = getContentResolver().openInputStream(photoUri)) { bmp = android.graphics.BitmapFactory.decodeStream(in, null, o2); }
                if (bmp == null) continue;
                Text t = com.google.android.gms.tasks.Tasks.await(rec.process(InputImage.fromBitmap(bmp, rot)));
                int bi = 0;
                for (Text.TextBlock b : t.getTextBlocks()) {
                    for (Text.Line l : b.getLines()) {
                        try {
                            Rect r = l.getBoundingBox();
                            JSONObject o = new JSONObject();
                            o.put("t", l.getText());
                            o.put("h", r == null ? 0 : r.height() * sample);
                            o.put("x", r == null ? 0 : r.left * sample);
                            o.put("y", r == null ? 0 : r.top * sample);
                            o.put("b", pass * 1000 + bi);
                            o.put("p", pass);
                            float c = l.getConfidence();
                            o.put("c", Float.isNaN(c) ? 1 : c);
                            arr.put(o);
                        } catch (Exception ignored) { }
                    }
                    bi++;
                }
                bmp.recycle();
                pass++;
            }
        } catch (Exception e) {
            // اگر روش بالا نشد، روش ساده
            try {
                Text t = com.google.android.gms.tasks.Tasks.await(TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        .process(InputImage.fromFilePath(this, photoUri)));
                for (Text.TextBlock b : t.getTextBlocks()) for (Text.Line l : b.getLines()) arr.put(l.getText());
            } catch (Exception ignored) { }
        }
        callJs("onNativeOcr", arr.toString());
    }

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

    private boolean gmsFailed = false;

    // اسکنر پشتیبان (اگر سرویس گوگل در دسترس نبود)
    private void zxingScan() {
        ScanOptions o = new ScanOptions();
        o.setPrompt("بارکد را داخل کادر بگیرید — کمی دورتر نگه دارید تا فوکوس شود");
        o.setBeepEnabled(true);
        o.setOrientationLocked(false);
        o.setCaptureActivity(ScanActivity.class);
        o.setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES);
        o.addExtra("TRY_HARDER", true);
        scanner.launch(o);
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
                if (gmsFailed) { zxingScan(); return; }
                try {
                    // اسکنر گوگل: فوکوس و زوم خودکار، خواندن سریع‌تر
                    GmsBarcodeScannerOptions o = new GmsBarcodeScannerOptions.Builder()
                            .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                            .enableAutoZoom()
                            .build();
                    GmsBarcodeScanning.getClient(MainActivity.this, o).startScan()
                            .addOnSuccessListener(b -> callJs("onNativeScan", b.getRawValue() == null ? "" : b.getRawValue()))
                            .addOnCanceledListener(() -> callJs("onNativeScan", ""))
                            .addOnFailureListener(e -> { gmsFailed = true; zxingScan(); });
                } catch (Exception e) {
                    gmsFailed = true;
                    zxingScan();
                }
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
        public void ocrFa() {
            faMode = true;
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchPhoto();
                else cameraPermission.launch(Manifest.permission.CAMERA);
            });
        }

        @JavascriptInterface
        public void ocr() {
            faMode = false;
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
