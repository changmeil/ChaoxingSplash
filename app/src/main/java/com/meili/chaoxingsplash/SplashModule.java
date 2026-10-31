package com.meili.chaoxingsplash;

import android.app.Activity;
import android.content.res.Resources;
import android.util.TypedValue;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * 学习通（com.chaoxing.mobile）开屏页图片替换模块。
 *
 * <p>工作方式：
 * <ol>
 *   <li>Hook {@code Resources#loadDrawable(TypedValue, int, int, Theme)}，当被加载的 drawable
 *       是 {@code drawable/splash} / {@code drawable/splash_1080} 时，直接返回模块内置的图片；</li>
 *   <li>Hook {@code Activity#onCreate}，对 Splash 页面再把 {@code id/iv_splash} 的 ImageView
 *       强制换成模块内置图片。</li>
 * </ol>
 * 显示方式为「等比缩放 + 居中留白」（fit-center）：图片完整可见、不裁切、不变形，
 * 四周用图片四角颜色（不一致时用白色）填充。
 * 模块使用 libxposed API 102（LSPosed 2.x），不依赖旧版 de.robv.android.xposed。
 */
public class SplashModule extends XposedModule {

    static final String TAG = "CxSplash";
    /** 目标应用包名 */
    static final String PKG = "com.chaoxing.mobile";
    /** HyperOS 上渲染启动窗口首帧的系统进程 */
    static final String SYSTEMUI = "com.android.systemui";
    /** 开屏页用到的 drawable 资源名（不同版本可能用其中之一） */
    private static final String[] SPLASH_DRAWABLES = {"splash", "splash_1080"};
    /** 模块 APK 内置的替换图 */
    private static final String MODULE_ASSET = "assets/splash.jpg";
    /**
     * 留白底色：{@code true} 取图片四角颜色（四角不一致时退回白色），
     * {@code false} 固定白色。
     */
    private static final boolean MARGIN_BLEND_CORNERS = true;

    private final Object lock = new Object();
    private Bitmap bitmap;
    private boolean bitmapLoaded;
    /** 自定义图的文件指纹，变了就重新解码（换图后不必重启模块所在进程之外的东西） */
    private String bitmapStamp;
    private int[] splashIds;
    private String[] splashNames;
    private boolean idsResolved;
    private int resolveAttempts;
    private boolean installed;
    private boolean systemInstalled;
    /** system_server 里对 “资源 id 是否要替换” 的判定缓存。
     *  键是 {@code Resources 的 identityHashCode << 32 | resId}——不同包的 Resources 都会用 0x7f 段，
     *  只按 resId 做键会把别的包的同号资源判成同一个。 */
    private final HashMap<Long, Boolean> systemDecisions = new HashMap<>();
    /** 诊断用：system_server 里已打印的学习通资源探测日志条数 */
    private int systemProbeLogs;
    /** 诊断用：system_server 里已替换次数 */
    private int systemReplaceCount;
    /** 诊断用：当前宿主进程名（system_server / com.android.systemui） */
    private String extName = "system";

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        String process = param.getProcessName();
        boolean isSystem = false;
        try {
            isSystem = param.isSystemServer();
        } catch (Throwable ignored) {
        }
        log(Log.INFO, TAG, "module loaded: process=" + process + ", systemServer=" + isSystem
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + ", api=" + getApiVersion());
        if (isSystem || "android".equals(process) || "system_server".equals(process)) {
            installForSystemServer();
            return;
        }
        // HyperOS：启动窗口（start window）的 ViewRootImpl 跑在 SystemUI 进程里
        // （日志 "Splash Screen <pkg> use first frame acceleration" 的来源），
        // 所以 SystemUI 也必须挂同一套资源替换，否则第一帧仍是原图。
        if (SYSTEMUI.equals(process)) {
            extName = SYSTEMUI;
            installForSystemServer();
            return;
        }
        if (process == null || !process.startsWith(PKG)) {
            return; // 只处理学习通自己的进程（主进程以及 :push 等子进程）
        }
        install();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!PKG.equals(param.getPackageName())) return;
        install();
    }

    /**
     * system_server 的标准入口：这个回调在关键服务启动前触发，
     * 早于任何 starting window 的创建，是挂系统侧 Hook 的正确时机。
     */
    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        log(Log.INFO, TAG, "[system] onSystemServerStarting");
        installForSystemServer();
    }

    // ------------------------------------------------------------------- hooks

    private synchronized void install() {
        if (installed) return;
        installed = true;
        installedAt = System.currentTimeMillis();
        try {
            hookLoadDrawable();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Resources#loadDrawable failed", t);
        }
        try {
            hookActivityOnCreate();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook Activity#onCreate failed", t);
        }
    }

    /**
     * Hook 资源加载：所有 {背景 / src / srcCompat} 最终都会走到 {@code Resources#loadDrawable}。
     */
    private void hookLoadDrawable() {
        int count = hookLoadDrawableMethods(false);
        if (count == 0) { // 兜底：签名变化时改 hook 公开方法
            hookPublicGetter("getDrawable", int.class);
            hookPublicGetter("getDrawableForDensity", int.class, int.class);
        }
    }

    /**
     * Android 12+ 的系统启动画面（starting window）由 system_server 用目标应用的
     * {@code windowBackground} / {@code windowSplashScreenBackground} 绘制，早于应用进程启动，
     * 应用侧的 hook 完全碰不到它。这里在系统进程里挂同一套资源替换，把这约 0.5 秒也换成自定义图。
     */
    private synchronized void installForSystemServer() {
        if (systemInstalled) return;
        systemInstalled = true;
        try {
            int count = hookLoadDrawableMethods(true);
            log(Log.INFO, TAG, "[system] hooked " + count + " loadDrawable method(s)");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "[system] hook failed", t);
        }
    }

    /**
     * @param systemServer true = 运行在 system_server，按“资源 id 是否属于学习通的开屏图”判断；
     *                     false = 运行在学习通进程，用已解析并缓存的资源 id 比对
     */
    private int hookLoadDrawableMethods(final boolean systemServer) {
        int count = 0;
        for (Method method : Resources.class.getDeclaredMethods()) {
            if (!"loadDrawable".equals(method.getName())) continue;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != 4 || types[0] != TypedValue.class || types[1] != int.class) continue;

            log(Log.INFO, TAG, (systemServer ? "[" + extName + "] " : "") + "hooking Resources#loadDrawable("
                    + types[0].getSimpleName() + ", int, " + types[2].getSimpleName()
                    + ", " + types[3].getSimpleName() + ")");
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        // 系统进程里绝不能把异常抛出去，任何问题都退回原逻辑
                        try {
                            Object self = chain.getThisObject();
                            if (!(self instanceof Resources)) return chain.proceed();
                            Resources res = (Resources) self;
                            int resId = (Integer) chain.getArg(1);
                            String name = systemServer
                                    ? (isTargetSplashInSystem(res, resId) ? "splash" : null)
                                    : decorationFor(res, resId);
                            if (name == null) return chain.proceed();
                            Drawable drawable = buildDrawable();
                            if (drawable == null) return chain.proceed();
                            if (systemServer) systemReplaceCount++;
                            log(Log.INFO, TAG, (systemServer ? "[" + extName + "] " : "")
                                    + "replaced drawable 0x" + Integer.toHexString(resId)
                                    + " (" + name + ")"
                                    + (systemServer ? " #" + systemReplaceCount : ""));
                            return drawable;
                        } catch (Throwable t) {
                            log(Log.WARN, TAG, (systemServer ? "[" + extName + "] " : "") + "intercept error: " + t);
                            return chain.proceed();
                        }
                    });
            count++;
        }
        return count;
    }

    /**
     * system_server 里拿不到学习通进程解析过的 id，只能反查资源名。
     * 先用资源 id 高位（0x7f = 应用包）做一次免费过滤，命中应用包才做较贵的 getResourceName，
     * 并对结果做缓存，避免给系统资源加载带来长期开销。
     */
    private boolean isTargetSplashInSystem(Resources res, int resId) {
        if (resId == 0 || (resId >>> 24) != 0x7f) return false;
        final long key = ((long) System.identityHashCode(res) << 32) | (resId & 0xffffffffL);
        synchronized (systemDecisions) {
            Boolean cached = systemDecisions.get(key);
            if (cached != null) return cached;
        }

        boolean hit = false;
        try {
            String name = res.getResourceName(resId); // com.chaoxing.mobile:drawable/splash
            if (name != null && name.startsWith(PKG + ":")) {
                if (systemProbeLogs < 40) {
                    systemProbeLogs++;
                    log(Log.INFO, TAG, "[system] probe 0x" + Integer.toHexString(resId) + " = " + name);
                }
                int slash = name.indexOf('/');
                if (slash > 0) {
                    String type = name.substring(PKG.length() + 1, slash);
                    if ("drawable".equals(type) || "mipmap".equals(type)) {
                        String entry = name.substring(slash + 1);
                        for (String expected : SPLASH_DRAWABLES) {
                            if (expected.equals(entry)) {
                                hit = true;
                                break;
                            }
                        }
                        if (!hit) hit = entry.contains("splash");
                    }
                }
            }
        } catch (Throwable ignored) {
            // 资源 id 不属于当前 Resources 时会抛 NotFoundException，忽略
        }

        synchronized (systemDecisions) {
            systemDecisions.put(key, hit);
        }
        return hit;
    }

    private void hookPublicGetter(String name, Class<?>... params) {
        try {
            Method method = Resources.class.getDeclaredMethod(name, params);
            log(Log.INFO, TAG, "fallback hooking Resources#" + name);
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Resources res = (Resources) chain.getThisObject();
                        String matched = decorationFor(res, (Integer) chain.getArg(0));
                        if (matched != null) {
                            Drawable drawable = buildDrawable();
                            if (drawable != null) return drawable;
                        }
                        return chain.proceed();
                    });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "fallback hook " + name + " failed", t);
        }
    }

    /**
     * 拦截开屏 Activity，做一次视图层面的兜底替换（防止开屏图不走 Resources）。
     */
    private void hookActivityOnCreate() throws NoSuchMethodException {
        Method onCreate = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
        hook(onCreate)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object self = chain.getThisObject();
                    if (self instanceof Activity) {
                        Activity activity = (Activity) self;
                        String name = activity.getClass().getName();
                        if (name.contains("Splash") || name.contains("splash")) {
                            applyToSplashActivity(activity);
                        }
                    }
                    return result;
                });
        log(Log.INFO, TAG, "hooked Activity#onCreate");
    }

    // -------------------------------------------------------------- 视图兜底逻辑

    private void applyToSplashActivity(Activity activity) {
        if (loadBitmap() == null) return;
        // 注意：这里不要覆盖 window 背景 —— 开屏图的主题背景本身可能就是 drawable/splash，
        // 已经被资源层 Hook 换成我们的图片，覆盖成纯色反而会把它弄丢。
        try {
            Resources res = activity.getResources();
            int id = res.getIdentifier("iv_splash", "id", PKG);
            View view = id != 0 ? activity.findViewById(id) : null;
            if (view instanceof ImageView) {
                applyImage((ImageView) view);
            }
            // 再扫一遍视图树，处理其它整屏图片（例如开屏广告容器内的大图）
            List<ImageView> images = new ArrayList<>();
            collectImageViews(activity.getWindow().getDecorView(), images);
            for (ImageView image : images) {
                if (image != view && looksLikeSplash(image)) {
                    applyImage(image);
                }
            }
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "applyToSplashActivity failed", t);
        }
    }

    private void applyImage(ImageView view) {
        Drawable drawable = buildDrawable();
        if (drawable == null) return;
        view.setImageDrawable(drawable);
        // Drawable 内部做等比缩放居中，这里让它铺满容器即可
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        view.setPadding(0, 0, 0, 0);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && (params.width != ViewGroup.LayoutParams.MATCH_PARENT
                || params.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT;
            params.height = ViewGroup.LayoutParams.MATCH_PARENT;
            view.setLayoutParams(params);
        }
        log(Log.INFO, TAG, "ImageView replaced: " + view);
    }

    private void collectImageViews(View view, List<ImageView> out) {
        if (view == null) return;
        if (view instanceof ImageView) out.add((ImageView) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectImageViews(group.getChildAt(i), out);
            }
        }
    }

    /** 开屏图是整屏 1080x1920 的 9-patch，用尺寸做启发式判断。 */
    private boolean looksLikeSplash(ImageView view) {
        Drawable drawable = view.getDrawable();
        if (drawable == null) return false;
        int w = drawable.getIntrinsicWidth();
        int h = drawable.getIntrinsicHeight();
        return w >= 480 && h >= 800;
    }

    // ---------------------------------------------------------------- resources

    private static final long NAME_FALLBACK_WINDOW_MS = 30_000L;
    private long installedAt;

    /**
     * 判断这个资源是否就是要替换的开屏图。
     *
     * @return 命中时返回资源名（用于日志），否则返回 {@code null}
     */
    private String decorationFor(Object resources, int resId) {
        if (!(resources instanceof Resources)) return null;
        if (resId == 0 || (resId >>> 24) != 0x7f) return null; // 快速排除系统资源
        Resources res = (Resources) resources;
        if (!idsResolved) resolveIds(res);
        int[] ids = splashIds;
        String[] names = splashNames;
        if (ids != null && names != null) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == resId) return names[i];
            }
        }
        // 资源被改名等情况下，启动早期用资源名兜底（只在前 30 秒生效，避免长期开销）
        if (installedAt != 0 && System.currentTimeMillis() - installedAt < NAME_FALLBACK_WINDOW_MS) {
            try {
                String name = res.getResourceName(resId);
                if (name != null && name.startsWith(PKG + ":drawable/")) {
                    String entry = name.substring((PKG + ":drawable/").length());
                    if (entry.contains("splash") || entry.contains("launch") || entry.contains("welcome")) {
                        return entry;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 解析开屏 drawable 的资源 id。找不到时（例如首次调用发生在资源尚未就绪的时候）不锁定结果，下次重试。
     */
    private synchronized void resolveIds(Resources res) {
        if (idsResolved || resolveAttempts >= 5) return;
        resolveAttempts++;
        List<Integer> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String name : SPLASH_DRAWABLES) {
            try {
                int id = res.getIdentifier(name, "drawable", PKG);
                if (id != 0) {
                    ids.add(id);
                    names.add(name);
                }
            } catch (Throwable ignored) {
            }
        }
        if (ids.isEmpty()) {
            log(Log.WARN, TAG, "splash drawables not found (attempt " + resolveAttempts + ")");
            return;
        }
        int[] resolved = new int[ids.size()];
        for (int i = 0; i < resolved.length; i++) resolved[i] = ids.get(i);
        splashIds = resolved;
        splashNames = names.toArray(new String[0]);
        idsResolved = true;
        log(Log.INFO, TAG, "resolved splash ids: " + desc(resolved) + " " + names);
    }

    private static String desc(int[] ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("0x").append(Integer.toHexString(ids[i]));
        }
        return sb.append(']').toString();
    }

    private Drawable buildDrawable() {
        Bitmap bmp = loadBitmap();
        if (bmp == null) return null;
        return new SplashDrawable(bmp);
    }

    /** 取样边缘主色作为留白底色，取样点不一致时退回白色。 */
    private static int marginColorOf(Bitmap b) {
        int w = b.getWidth();
        int h = b.getHeight();
        if (w < 8 || h < 8) return Color.WHITE;
        // 向内收一点，避开原图可能自带的 1~几像素细边框
        int inset = Math.min(Math.max(2, Math.min(w, h) / 40), Math.min(w, h) / 4);
        int l = inset, r = w - 1 - inset, t = inset, bo = h - 1 - inset;
        int[] samples = {
                b.getPixel(l, t), b.getPixel(r, t), b.getPixel(l, bo), b.getPixel(r, bo),
                b.getPixel(w / 2, t), b.getPixel(w / 2, bo),
                b.getPixel(l, h / 2), b.getPixel(r, h / 2)
        };
        int rr = 0, gg = 0, bb = 0;
        for (int c : samples) {
            rr += Color.red(c);
            gg += Color.green(c);
            bb += Color.blue(c);
        }
        rr /= samples.length;
        gg /= samples.length;
        bb /= samples.length;
        for (int c : samples) {
            if (Math.abs(Color.red(c) - rr) > 24
                    || Math.abs(Color.green(c) - gg) > 24
                    || Math.abs(Color.blue(c) - bb) > 24) {
                return Color.WHITE;
            }
        }
        return Color.rgb(rr, gg, bb);
    }

    /**
     * 等比缩放并居中绘制（fit-center）：宽高都不裁切，比例失真为 0，
     * 剩余空间用 {@link #marginColor} 填充。
     */
    static final class SplashDrawable extends Drawable {

        private final Bitmap bitmap;
        private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        private final Paint marginPaint = new Paint();
        private final int marginColor;
        private int alpha = 255;

        SplashDrawable(Bitmap bitmap) {
            this.bitmap = bitmap;
            this.marginColor = MARGIN_BLEND_CORNERS ? marginColorOf(bitmap) : Color.WHITE;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.width() <= 0 || bounds.height() <= 0) return;

            marginPaint.setColor(marginColor);
            marginPaint.setAlpha(alpha);
            canvas.drawRect(bounds, marginPaint);

            int bw = bitmap.getWidth();
            int bh = bitmap.getHeight();
            if (bw <= 0 || bh <= 0) return;

            float scale = Math.min(bounds.width() / (float) bw, bounds.height() / (float) bh);
            float dx = bounds.left + (bounds.width() - bw * scale) / 2f;
            float dy = bounds.top + (bounds.height() - bh * scale) / 2f;

            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);

            imagePaint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, matrix, imagePaint);
        }

        @Override
        public void setAlpha(int alpha) {
            if (this.alpha == alpha) return;
            this.alpha = alpha;
            invalidateSelf();
        }

        @Override
        public int getAlpha() {
            return alpha;
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            imagePaint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        public int getOpacity() {
            return alpha == 255 ? PixelFormat.OPAQUE : PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return bitmap.getWidth();
        }

        @Override
        public int getIntrinsicHeight() {
            return bitmap.getHeight();
        }
    }

    /**
     * 取替换图：优先用用户在模块界面里选的自定义图（存在外部媒体目录，宿主进程可读），
     * 没有则回退到模块 APK 内置的 assets/splash.jpg。
     */
    private Bitmap loadBitmap() {
        final String stamp = SplashStore.stamp();
        if (bitmapLoaded && stamp.equals(bitmapStamp) && bitmap != null) return bitmap;
        synchronized (lock) {
            if (bitmapLoaded && stamp.equals(bitmapStamp) && bitmap != null) return bitmap;
            bitmapStamp = stamp;
            bitmapLoaded = true;
            bitmap = SplashStore.loadCustom();
            if (bitmap != null) {
                log(Log.INFO, TAG, "custom bitmap loaded: " + stamp + " -> "
                        + bitmap.getWidth() + "x" + bitmap.getHeight());
                return bitmap;
            }
            ZipFile zip = null;
            InputStream in = null;
            try {
                zip = new ZipFile(getModuleApplicationInfo().sourceDir);
                ZipEntry entry = zip.getEntry(MODULE_ASSET);
                if (entry != null) {
                    in = zip.getInputStream(entry);
                    bitmap = BitmapFactory.decodeStream(in);
                } else {
                    log(Log.ERROR, TAG, "asset not found: " + MODULE_ASSET);
                }
            } catch (Throwable t) {
                log(Log.ERROR, TAG, "load bitmap failed", t);
            } finally {
                closeQuietly(in);
                closeQuietly(zip);
            }
            if (bitmap != null) {
                log(Log.INFO, TAG, "bitmap loaded: " + bitmap.getWidth() + "x" + bitmap.getHeight());
            }
            return bitmap;
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }
}
