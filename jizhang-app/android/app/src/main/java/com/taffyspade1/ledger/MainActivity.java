package com.taffyspade1.ledger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 安心记账 · Android 壳
 *
 * 隐私设计：
 * 1. AndroidManifest.xml 中不声明 INTERNET 权限，并用 tools:node="remove"
 *    防止第三方库在清单合并时把 INTERNET 带进来。
 * 2. 页面通过 WebViewAssetLoader 映射到 https://appassets.androidplatform.net/，
 *    由本机 assets 直接响应，请求不会出设备，同时让 localStorage 拥有稳定 origin。
 * 3. allowBackup=false，避免账本被 adb backup 带走。
 * 4. 截图通过系统文件选择器一次性多选，图片只在 WebView 内存中出现；
 *    确认入账后即释放，不落盘、不上传。
 * 5. 备份文件通过系统「存储访问框架」由用户亲自选择保存位置 / 选择要恢复的文件，
 *    App 不主动写公共目录、不申请存储权限。
 * 6. 文字识别使用 ML Kit 的 bundled 中文模型，模型随 APK 安装，
 *    全程在设备本地推理，不依赖 Google Play 服务，不需要联网。
 */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1001;
    private static final int REQ_EXPORT = 1002;
    private static final int REQ_IMPORT = 1003;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private volatile String pendingExport;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setMediaPlaybackRequiresUserGesture(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent;
                try {
                    intent = params.createIntent();
                } catch (Exception e) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("image/*");
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                try {
                    startActivityForResult(
                            Intent.createChooser(intent, "选择账单截图（可一次选多张）"), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }
        });

        // 页面只来自本机 assets，不加载任何远程内容，因此暴露这两个桥接是安全的。
        web.addJavascriptInterface(new BackupBridge(), "AndroidBackup");
        web.addJavascriptInterface(new OcrBridge(), "AndroidOcr");

        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    /**
     * 端侧中文文字识别桥接。
     * 页面先把截图压缩成 JPEG 的 base64，再调用这里；识别结果按视觉行回传，
     * 由页面的 parseBillRows() 解析成待确认的收支条目。
     */
    private class OcrBridge {

        @JavascriptInterface
        public void recognize(String cardId, String base64) {
            byte[] bytes;
            try {
                bytes = Base64.decode(base64, Base64.DEFAULT);
            } catch (Exception e) {
                deliverOcr(cardId, "[]");
                return;
            }
            final Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                deliverOcr(cardId, "[]");
                return;
            }

            final TextRecognizer recognizer = TextRecognition.getClient(
                    new ChineseTextRecognizerOptions.Builder().build());
            final InputImage image = InputImage.fromBitmap(bmp, 0);

            recognizer.process(image)
                    .addOnSuccessListener(new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text text) {
                            deliverOcr(cardId, rowsToJson(text));
                            releaseOcr(recognizer, bmp);
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            deliverOcr(cardId, "[]");
                            releaseOcr(recognizer, bmp);
                        }
                    });
        }
    }

    private void releaseOcr(TextRecognizer recognizer, Bitmap bmp) {
        try { recognizer.close(); } catch (Exception ignored) { }
        try { bmp.recycle(); } catch (Exception ignored) { }
    }

    /** 把 ML Kit 的 Line 按纵坐标聚类成「视觉行」，方便页面把商户名和金额对上。 */
    private String rowsToJson(Text text) {
        List<Text.Line> lines = new ArrayList<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            lines.addAll(block.getLines());
        }
        Collections.sort(lines, new Comparator<Text.Line>() {
            @Override
            public int compare(Text.Line a, Text.Line b) {
                Rect ra = a.getBoundingBox();
                Rect rb = b.getBoundingBox();
                int ya = ra == null ? 0 : ra.top;
                int yb = rb == null ? 0 : rb.top;
                return Integer.compare(ya, yb);
            }
        });

        JSONArray rows = new JSONArray();
        int curY = Integer.MIN_VALUE;
        StringBuilder sb = new StringBuilder();
        for (Text.Line line : lines) {
            Rect r = line.getBoundingBox();
            int y = r == null ? curY : r.top;
            String t = line.getText() == null ? "" : line.getText().trim();
            if (t.isEmpty()) continue;
            if (curY == Integer.MIN_VALUE || Math.abs(y - curY) <= 18) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(t);
                curY = y;
            } else {
                rows.put(sb.toString());
                sb = new StringBuilder(t);
                curY = y;
            }
        }
        if (sb.length() > 0) rows.put(sb.toString());
        return rows.toString();
    }

    private void deliverOcr(String cardId, String rowsJson) {
        final String js = "window.onOcrResult && window.onOcrResult("
                + JSONObject.quote(String.valueOf(cardId)) + ","
                + JSONObject.quote(rowsJson) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    /**
     * 导出 / 导入备份的原生桥接。
     * JavascriptInterface 方法运行在 WebView 的后台线程，启动 Activity 必须切回主线程。
     */
    private class BackupBridge {

        @JavascriptInterface
        public void exportBackup(String json) {
            pendingExport = json;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/json");
                    intent.putExtra(Intent.EXTRA_TITLE, "安心记账-备份-"
                            + new SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(new Date())
                            + ".json");
                    try {
                        startActivityForResult(intent, REQ_EXPORT);
                    } catch (Exception e) {
                        pendingExport = null;
                    }
                }
            });
        }

        @JavascriptInterface
        public void importBackup() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    try {
                        startActivityForResult(intent, REQ_IMPORT);
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_EXPORT) {
            handleExportResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_IMPORT) {
            handleImportResult(resultCode, data);
            return;
        }
        if (requestCode != REQ_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null && clip.getItemCount() > 0) {
                results = new Uri[clip.getItemCount()];
                for (int i = 0; i < clip.getItemCount(); i++) {
                    results[i] = clip.getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    private void handleExportResult(int resultCode, Intent data) {
        String content = pendingExport;
        pendingExport = null;
        if (resultCode != RESULT_OK || data == null || data.getData() == null || content == null) {
            return;
        }
        try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
            if (os != null) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private void handleImportResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String content = readUri(data.getData());
        if (content == null) return;
        final String js = "window.onBackupLoaded && window.onBackupLoaded("
                + JSONObject.quote(content) + ")";
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }

    private String readUri(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
