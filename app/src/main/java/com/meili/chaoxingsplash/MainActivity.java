package com.meili.chaoxingsplash;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 模块说明页（LSPosed 里点“打开”看到的就是这个页面），同时提供换图入口。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CxSplash";
    private static final int REQ_PICK = 1001;
    private static final int MAX_EDGE = 2400;

    private ImageView preview;
    private TextView source;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        preview = findViewById(R.id.preview);
        source = findViewById(R.id.source);

        TextView device = findViewById(R.id.device);
        device.setText(getString(R.string.device_fmt,
                Build.MANUFACTURER + " " + Build.MODEL, Build.VERSION.RELEASE));

        TextView version = findViewById(R.id.version);
        version.setText(getString(R.string.version_fmt, BuildConfig.VERSION_NAME));

        findViewById(R.id.btn_pick).setOnClickListener(v -> pickImage());
        findViewById(R.id.btn_reset).setOnClickListener(v -> resetCustom());

        refreshPreview();
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(Intent.createChooser(intent, getString(R.string.pick_title)), REQ_PICK);
        } catch (Throwable t) {
            toast(getString(R.string.pick_failed));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        new Thread(() -> {
            String error = saveImage(uri);
            runOnUiThread(() -> {
                if (error == null) {
                    toast(getString(R.string.saved_ok));
                    refreshPreview();
                } else {
                    toast(getString(R.string.saved_failed, error));
                }
            });
        }).start();
    }

    /** 把用户选的图缩放并转成 JPEG 存进模块可被其他进程读取的目录；返回 null 表示成功 */
    private String saveImage(Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            // 1. 先读尺寸，算出采样率，避免超大图 OOM
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            closeQuietly(in);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "无法识别图片";

            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > MAX_EDGE) sample *= 2;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            in = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
            closeQuietly(in);
            if (bitmap == null) return "解码失败";

            // 2. 写文件
            File target = SplashStore.primary();
            File dir = target.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                return "无法创建目录 " + dir;
            }
            out = new FileOutputStream(target);
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                return "压缩失败";
            }
            out.flush();
            closeQuietly(out);
            bitmap.recycle();
            Log.i(TAG, "custom splash saved: " + target + " (" + target.length() + " bytes)");
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "save custom splash failed", t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    private void resetCustom() {
        boolean removed = SplashStore.clearCustom();
        toast(getString(removed ? R.string.reset_ok : R.string.reset_none));
        refreshPreview();
    }

    private void refreshPreview() {
        Bitmap bmp = SplashStore.loadCustom();
        boolean custom = bmp != null;
        if (!custom) {
            try (InputStream in = getAssets().open("splash.jpg")) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Throwable ignored) {
            }
        }
        if (bmp != null) preview.setImageBitmap(bmp);
        File file = SplashStore.existing();
        if (custom && file != null) {
            source.setText(getString(R.string.source_custom,
                    file.length() / 1024, bmp != null ? bmp.getWidth() : 0, bmp != null ? bmp.getHeight() : 0));
        } else {
            source.setText(R.string.source_builtin);
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
