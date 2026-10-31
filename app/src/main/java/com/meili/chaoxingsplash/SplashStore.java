package com.meili.chaoxingsplash;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 自定义开屏图的存取。
 *
 * <p>难点：写图的进程是模块自己（{@code com.meili.chaoxingsplash}），
 * 读图的进程却是被 Hook 的宿主（学习通 / SystemUI / system_server），
 * 后两者读不到模块的私有目录（{@code /data/data/.../files} 是 0700 + SELinux 限制）。
 *
 * <p>因此统一放在 <b>外部媒体目录</b> {@code /storage/emulated/0/Android/media/<模块包名>/}：
 * 这个目录不需要任何权限就能写，且其他应用（以及系统进程）可以读取。
 */
public final class SplashStore {

    public static final String PKG = "com.meili.chaoxingsplash";
    public static final String DIR = "Android/media/" + PKG;
    public static final String FILE_NAME = "custom_splash.jpg";

    /** 同一个外部存储可能出现的几种挂载写法，全部试一遍，谁先存在用谁 */
    private static final String[] ROOTS = {"/storage/emulated/0", "/sdcard", "/data/media/0"};

    private static final int MAX_EDGE = 2400;

    private SplashStore() {
    }

    public static File primary() {
        return new File(ROOTS[0], DIR + "/" + FILE_NAME);
    }

    public static File[] candidates() {
        List<File> list = new ArrayList<>();
        for (String root : ROOTS) {
            list.add(new File(root, DIR + "/" + FILE_NAME));
        }
        return list.toArray(new File[0]);
    }

    /** 返回第一个可读的自定义图；没有则 null */
    public static File existing() {
        for (File f : candidates()) {
            try {
                if (f.isFile() && f.length() > 0 && f.canRead()) return f;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    public static boolean hasCustom() {
        return existing() != null;
    }

    /** 读取自定义图，失败返回 null（调用方应回退到内置图） */
    public static Bitmap loadCustom() {
        File f = existing();
        if (f == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > MAX_EDGE) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 删除所有候选位置的自定义图，返回是否真的删掉了东西 */
    public static boolean clearCustom() {
        boolean removed = false;
        for (File f : candidates()) {
            try {
                if (f.exists() && f.delete()) removed = true;
            } catch (Throwable ignored) {
            }
        }
        return removed;
    }

    /** 文件指纹，用于在宿主进程里判断自定义图是否已被替换（避免重启才生效） */
    public static String stamp() {
        File f = existing();
        if (f == null) return "asset";
        return f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
    }
}
