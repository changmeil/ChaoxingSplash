# ChaoxingSplash · 学习通开屏替换

把学习通（`com.chaoxing.mobile`）开屏页的「学习是一种信仰」换成你自己的图，**从冷启动第一帧起**就是新图，纯本地绘制，不改宿主 APK、不联网。

![效果](docs/screenshot_splash.png)

- 模块框架：**LSPosed / libxposed API 102**
- 支持版本：学习通 7.x（资源名 `drawable/splash`、`drawable/splash_1080`）
- 显示方式：等比缩放 + 居中留白（fit-center），不裁切、不变形
- 换图：装好后在模块界面点「选择图片」即可，**不需要重新打包**

---

## 一、为什么不是"改个图层"那么简单

学习通的开屏其实经过**三个进程**，少拦一个就会看到原图闪一下：

| 阶段 | 谁在画 | 画面内容 |
| --- | --- | --- |
| 冷启动 0~2.5s | HyperOS 把启动窗口（`Splash Screen com.chaoxing.mobile`，`APPLICATION_STARTING`）交给 **`com.android.systemui`** 渲染 | 主题 `CxTheme.Splash` 的 `windowBackground = @drawable/splash` |
| 学习通进程起来后 | `SplashActivity` + `layout/n_activity_splash` 的 `iv_splash` | 同一张 `drawable/splash` |
| 部分 ROM | **system_server** 直接画启动窗口 | 同上 |

实测（`screenrecord` 逐帧 + `dumpsys window` 轮询 + logcat 时间戳对齐）：

```
46.07s  am start -n com.chaoxing.mobile/.activity.SplashActivity
46.35s  window manager 创建启动窗口 Splash Screen com.chaoxing.mobile
48.29s  学习通进程内 hook 已替换完毕（replaced drawable 0x7f081a44）
48.85s  屏幕上却还是原图        ← 因为此刻显示的是 SystemUI 渲染的启动窗口
49.01s  启动窗口移除，才切到新图
```

所以模块的作用域需要三项：`com.chaoxing.mobile` + `com.android.systemui` + `system`。
1.7.1 之前只勾了前两项时，就一直卡在"开头闪原图"这一步。

---

## 二、实现

入口类 `SplashModule`（`io.github.libxposed.api.XposedModule`）挂三层：

1. **资源层（主）** — hook `Resources#loadDrawable(TypedValue, int, int, Theme)`。
   布局的 `src` / `background` / `srcCompat`、主题的 `windowBackground` 最终都走这里，
   命中开屏资源就返回自绘的 `SplashDrawable`。

   - 应用进程里用 `getIdentifier` 解析出的 id 直接比对（零开销）；
   - SystemUI / system_server 里拿不到那些 id，只能反查资源名，判断顺序**先廉价后昂贵**：

   ```java
   if ((resId >>> 24) != 0x7f) return chain.proceed();         // 不是应用包资源，立刻放行
   String name = res.getResourceName(resId);                   // com.chaoxing.mobile:drawable/splash
   if (name == null || !name.startsWith(PKG + ":")) return chain.proceed();
   ```

   判定结果按 `identityHashCode(res) << 32 | resId` 缓存——不同包的 Resources 都用 `0x7f` 段，
   只按 id 缓存会把别的包的同号资源误判成同一个。

2. **视图层（兜底）** — hook `Activity#onCreate`，对类名含 `Splash` 的页面把 `id/iv_splash`
   换成自定义图，并扫描视图树替换其它"整屏尺寸"的 ImageView，
   覆盖 `BitmapFactory.decodeResource` 这类不走 `loadDrawable` 的写法。

3. **进程识别** — `onModuleLoaded` 里用 `param.isSystemServer()` 判断系统进程，
   用进程名识别 SystemUI；`onSystemServerStarting()` 作为系统侧的标准入口
   （关键服务启动前触发，早于任何启动窗口）。

4. **配置通道** — 模块 App 声明 `io.github.libxposed.service.XposedProvider`
   （authority `com.meili.chaoxingsplash.XposedService`），LSPosed 用它把 `IXposedService`
   投递进来；App 侧 `XposedService#getRemotePreferences()` 写、Hook 侧
   `XposedInterface#getRemotePreferences()` 读，同一份数据存在 LSPosed 库里，不碰文件系统。

   > 用 aapt2 手工打包**不会合并 aar 的 manifest**，这个 provider 必须手写在
   > `AndroidManifest.xml` 里，否则会看到 `No provider info for content provider ...XposedService`。

保护措施：`exceptionMode=protective`、intercept 内整体 try/catch、任何异常都 `proceed()` 走原逻辑，
因此即使宿主资源结构变化，也只会"不生效"，不会让宿主崩溃。

### 显示：fit-center

`SplashDrawable` 自己算矩阵，不依赖 `BitmapDrawable` 的 gravity：

```
scale = min(boundW / bmpW, boundH / bmpH)   // 取小的 → 整图完整可见
dx    = (boundW - bmpW * scale) / 2         // 水平居中
dy    = (boundH - bmpH * scale) / 2         // 垂直居中
```

四周留白底色默认取图片边缘的采样色（会向内缩 `min(w,h)/40` 避开原图细边框），
采样点颜色不一致时退回纯白。想强制纯白：把 `SplashModule.MARGIN_BLEND_CORNERS` 改成 `false`。

---

## 三、安装

1. 需要 **LSPosed 2.x**（支持 libxposed API 102）。低于此版本请在
   `app/src/main/resources/META-INF/xposed/module.prop` 里把 `minApiVersion` 改成 `101` 后自行构建（代码没用 102 独有 API）。
2. 安装 `dist/ChaoxingSplash-<版本>.apk`。
3. LSPosed → 模块 → 启用「学习通开屏替换」，**作用域勾三项**：
   **学习通**、**系统框架**、**系统界面（com.android.systemui）**。
4. **重启手机**（SystemUI / system_server 层需要重启才会加载模块）。
5. 打开学习通，开屏即新图。

> 升级模块时注意：LSPosed 只在**首次安装**时读取 APK 里的 `scope.list`，
> 覆盖安装不会刷新已登记的作用域；`staticScope=false` 意味着作用域以你在管理器里勾的为准。

### 系统兼容性

| 项 | 值 |
| --- | --- |
| `minSdkVersion` | 26（Android 8.0） |
| `targetSdkVersion` | 34（Android 14） |
| native 库 | 无（纯 Java/dex，与 CPU 架构无关） |
| 签名 | APK Signature Scheme v2 + v3 |

Android 8.0 ~ 16 都能装，**Android 15 完全可用**。

> 手工打包（不走 Gradle）时极易踩的一个坑，已修复：`resources.arsc` 必须
> **以 STORED 方式存放并 4 字节对齐**，否则 `targetSdk >= 30` 的设备会直接拒绝：
> `-124: Failed parse during installPackageLI: Targeting R+ (version 30 and above)
> requires the resources.arsc of installed APKs to be stored uncompressed and
> aligned on a 4-byte boundary`。见 `tools/package.py` 与 `build.sh` 第 6/7 步。

### 如果装不上

| 现象 | 处理 |
| --- | --- |
| 小米提示 `安装失败(-124) 该安装包与您的系统不兼容` | v1.9.3 及更早的包有 `resources.arsc` 被压缩的 bug，换 **v1.9.4+**。想确认原因可以 `adb install`，它会打印带 `-124:` 前缀的真实报错 |
| 提示「解析软件包出错」 | 文件传输损坏，重新发送，核对字节数（1.9.4 为 173250 字节） |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 设备上有另一签名的同包名旧版，先卸载 `com.meili.chaoxingsplash` |
| `INSTALL_FAILED_USER_RESTRICTED` | HyperOS 限制 adb 安装：把 APK 放到手机里用文件管理器点开装 |
| 装上了但没效果 | 检查作用域三项是否勾全 + 是否重启过手机；见「七、排查」 |

---

## 四、换图

**方法一（推荐，不用重装）**：打开模块界面 → 点「选择图片」→ 选图 → 重启学习通。

选中的图会同时写两份，因为**写图的是模块进程，读图的是另外三个进程**：

| 通道 | 位置 | 谁在用 |
|---|---|---|
| 落盘文件 | `Android/media/com.meili.chaoxingsplash/custom_splash.jpg` | SystemUI（读得到外部存储） |
| LSPosed 远程配置 | LSPosed 数据库（base64，长边 ≤ 1280px） | 学习通进程（读不到外部存储，走 binder） |

两条通道任一命中即可，都读不到时回退到 APK 内置图，不会白屏。

- 落盘那份不需要任何存储权限（`Android/media/<包名>/`），但学习通进程受分区存储限制看不见它；
- 远程配置走 `libxposed-service` 的 `XposedProvider` → `IXposedService`，
  模块侧 `getRemotePreferences()` 写、Hook 侧 `getRemotePreferences()` 读，完全绕开文件权限。

> 第一次设置图片后，如果开屏还是旧的：**重启一次手机**（或 `am crash com.android.systemui`）。
> SystemUI 是常驻进程，它在图片文件出现之前启动的话会缓存「该目录是空的」。
> 之后换图即时生效，不用再重启。

**方法二（改内置图，适合做发行版）**：

```bash
cp your_image.jpg app/src/main/assets/splash.jpg
bash build.sh
```

---

## 五、构建

本工程**不用 Gradle**，直接调 Android SDK 工具链：

```
aapt2 compile → aapt2 link → javac → d8 → 打包 → zipalign → apksigner
```

```bash
bash tools/setup_sdk.sh     # 下载 build-tools 35 / platform 35 / libxposed api 102 到 ./sdk
bash build.sh               # 产物在 dist/
```

依赖：

- JDK 17+（`--release 17` 编译）
- Android build-tools 35、platform 35 的 `android.jar`
- `io.github.libxposed:api:102.0.0`（Maven Central）
- Python 3（仅 `tools/*.py` 打包与预览图脚本需要）

`build.sh` 默认用 `../sdk`，没有则用 `./sdk`，也可 `SDK=/path/to/sdk bash build.sh` 指定。
首次构建会自动生成 `debug.keystore`；正式发布请换成自己的签名。

---

## 六、目录

```
.
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── assets/splash.jpg                      # 内置替换图
│   ├── java/com/meili/chaoxingsplash/
│   │   ├── SplashModule.java                  # 入口：三层 Hook + SplashDrawable
│   │   ├── SplashStore.java                   # 自定义图的存取（跨进程可读路径）
│   │   ├── MainActivity.java                  # 模块界面（预览 / 选图 / 恢复内置）
│   │   └── BuildConfig.java
│   ├── res/                                   # 界面布局、图标、文案
│   └── resources/META-INF/xposed/
│       ├── module.prop                        # minApiVersion / staticScope / exceptionMode
│       ├── java_init.list
│       └── scope.list                         # 学习通 + SystemUI + system
├── stub/androidx/annotation/                  # 编译期占位注解，不打进 APK
├── tools/
│   ├── setup_sdk.sh                           # 下载构建依赖
│   ├── package.py                             # 把 dex 与 META-INF/xposed 塞进 APK
│   ├── gen_icon.py                            # 生成图标
│   ├── mk_preview.py                          # 生成效果对比图
│   └── verify.sh                              # adb 一键复验：冷启动 + 连拍 + 抓日志
├── docs/                                      # 效果图、原图对照
└── build.sh
```

---

## 七、排查

日志标签 `CxSplash`（LSPosed → 日志，或 `adb logcat | grep CxSplash`）。

| 现象 | 判断与处理 |
| --- | --- |
| 完全没有 `CxSplash` 日志 | 模块没被加载：确认已启用、作用域含学习通 |
| 只有 `(com.chaoxing.mobile)` 的 `replaced drawable`，开头仍闪原图 | SystemUI 没进作用域：勾「系统界面」后重启手机 |
| 有 `(com.android.systemui) replaced drawable` | 已从第一帧生效 |
| 有 `module loaded` 但没有 `replaced` | 开屏资源改名了：把日志里的 drawable 名加进 `SPLASH_DRAWABLES` |
| 底部出现两套 logo | 学习通在开屏页额外叠了 `splash_branding`，可在 `applyToSplashActivity` 里 `setVisibility(GONE)` |
| 留白颜色不对 | `MARGIN_BLEND_CORNERS = false` 固定白色 |
| 图四周有细线 | 原图最外圈自带 5~8px 描边，裁掉再换即可 |
| 选好的图不生效 | 换图后要**重启学习通**（进程内 bitmap 有缓存，按文件指纹失效） |
| 模块界面提示"没连上 LSPosed" | `XposedProvider` 没收到 Binder：确认模块已启用；老版本 LSPosed 可能要求把「学习通开屏替换」自己也在作用域里勾上 |
| 日志有 `No provider info ... XposedService` | 同上，是 APK 里缺 `io.github.libxposed.service.XposedProvider` 声明（手工打包没做 manifest 合并导致的，1.9.2 已修） |

---

## 八、已知限制

- 只替换静态开屏图；联网下发的广告开屏属于另一套逻辑，不处理。
- 注入 SystemUI / system_server 属于系统级修改。模块已做异常隔离，但**使用风险自负**；
  出问题可 `adb uninstall com.meili.chaoxingsplash` 后重启恢复。
- 仅修改本机展示效果，不修改应用本体，不涉及任何服务端能力。
- `assets/splash.jpg` 为示例图，替换为你自己的图片再分发。

## 九、许可

MIT。仅供学习与个人使用，请遵守当地法律与目标应用的服务条款。
