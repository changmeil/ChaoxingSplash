package com.meili.chaoxingsplash;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块说明页（LSPosed 里点“打开”看到的就是这个页面），同时提供换图入口。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CxSplash";
    private static final int REQ_PICK = 1001;
    /** 落盘图的长边上限（宿主能直接读文件的场景，保留更高画质） */
    private static final int MAX_EDGE_FILE = 2400;
    /** 走 RemotePreferences 的长边上限（要过 binder，控制体积） */
    private static final int MAX_EDGE_PREFS = 1280;

    /** LSPosed 投递进来的通道；null 表示还没绑定（模块没启用 / App 是开机后头一次打开） */
    private static volatile XposedService remote;

    private ImageView preview;
    private TextView source;
    /** 最近一次保存是否成功写进了 LSPosed 远程配置 */
    private boolean lastRemoteOk;

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

        bindRemote();

        refreshPreview();
    }

    /**
     * 等待 LSPosed 把 IXposedService 投递进来（走 XposedProvider 的 call()）。
     * 绑定成功后才能用 getRemotePreferences() 写配置——那份数据存在 LSPosed 库里，
     * Hook 进程用 XposedInterface#getRemotePreferences 读同一份，不需要任何文件权限。
     */
    private void bindRemote() {
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                remote = service;
                try {
                    Log.i(TAG, "xposed service bound: " + service.getFrameworkName()
                            + " " + service.getFrameworkVersion());
                } catch (Throwable ignored) {
                }
            }

            @Override
            public void onServiceDied(XposedService service) {
                remote = null;
                Log.w(TAG, "xposed service died");
            }
        });
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
                    toast(getString(lastRemoteOk ? R.string.saved_ok : R.string.saved_local_only));
                    refreshPreview();
                } else {
                    toast(getString(R.string.saved_failed, error));
                }
            });
        }).start();
    }

    /**
     * 保存两张：一张落盘（给能直接读文件的宿主），一张压缩后放进 RemotePreferences
     * （给读不到外部存储的宿主，例如学习通进程）。返回 null 表示成功。
     */
    private String saveImage(Uri uri) {
        InputStream in = null;
        OutputStream out = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            closeQuietly(in);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "无法识别图片";

            Bitmap bitmap = decodeSampled(uri, MAX_EDGE_FILE);
            if (bitmap == null) return "解码失败";

            // 1. 落盘：外部媒体目录，宿主进程可能读得到
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
            out = null;
            // 试着把文件放成全局可读，看 FUSE 是否按模式位放行（多数 RH 上仍然无效，仅作兜底）
            //noinspection ResultOfMethodCallIgnored
            target.setReadable(true, false);
            Log.i(TAG, "custom splash saved: " + target + " (" + target.length() + " bytes)");


            // 2. LSPosed 远程配置：Hook 进程用 getRemotePreferences 读，绕开存储权限/作用域限制
            lastRemoteOk = storeToPrefs(bitmap);
            bitmap.recycle();
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "save custom splash failed", t);
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * 写一份进 LSPosed 的远程配置（Hook 进程用 getRemotePreferences 读，学习通/系统界面/
     * system_server 都能拿到，不受分区存储和 SELinux 影响）。返回是否写成功。
     */
    private boolean storeToPrefs(Bitmap bitmap) {
        XposedService svc = remote;
        if (svc == null) {
            Log.w(TAG, "xposed service not bound, remote prefs skipped");
            return false;
        }
        try {
            Bitmap small = bitmap;
            int max = Math.max(bitmap.getWidth(), bitmap.getHeight());
            if (max > MAX_EDGE_PREFS) {
                float s = (float) MAX_EDGE_PREFS / max;
                small = Bitmap.createScaledBitmap(bitmap,
                        Math.max(1, Math.round(bitmap.getWidth() * s)),
                        Math.max(1, Math.round(bitmap.getHeight() * s)), true);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            small.compress(Bitmap.CompressFormat.JPEG, 82, bos);
            String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
            SharedPreferences sp = svc.getRemotePreferences(SplashStore.PREFS);
            // commit() 是同步的，写完 Hook 进程下一次读就能拿到
            boolean ok = sp.edit()
                    .putString(SplashStore.KEY_B64, b64)
                    .putLong(SplashStore.KEY_TIME, System.currentTimeMillis())
                    .commit();
            Log.i(TAG, "custom splash -> remote prefs: " + b64.length() + " chars, ok=" + ok);
            if (small != bitmap) small.recycle();
            return ok;
        } catch (Throwable t) {
            // 过大（binder 限制）时只是这条通道不可用，落盘那份仍有效
            Log.w(TAG, "store to remote preferences failed", t);
            return false;
        }
    }

    private Bitmap decodeSampled(Uri uri, int maxEdge) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        int max = Math.max(bounds.outWidth, bounds.outHeight);
        while (max / sample > maxEdge) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private void resetCustom() {
        boolean removedFile = SplashStore.clearCustom();
        boolean removedPref = false;
        XposedService svc = remote;
        if (svc != null) {
            try {
                svc.deleteRemotePreferences(SplashStore.PREFS);
                removedPref = true;
            } catch (Throwable t) {
                Log.w(TAG, "delete remote preferences failed", t);
            }
        }
        toast(getString(removedFile || removedPref ? R.string.reset_ok : R.string.reset_none));
        refreshPreview();
    }

    private void refreshPreview() {
        Bitmap bmp = SplashStore.loadCustom();
        if (bmp == null) bmp = loadPrefsPreview();
        boolean custom = bmp != null;
        if (!custom) {
            try (InputStream in = getAssets().open("splash.jpg")) {
                bmp = BitmapFactory.decodeStream(in);
            } catch (Throwable ignored) {
            }
        }
        if (bmp != null) preview.setImageBitmap(bmp);
        File file = SplashStore.existing();
        if (custom) {
            source.setText(getString(R.string.source_custom,
                    file != null ? file.length() / 1024 : 0,
                    bmp != null ? bmp.getWidth() : 0,
                    bmp != null ? bmp.getHeight() : 0));
        } else {
            source.setText(R.string.source_builtin);
        }
    }

    /**
     * 读回当前生效的配置，用来在页面上显示“自定义 / 内置”。
     * 注意：这里读的是模块自己的本地副本，和 Hook 进程读的远程配置内容一致。
     */
    private Bitmap loadPrefsPreview() {
        XposedService svc = remote;
        if (svc == null) return null;
        try {
            String b64 = svc.getRemotePreferences(SplashStore.PREFS)
                    .getString(SplashStore.KEY_B64, null);
            if (b64 == null || b64.isEmpty()) return null;
            byte[] raw = Base64.decode(b64, Base64.NO_WRAP);
            return BitmapFactory.decodeByteArray(raw, 0, raw.length);
        } catch (Throwable t) {
            return null;
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
