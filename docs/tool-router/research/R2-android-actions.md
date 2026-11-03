# R2 · Android 本地动作：打开应用 / 定时提醒 / 悬浮窗显隐

> 前提（本项目的实际配置）：`compileSdk=34 / targetSdk=34 / minSdk=28`；机型 = 小米 13（HyperOS，SDK 36）+ 一加 7T Pro（HD1910，Android 11 / ColorOS 11）。
> 「待确认」= 只有二手来源或未在本机复现，后面都给了验证命令。一手来源全部是 developer.android.com，见 §6。

## 0. 总览

| 动作 | 一句话结论 | 主要风险 |
|---|---|---|
| 打开其他应用 | `startActivity` 不需要包可见性；只有「先判断能不能打开 / 列应用表」才需要 `<queries>` | 后台启动限制（静默失败）+ MIUI 独立开关 |
| 定时提醒 | 进程内用协程/Hander；跨进程用 `AlarmManager` + `SCHEDULE_EXACT_ALARM`（先 `canScheduleExactAlarms()`） | Android 14 新装默认拒绝精确闹钟 |
| 悬浮窗显隐 | 默认 `removeViewImmediate`；高频切换用 flags + alpha | GONE 既不省 Surface 也可能继续吃触摸 |

---

## 1. 打开其他应用

### 1.1 包可见性：`<queries>` vs `QUERY_ALL_PACKAGES`

- **关键事实**（官方明文）：`startActivity()` 启动别的 App **不需要**包可见性，「implicit 和 explicit intent 都成立」。可见性只过滤 `PackageManager` 的**查询**结果（`getLaunchIntentForPackage` / `queryIntentActivities` / `getInstalledPackages`）。
- 因此：只为「打开」而申请 `QUERY_ALL_PACKAGES` 是白担上架风险；官方给的合适场景只有无障碍、浏览器、设备管理、安全、杀毒五类，桌宠不在其中。
- 需要「列应用 / 预判能否启动」时，按用途声明：

```xml
<!-- 只要"有桌面入口的 App"（≈ 桌面/drawer 的可见范围），不要 QUERY_ALL_PACKAGES -->
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
<!-- 已知包名时更省：<package android:name="com.tencent.mm" /> -->
```

- `QUERY_ALL_PACKAGES` 现状：本项目 manifest 已声明（`tools:ignore="QueryAllPackagesPermission"`）。它是 Play 受限权限，申报入口在 Play Console → App content；政策原文链接本次抓取失败，**待确认**。

### 1.2 启动与查询的正确用法 / 性能

- 启动：`Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(pkg)` + `FLAG_ACTIVITY_NEW_TASK`，`ActivityNotFoundException` 兜底。**不要**先 `getLaunchIntentForPackage` 再启动——多一次 binder，且不可见时它返回 `null`（文档：「or if packageName is not recognized」），会把「没装」和「看不见」混为一谈。
- Android 13+ 若要「启动一个查不到的包」，可用 `getLaunchIntentSenderForPackage()`，文档明确它「not restricted by the package visibility」。
- 列表：`queryIntentActivities` + `loadLabel` 是 binder + 逐包资源读取，**别在主线程 / Compose composition 里跑**（`MainActivity.kt:1444` 已有此注释）。缓存策略：包名→`Intent` 的进程内 Map（几十字节/条，永久有效）+ `ACTION_PACKAGE_ADDED/REMOVED/REPLACED` 广播失效；`Drawable` 图标进 `LruCache`（32~64 项），否则 200 个应用 ≈ 数十 MB。

### 1.3 从悬浮窗 Service 里 `startActivity` 会不会被拦

- Android 10（API 29）起后台启动 Activity 受限。官方豁免清单里明确包含：**「应用已获得用户授予的 SYSTEM_ALERT_WINDOW 权限」**、有可见窗口、系统发出的 PendingIntent（通知点击）、`START_ACTIVITIES_FROM_BACKGROUND`、桌面启动器发起等。
- 结论：**本项目持有已授予的悬浮窗权限 → 从 overlay Service 起 Activity 是合规路径**，前提是 `appops get <pkg> SYSTEM_ALERT_WINDOW` 仍为 `allow`。
- 被拦时的表现：`startActivity` **正常返回、不抛异常**，行为是「什么都没发生」；只有 logcat 有 `ActivityTaskManager` 的 `Background activity launch blocked!`。
- 另一条硬约束：`Service`/`Application` context 起 Activity 必须带 `FLAG_ACTIVITY_NEW_TASK`，否则 `AndroidRuntimeException: Calling startActivity() from outside of an Activity context requires the FLAG_ACTIVITY_NEW_TASK flag`。
- 想「确认真的打开了」：启动后 ~600ms 用 `UsageStatsManager` 查前台包名（本项目已有 `PACKAGE_USAGE_STATS`），比 try/catch 靠谱。

### 1.4 小米 / 一加的额外拦截

- **小米 HyperOS / MIUI**：除 AOSP 限制外，另有「自启动」和「后台弹出界面」两个独立开关，默认拒绝时 `startActivity` 同样静默失败。网上记录的 MIUI 11 op 常量是 `OP_BACKGROUND_START_ACTIVITY = 10021`、`OP_GET_INSTALLED_APPS = 10022`（第三方博客），但本项目在 HyperOS（SDK 36）上实测 `checkOpNoThrow(10021, …)` 对应的是**自启动**——说明 op 编号随 ROM 版本漂移，**待确认**，必须按机型核对：

```kotlin
// 打印本机所有 op 常量，按名字找 auto_start / background_start_activity
AppOpsManager::class.java.declaredFields
    .filter { it.type == Int::class.javaPrimitiveType && Modifier.isStatic(it.modifiers) }
    .forEach { println("${it.name}=${it.getInt(null)}") }
```

```shell
adb shell appops get com.dshpet.android     # 看本机真实 op 名与状态（(running) 表示当前生效）
```

- **一加 / ColorOS 11**：本机（3cab08db）实测 `appops get com.dshpet.android` 里**没有** background-start 类 op，只有 AOSP 常规项 + `SYSTEM_ALERT_WINDOW: allow (running)`；其「自启动 / 关联启动 / 耗电管理」是 UI 层管控，读不到也改不了，只能引导用户去点。
- 绕过手段（按可靠度）：① 通知点击——系统发出的 `PendingIntent` 属豁免项，最稳；② Shizuku（shell UID 执行 `am start`，需用户每次开机手动启动服务）；③ 无障碍服务——**不在 AOSP 豁免清单**里，能否起 Activity 依赖 ROM，**待确认**，且上架审查敏感，不建议作为主路径。

**汇总表**

| 项 | 内容 |
|---|---|
| 推荐方案 | `setPackage()` + MAIN/LAUNCHER 的显式化 intent + `NEW_TASK`，`ActivityNotFoundException` 兜底；失败后用 UsageStats 复核 |
| 备选 | 通知点击（`PendingIntent.getActivity`）；Shizuku `am start`；无障碍（不推荐） |
| 需要声明 | `SYSTEM_ALERT_WINDOW`（已有）；`<queries>` 仅在需要列表/预检时加；`PACKAGE_USAGE_STATS`（已有，用于复核） |
| 失败表现 | 无异常、无界面；logcat `ActivityTaskManager: Background activity launch blocked!`；`getLaunchIntentForPackage` 返回 `null` |
| 验证方法 | `adb logcat -s ActivityTaskManager`；`adb shell appops get <pkg> SYSTEM_ALERT_WINDOW`；`adb shell dumpsys package queries`（看 `forceQueryable`） |

---

## 2. 定时提醒

### 2.1 精确闹钟权限（Android 12 / 14）

| 权限 | 授予方式 | 适用 | 坑 |
|---|---|---|---|
| `SCHEDULE_EXACT_ALARM` | 用户在「闹钟和提醒」特殊权限页授予 | 通用 | **targetSdk 33+ 的新装默认不授予**；备份恢复到 Android 14 也拒绝；升级到 14 的存量应用保留 |
| `USE_EXACT_ALARM` | 装机即授予，用户不可撤销 | 仅闹钟/日历类主功能 | Play 政策限制用途，桌宠不满足 |

- 不声明任一权限就 `setExact*` → **`SecurityException`**；必须在设置前 `canScheduleExactAlarms()`，false 时跳 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`。
- 冷知识：用 `setExact()` + `OnAlarmListener`（不带 PendingIntent）**不需要**该权限——但回调要求进程活着，可靠性等价于 Handler。

```kotlin
val am = context.getSystemService(AlarmManager::class.java)
if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
    context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return  // 别在这里 setExact，会崩
}
// 点击必须直接指向 Activity，见坑 #6
val pi = PendingIntent.getActivity(context, id, targetIntent,
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
```

### 2.2 五种定时方案

| 方案 | 精度 | 进程被杀 | Doze | 耗电 | 结论 |
|---|---|---|---|---|---|
| `Handler.postDelayed` / 协程 `delay` | 高（uptime） | 失效 | 挂起，深睡不计时 | 极低 | 只用于进程内自说自话（本项目现用） |
| 前台服务 + Handler | 高 | 常驻存活 | 部分受限 | 高（常驻通知+唤醒） | 桌宠已在跑 FGS，可复用但别为定时再加 |
| `AlarmManager.set()` | 官方：Android 12+ 触发后 **1 小时内**送达 | 存活（配合 BOOT 重排） | 不唤醒设备 | 低 | 「大概这个点」够用时首选 |
| `setWindow()` | 窗口 <10 分钟被夹到 10 分钟 | 同上 | 同上 | 低 | 需要「几点到几点之间」 |
| `setExactAndAllowWhileIdle` | 精确 | 同上 | 每 App **最多 9 分钟一次** | 中 | 真正准点提醒 |
| `WorkManager` | 周期最小 15 分钟，可延迟 | 存活 | 延后 | 低 | 只做可延迟任务（同步/清理） |

### 2.3 通知权限（`POST_NOTIFICATIONS`）

- 清单声明后用 `ActivityResultContracts.RequestPermission` 在**用户刚开启提醒开关的上下文里**申请，别冷启动就弹；`shouldShowRequestPermissionRationale()` 为 true 时先解释。
- 差异：targetSdk ≥33 才能「拒绝后再问」；targetSdk ≤32 时用户点一次「不允许」就永久不再弹（除非卸载重装）。
- 无权限时**前台服务照样能跑，但它的常驻通知不可见**——用户会觉得服务「偷偷在跑」，也更容易被 ROM 杀。
- MIUI/HyperOS 二级开关：`areNotificationsEnabled()` 为 true 不代表能弹。权威判断是**渠道级**：

```kotlin
val ch = nm.getNotificationChannel(CHANNEL_ID)          // importance == NONE 就是被关
val ok = NotificationManagerCompat.from(ctx).areNotificationsEnabled() &&
         ch != null && ch.importance != NotificationManager.IMPORTANCE_NONE
```

  另有 MIUI 私有 extra：`miui.enableFloat=false` 可关悬浮横幅、`miui.enableKeyguard=false` 可关锁屏显示（第三方资料，**待确认** HyperOS 是否仍生效）。

| 项 | 内容 |
|---|---|
| 推荐方案 | 准点 = `SCHEDULE_EXACT_ALARM` + `setExactAndAllowWhileIdle` + `PendingIntent.getActivity`；不要求准点 = `setWindow(…, 10min)` |
| 备选 | `set()` 不精确闹钟；进程内 Handler；WorkManager（≥15min 可延迟任务） |
| 需要声明 | `SCHEDULE_EXACT_ALARM`、`POST_NOTIFICATIONS`、`RECEIVE_BOOT_COMPLETED`（重启后重排，已有） |
| 失败表现 | `SecurityException`（越权 setExact）；闹钟到点无通知（权限/channel 被关）；`ForegroundServiceStartNotAllowedException`（后台起 FGS 且无豁免） |
| 验证方法 | `adb shell dumpsys alarm \| grep <pkg>`；`adb shell cmd appops get <pkg> SCHEDULE_EXACT_ALARM`（Android 11 无此 op，会报 Unknown operation）；`adb shell dumpsys deviceidle force-idle` 后观察 |

---

## 3. 悬浮窗隐藏 / 显示

| 做法 | 触摸 | 焦点 | Surface/耗电 | 适用 |
|---|---|---|---|---|
| `View.GONE` | window 仍在，是否吃触摸取决于 `touchableRegion` | 不变 | **不释放** Surface，仍在合成树里 | 不推荐（省不了电，还可能挡住点击） |
| `removeViewImmediate` | 彻底不挡 | 释放 | 释放 Surface，最省电 | 默认隐藏方式 |
| 改 flags：`FLAG_NOT_TOUCHABLE`（永不接收触摸）+ `FLAG_NOT_FOCUSABLE`（不拿输入焦点） | 不吃触摸 | 不抢焦点/不挡返回键 | 仍在合成；`alpha=0` 也照画 | 需要「1 帧内恢复」的高频切换 |

- 推荐：**隐藏走 `removeViewImmediate`**，恢复时重建——代价是首帧空窗（本项目实测换解码器 35~90ms），可用已预热的容器/静帧遮住。
- 只有「点一下就要立刻出现」的场景才用 flags + `alpha`，且必须同时加 `FLAG_NOT_TOUCHABLE`，否则透明窗口继续吃触摸。

---

## 4. 必须知道的 8 个坑

1. **`startActivity` 不需要包可见性**，`QUERY_ALL_PACKAGES` 只为「列应用/预检」存在；为一个桌宠申请它纯属给上架加风险。
2. **`getLaunchIntentForPackage` 返回 null 有二义性**：没装，或包不可见。要区分就用 `<queries>` 或 Android 13+ 的 `getLaunchIntentSenderForPackage`。
3. **Service 里 `startActivity` 忘 `NEW_TASK`** → 直接 `AndroidRuntimeException` 崩，不是「没反应」。
4. **后台启动被拦是静默的**：无异常、无 toast，只有 logcat；所以必须有 `UsageStatsManager` 之类的「事后确认」。
5. **悬浮窗权限是 BAL 豁免项，但会被 ROM 撤销**（省电清理、权限回收）；另外 MIUI 的「后台弹出界面」是独立开关，op 编号随版本漂移（10021 在本机是自启动）。
6. **通知跳板（trampoline）禁令**：targetSdk 31+ 不能在 BR/Service 里 `startActivity` 来做通知点击跳转，必须 `PendingIntent.getActivity` 直达目标页。
7. **`SCHEDULE_EXACT_ALARM` 在 Android 14 对新装默认拒绝**，不查 `canScheduleExactAlarms()` 直接 `setExact` 会 `SecurityException`；`USE_EXACT_ALARM` 只给闹钟/日历类应用。
8. **计时语义陷阱**：`Handler.postDelayed` 用 uptime（深睡不计时）；`setExactAndAllowWhileIdle` 每 App 每 9 分钟最多一次；`POST_NOTIFICATIONS` 被拒时 FGS 通知不可见，且 MIUI 还要过渠道级 `IMPORTANCE_NONE` 这一关。

---

## 5. 针对本项目的选型结论

1. **打开其他应用**：用 `Intent(ACTION_MAIN).addCategory(CATEGORY_LAUNCHER).setPackage(pkg)` + `FLAG_ACTIVITY_NEW_TASK` 直接 `startActivity`（不查可见性，`ActivityNotFoundException` 兜底），列表页用 `queryIntentActivities` 结果做进程内缓存；manifest 把 `QUERY_ALL_PACKAGES` 换成 `<queries>` 里的 MAIN/LAUNCHER `<intent>`，保留 `SYSTEM_ALERT_WINDOW`，并在设置页引导 MIUI 用户开「自启动 + 后台弹出界面」。
2. **定时提醒**：秒级准点（如用户设的闹钟式提醒）用 `AlarmManager.setExactAndAllowWhileIdle` + `SCHEDULE_EXACT_ALARM`（先 `canScheduleExactAlarms()` 再跳授权页），通知点击用 `PendingIntent.getActivity`；桌宠自己的自说自话间隔继续用协程 `delay`，不要碰 AlarmManager。
3. **悬浮窗显隐**：隐藏统一走 `windowManager.removeViewImmediate(view)`（真实释放 Surface、绝不吃触摸），需要「瞬显」的交互才退化为 `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE` + `alpha=0`，`View.GONE` 不用。

**要加的 manifest 声明（相对现状的增量）**

```xml
<!-- 保持不变 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- 新增：只在必须准点提醒时 -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<!-- 去掉：QUERY_ALL_PACKAGES，改为下面这个（仅在需要"应用列表"页时） -->
<queries>
    <intent>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent>
</queries>
```

---

## 6. 参考链接

- 包可见性声明：https://developer.android.com/training/package-visibility/declaring
- 自动可见的包（含「startActivity 不要求可见性」原文）：https://developer.android.com/training/package-visibility/automatic
- 有限可见性下的常见用例：https://developer.android.com/training/package-visibility/use-cases
- `getLaunchIntentForPackage` / `getLaunchIntentSenderForPackage`：https://developer.android.com/reference/android/content/pm/PackageManager#getLaunchIntentForPackage(java.lang.String)
- 后台启动 Activity 的限制与豁免清单：https://developer.android.com/guide/components/activities/background-starts
- 通知 trampoline 限制：https://developer.android.com/about/versions/12/behavior-changes-12#notification-trampolines
- 精确闹钟权限（Android 12）：https://developer.android.com/about/versions/12/behavior-changes-12#exact-alarm-permission
- 精确闹钟默认拒绝（Android 14）：https://developer.android.com/about/versions/14/behavior-changes-14
- 设置闹钟（含 SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM 对比）：https://developer.android.com/develop/background-work/services/alarms/schedule
- Doze 下 9 分钟限制：https://developer.android.com/training/monitoring-device-state/doze-standby
- 前台服务后台启动限制与豁免：https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- 通知运行时权限：https://developer.android.com/develop/ui/views/notifications/notification-permission
- `FLAG_NOT_TOUCHABLE` / `FLAG_NOT_FOCUSABLE`：https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE
- Shizuku 用户手册：https://shizuku.rikka.app/guide/setup/
- MIUI 通知二级开关（第三方）：https://www.jianshu.com/p/9df4101b83d2
- MIUI op 常量清单（第三方，编号待本机核对）：https://blog.csdn.net/u014418171/article/details/103583757
