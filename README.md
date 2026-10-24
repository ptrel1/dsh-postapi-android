# DSH PostAPI · Android 客户端（v0.5.0）

> DSH（DeepSeekRuntime）的公网访问入口之一：用账号密码登录，然后在原生 WebView 里使用官方网页版。
> **账号密码在本机加密保存**，下次打开免输入；cookie 失效自动重登。
> 支持交付物直链下载（卡片点一下即下载）。

---

## 必须先安装配套插件（否则本 App 无法登录）

**本 App 不是独立客户端** —— 它依赖 DSH 插件 **dsh-postapi-bridge** 提供登录与插件 API。
插件没装，App 连登录都会失败（/api/dsh/v1/mobile-login 不存在 → 404）。

### 安装（在跑 DSH 的那台机器上）

    # 装到当前 profile（把 web 换成你自己的 profile 名）
    dsh plugin --profile web add dsh-postapi-bridge

    # 重启 DSH 生效

### 发布渠道

| 渠道 | 地址 |
| :--- | :--- |
| **npm（推荐）** | https://www.npmjs.com/package/dsh-postapi-bridge |
| npm 安装命令 | npm install dsh-postapi-bridge |
| **GitHub** | https://github.com/ptrel1/dsh-postapi-bridge |
| 插件 Gitea 主仓 | https://git.ptrel.cc.cd:53103/ptrel1/dsh1-plugins |

**当前版本：dsh-postapi-bridge@1.8.0**

> ⚠️ npm 上 0.7.0 及更早的版本**不包含**本 App 需要的接口，必须 >= 1.8.0。
> 用 npm view dsh-postapi-bridge version 确认。

### 插件提供哪些接口

App 用到的全部端点（都由 dsh-postapi-bridge 提供）：

| 端点 | 用途 |
| :--- | :--- |
| POST /api/dsh/v1/mobile-login | 账号密码换「官方启动令牌 + 插件会话 cookie」 |
| GET /api/dsh/v1/files?sessionId= | 交付物清单（含可直接下载的直链） |
| POST /api/dsh/v1/file-ticket | 签发下载票据 |
| GET /api/dsh/v1/dl/<token> | 无状态直链下载（48h 时效） |
| GET /api/dsh/v1/app-version | App 版本检查 |
| GET /api/dsh/v1/app-latest.apk | App 安装包 |
| POST /api/dsh/v1/chat/stream | SSE 流式对话（兼容模式用，v1.8.0 起） |

**插件的其他能力**（交付物直链、侧栏自动收起、客户端诊断）见插件仓库 README。

---

## 首次使用必读

**请先把登录页的「服务地址」改成你自己的 DSH 地址**（例如 https://dsh.example.com）。

> 仓库里**不含任何真实域名**：默认预填值是占位用的 http://127.0.0.1:3080，
> 它不是你该连的服务器，点登录前一定要改。改完地址后那个值会被记住。

---

## 两种使用方式（v0.5.0）

登录成功后第一次进入，App 会让你选一次使用方式，选择会记住：

| | 完整模式（WebView） | 兼容模式（原生） |
| :--- | :--- | :--- |
| **界面** | 官方网页版，与电脑端一致 | 原生界面（**开发中**，当前为占位页） |
| **功能** | 全部：交付物下载、命令、@提及、审批、模型切换 | 目前无 |
| **依赖** | 系统 WebView 内核（旧内核如 Chrome 114 会卡顿） | 不依赖 WebView 内核 |
| **适合** | 需要完整功能 | 日常聊天、旧机型 |

随时可以在 App 内切换，两种模式共用同一个账号与 cookie。

---

## 一、这个 App 解决什么

用户需求原话：**"提供 dsh 给公网访问用户的文件下载方式"** —— 浏览器是主入口，安卓是接入方式之一。

官方 DSH Web 的门禁设计决定了"直接用手机浏览器打开"体验很差：

| 官方约束 | 出处 | 对客户端的后果 |
| :--- | :--- | :--- |
| `?token=` 只能在 `GET /` 且**恰好一个** token 参数时兑换 cookie，兑换后 303 + `Set-Cookie` | `browser-auth.ts:244` | App **必须一次性导航到 launchUrl**，不能拆成两步 |
| 官方 cookie 名 = `dsh-auth-` + `sha256(Host)`，**绑域名** | `browser-auth.ts:107` | 换域名 = 换 cookie，不能复用 |
| 官方 cookie 是 `HttpOnly; SameSite=Strict` | `browser-auth.ts:122` | 系统下载器带不上，第二版下载必须 App 内自建 OkHttp |
| 远端（非 loopback）访问时设置面板持久化降级为只读 | `ui-settings/src/client/index.ts:39` | **这是官方行为，不是 bug**，不要去"修" |

所以本 App 做的是：**用插件新增的机器可编程登录口 `POST /api/dsh/v1/mobile-login` 一次拿到「官方启动令牌 URL + 插件会话 cookie」，然后交给 WebView 一次导航完成两套鉴权。**

---

## 二、鉴权链路（这是整个方案的地基）

```
① POST https://<host>/api/dsh/v1/mobile-login
   body: { username, password, scheme: "https", host: "<域名>" }
   → 200 { ok, username, role,
           launchUrl: "https://<host>/?token=<启动令牌>",
           sessionCookie: { name: "dsh_postapi_session", value: "<sid.hmac>", maxAgeSeconds } }

② 把 sessionCookie 种进 WebView 的 CookieManager（domain 由 URL 推导，path=/）+ flush()

③ WebView.loadUrl(launchUrl)   ← 必须是【一次导航】，不能分两步
   → 官方 browserAuth 用 ?token= 铸官方签名 cookie（303 + Set-Cookie）
   → 落到 / ，此时官方 cookie 与插件 cookie 都在

④ 之后页面内所有 /api/* 请求自动携带 cookie，官方 RPC 全可用
```

**服务端契约出处**（插件仓库）：`<插件仓库>/plugins/dsh-postapi-bridge/lib/index.js`

- 分流点、handler、成功响应体均按符号名搜索：`isMobileLogin` / `handleMobileLogin` / `sendJson(res, 200`
- 失败语义：`400` 缺字段/非法 host·scheme，`401` 凭据错（不区分原因，防用户名枚举），`429` 按 IP 限流（5 次失败锁 10 分钟），`503` 读不到启动令牌
- 该端点**不要求 Bearer token**（它本身就是登录口），安全性由按 IP 限流兜底

---

## 三、如何构建

### 3.1 环境要求

| 项 | 版本 | 说明 |
| :--- | :--- | :--- |
| JDK | **17** | AGP 8.5 要求 JDK 17 |
| Android SDK | **compileSdk 35** | 需装 `platforms;android-35` 与 `build-tools;35.0.0` |
| Gradle | **8.7+** | 由 wrapper 提供 |
| AGP | 8.5.2 | 已在根 `build.gradle.kts` 声明 |
| Kotlin | 2.0.21 | 同上 |

### 3.2 构建步骤

> ⚠️ **本仓库是在没有 Android SDK 的机器上写的**（编写环境确认无 `sdkmanager` / `gradlew` / `adb`）。
> 因此**从未编译过**。下面的步骤需要在装有 Android SDK 的机器上执行，首次构建大概率需要修第六节 R1 列出的点。

```bash
# 0. 进入工程根目录（本文件所在目录）
cd dsh-postapi-android

# 1. 仓库里没有 gradle wrapper 二进制（避免提交 jar）。首次构建先生成：
gradle wrapper --gradle-version 8.7
#    若机器上没有独立 gradle，用 Android Studio 新建空项目，复制其 gradle/ 与 gradlew* 过来。

# 2. 指向 SDK（二选一）
export ANDROID_HOME=$HOME/Android/Sdk
#    或在工程根建 local.properties（不要提交到仓库）：sdk.dir=/home/<you>/Android/Sdk

# 3. 构建 debug 包
./gradlew assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk

# 4. 装到真机（需开启 USB 调试）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3.3 release 包

```bash
# release 需要签名配置。本仓库没有也不应提交 keystore。
# 在 ~/.gradle/gradle.properties 里放：
#   DSH_POSTAPI_STORE_FILE=/absolute/path/to/release.jks
#   DSH_POSTAPI_STORE_PASSWORD=...
#   DSH_POSTAPI_KEY_ALIAS=...
#   DSH_POSTAPI_KEY_PASSWORD=...
# 然后在 app/build.gradle.kts 的 release 块里接 signingConfigs（当前未接线，MVP 只发 debug）。
./gradlew assembleRelease
```

---

## 四、如何配置域名

### 4.1 运行时配置（推荐）

登录页的「服务地址」输入框**就是**配置入口。默认预填值是 `http://127.0.0.1:3080` ——
**这是占位，不是可用的服务**。首次使用请改成你自己的 DSH 地址（`README` 开头已强调）。

上次成功登录用过的地址会被记住（存在非机密的 `dsh_login_prefs` 里），
以后打开自动预填。**改地址 = 换服务器 → 会清空旧账号的保存凭据**（见 §5.8）。

格式规则（客户端与服务端双重校验）：

| 输入 | 结果 |
| :--- | :--- |
| `https://dsh.example.com` | ✅ |
| `dsh.example.com` | ✅ 自动补 `https://` |
| `http://192.168.1.10:3080` | ✅ 内网联调常用（**仅 debug 包允许明文**） |
| `https://dsh.example.com/` | ✅ 尾部斜杠自动去掉 |
| `https://dsh.example.com/login` | ❌ 带路径直接拒绝（比静默裁剪更诚实） |
| `https://dsh.example.com/?x=1` | ❌ 带查询串直接拒绝 |

### 4.2 为什么必须让用户填 authority

`launchUrl` 由**服务端**用 `scheme + host` 拼成。经 frp / nginx 反代时服务端看到的 `Host` 头可能是内网地址（`127.0.0.1:3080`），拼出来的 `launchUrl` 手机根本访问不到。

所以客户端在请求体里显式回传 `scheme` 与 `host`（服务端优先级：请求体 > `X-Forwarded-Host` > `Host`）。这是唯一可靠的办法。

### 4.3 ⚠️ 域名必须与服务端 `--trusted-host` 一致

官方 `/api` 门禁有 Host 围栏（`api-request-trust.ts:103`）：非 loopback 的 Host 必须在 `trustedHosts` 里，否则 **403**（连 cookie 都不看）。

当前服务的启动参数（`<supervisor 配置目录>/dsh-web.conf`）：

```
--trusted-host <你的域名>  --trusted-host <备用域名>…
```

⇒ **App 里填的域名必须与这份清单里的某一条完全一致**。否则登录可能成功（`mobile-login` 走插件路由，不受 Host 围栏约束），但 WebView 内的 `/api/*` 全部 403 —— 症状是"登录成功但页面一直转圈/空白"。

---

## 五、代码结构与关键决策

```
dsh-postapi-android/
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/cd/ptrel/dshpostapi/
        │   │   ├── Model.kt            登录结果数据类
        │   │   ├── LoginError.kt       失败分类（sealed class）
        │   │   ├── ApiClient.kt        mobile-login 的 OkHttp 客户端 + 地址解析
        │   │   ├── LoginActivity.kt    原生登录页
        │   │   └── WebViewActivity.kt  WebView 容器（种 cookie + 一次导航）
        │   └── res/                    layout / values / drawable / mipmap
        └── debug/
            └── AndroidManifest.xml     debug 专用明文流量开关
```

### 5.1 为什么用 View（ViewBinding）而不是 Compose

- MVP 只有两个页面，Compose 要多引入 `compose-bom` + 编译器插件 + runtime，APK 变大、启动变慢；
- WebView 本身就是 View 体系，混用两套 UI 工具链没有收益；
- 出错面更窄：ViewBinding 是编译期生成的类型安全绑定，没有运行期反射。

### 5.2 为什么只用 appcompat / material / constraintlayout / okhttp

JSON 用 Android 平台自带的 `org.json`，不引 Retrofit / Moshi / kotlinx-serialization：整个 MVP 只有一个 POST 和一个扁平响应结构，序列化框架的收益是负的。

### 5.3 `usesCleartextTraffic` 的双保险

```kotlin
// app/build.gradle.kts
debug   { manifestPlaceholders["usesCleartextTraffic"] = "true"  }
release { manifestPlaceholders["usesCleartextTraffic"] = "false" }
```
```xml
<!-- AndroidManifest.xml：占位符注入，不写死 -->
android:usesCleartextTraffic="${usesCleartextTraffic}"
```
```xml
<!-- src/debug/AndroidManifest.xml：debug 源集里再显式写一次 true（tools:replace） -->
<application android:usesCleartextTraffic="true" tools:replace="android:usesCleartextTraffic" />
```

**为什么两层**：`manifestPlaceholders` 在自定义 variant 或 IDE 某些构建路径上可能落到别的值；debug 源集里显式覆盖是最后一道保险，而这份文件**不参与 release 构建**，不可能影响正式包。

### 5.4 cookie 为什么必须 `flush()`

`CookieManager` 的写入是**异步落盘**的。`setCookie()` 之后立刻 `loadUrl()`，本次导航有概率读不到这枚 cookie ⇒ 插件接口 401。`flush()` 让写入立即生效。这是 `WebViewActivity.seedCookie()` 里最容易被删掉、删掉后最难查的一行。

### 5.5 cookie / 密码怎么存（v0.3.0 起：加密落盘）

**v0.2.0 的语义**是"一次登录 = 一次会话，重启要重新登录"。实际使用中等于每天都要重敲
一遍账号密码 —— 这是用户提出「账号本地记忆」的直接原因。

v0.3.0 起用 `androidx.security:security-crypto:1.1.0` 的 **EncryptedSharedPreferences**：

| 存储 | 文件 | 内容 | 加密 |
| :--- | :--- | :--- | :--- |
| `AccountStore` | `shared_prefs/dsh_account_secure.xml` | host / username / **password** / cookieName / **cookieValue** / cookieExp / lastLogin | ✅ AES-256-GCM（主密钥在 Android Keystore，`MasterKey.AES256_GCM`） |
| `LoginDefaults` | `shared_prefs/dsh_login_prefs.xml` | 上次用的服务地址（公开信息） | ❌ 普通 SharedPreferences（MODE_PRIVATE） |
| `UpdatePrefs` | `shared_prefs/dsh_update_prefs.xml` | 「稍后」跳过的新版本号 | ❌ 无凭据 |

**为什么把 host 单独放在非加密库**：退出登录要清空账号，但"用户连的是哪台服务器"没有
理由忘；更重要的是 Keystore 一旦失效（换机/系统升级），加密库整份作废，此时仍能预填
地址，用户不必重新回忆域名。两个库都不含可被冒用的凭据 / 一个库密钥失效不影响另一个。

**主密钥不可导出 ⇒ 备份是致命的**，所以 `AndroidManifest.xml` 设
`android:allowBackup="false"` **并**加 `res/xml/data_extraction_rules.xml`
（API 31+ 的 cloud-backup 与 D2D 两条路径都显式 exclude）。
只设 allowBackup 在 API 31+ 上对"设备间迁移"不够，两份都要。

### 5.8 账号本地记忆的设计（v0.3.0 核心）

**代码分工**：`AccountStore`（加密存储）→ `LoginFlow`（启动/重登决策）→
`LoginActivity`（呈现）。三层分开是为了让 7 条互相冲突的边界**能一眼看懂**：

| 层 | 文件 | 职责 | 不做的事 |
| :--- | :--- | :--- | :--- |
| 存储 | `AccountStore.kt` | 加解密、Keystore 异常降级、字段读写 | 不判断"该不该清"（那是策略） |
| 决策 | `LoginFlow.kt` | 启动分支、自动重登、错误分类 → 清/不清 | 不碰 View |
| 呈现 | `LoginActivity.kt` | 预填、错误文案、跳转 | 不直接调 `EncryptedSharedPreferences` |

#### 启动流程

```
App 启动（DshApp 后台预热 Keystore）
  ↓
LoginFlow.decideOnStart（后台线程读存储）
  ├─ 无账号            → 登录页（预填 LoginDefaults 里上次的 host）
  └─ 有账号
        ├─ cookie 未过期 → 直接进 WebView（加载 host 根路径，复用 CookieManager 里的 cookie）
        └─ cookie 过期/无
              ├─ 有密码 → 自动重登（用户看不到登录页）
              │     ├─ 成功      → 存新 cookie → 进 WebView
              │     ├─ 401/403   → **清密码 + 清 cookie** → 登录页（用户名预填、密码空）
              │     ├─ 429       → 不清，提示稍后再试（页面上有「重新登录」）
              │     ├─ 5xx/404   → 不清，提示服务端问题（可重试）
              │     └─ 网络/超时 → **不清**，提示重试（cookie 也留着）
              └─ 无密码 → 登录页（用户名预填、密码空）
```

#### 🔴 边界处理表（每条都有对应代码，改动前对照此表）

| 场景 | 正确行为 | 实现位置 |
| :--- | :--- | :--- |
| 密码已改 / 账号被禁用（401） | 清密码 + 清 cookie，回登录页 | `LoginFlow.kt` HTTP 401 分支 → `AccountStore.clearCredentials` |
| 账号被禁用（403） | 同上 | `LoginFlow.kt` `Http(403)` 分支 |
| **网络错误**（非 401） | **不清密码、不清 cookie**，提示重试 | `LoginFlow.kt` `Network` / `Timeout` 分支（无任何清空调用） |
| **429 限流** | **不清密码**，提示稍后再试 | `LoginFlow.kt` `TooManyAttempts` 分支 |
| **用户改服务地址** | 视为换服务器 → 清空旧凭据 | `LoginActivity.onHostEdited`（输入时）+ `submit()` 里的第二道闸 |
| **用户点「退出登录」** | 清全部存储 + 清 CookieManager + 清 WebStorage → 回登录页 | `WebViewActivity.performLogout` |
| **Keystore 异常**（换机/恢复出厂/系统升级） | 解密失败 → 删库+删密钥 → 当没账号，**不崩** | `AccountStore.open` / `load` 的 catch → `resetStorage` |
| 首次登录成功 | 存 host/username/password/cookie/cookieExp | `AccountStore.saveAfterLogin`（唯一写入点） |

**为什么"网络错误"不能清密码**：地铁里没信号时启动 App，若把密码清了，用户下次
还得重输 —— 而这个功能的全部意义就是"不用反复输入"。只有服务端**明确否定**这组凭据
（401/403）才算数。同理 429 是限流不是凭据错，清了等于让用户替服务端的限流买单。

#### 与「检查更新」的关系（不要造两套 cookie 存储）

v0.2.0 的 `UpdateSession` 是**独立的内存单例**。v0.3.0 把它改成
**`AccountStore` 的只读代理**（`UpdateSession.get(context)` 每次现读）：

- 单一真相：cookie 只在加密存储里；
- 「退出登录」清了存储，`UpdateSession.get` 自然返回 null，不需要额外协调；
- `set()` / `clear()` 保留为空操作 + `@Deprecated`，防止新代码再长出一套。

#### 安全红线

| 项 | 做法 |
| :--- | :--- |
| 密码进日志 | ❌ 全仓库 `Log.*` 只有异常类名 / 状态，**零字段值**（可 `grep -rn "Log\." app/src` 复核） |
| 密码进 Intent | ❌ 密码**完全不出 `LoginActivity`**；跳转只传 `launchUrl` + cookie（同进程、`exported=false`） |
| OkHttp 日志拦截器 | ❌ 全仓库**没有** `HttpLoggingInterceptor`（登录响应含 cookie，一旦加了必然泄） |
| 加密算法 | `MasterKey.AES256_GCM` + `AES256_SIV`(key) / `AES256_GCM`(value) |
| 云端备份 | `allowBackup="false"` + `data_extraction_rules.xml` 双保险 |

#### 已知取舍（诚实说明）

1. **cookie 过期时间的来源**：服务端只下发 `maxAgeSeconds`，所以 `cookieExp` 是
   「登录时刻 + maxAge」的**保守估算**。服务端若滚动续期（`sessionMaxAgeHours`
   默认 24h，每次请求续期），我们会**低估**有效期 ⇒ 多一次自动重登。用户无感，可接受；
   反过来（高估）会让用户进 WebView 看到 401 错误页，那是更差的结果。
2. **退出登录后密码无法从内存"擦除"**：`String` 不可变，我们只能尽快丢弃引用
   （`clearPendingCredentials`）。要做到真擦除需要 `CharArray` 贯穿整个 OkHttp
   请求链路，代价远大于收益。
3. **自动重登与手动登录共用同一个限流额度**：服务端按 IP 计 5 次失败锁 10 分钟。
   自动重登只会在启动时触发一次（`autoLoginInFlight` 防并发），所以正常使用触不到上限；
   但如果用户密码被改后**不去改密码**而反复重开 App，第一次 401 就已经清了密码，
   不会无限重试。

### 5.6 ⚠️ 系统栏留白（targetSdk 35 edge-to-edge）

**症状（v0.1.0 的真实缺陷）**：WebView 页顶部被手机状态栏盖住。

**根因**：`targetSdk = 35` ⇒ **Android 15 (API 35) 起强制 edge-to-edge**，
系统把窗口绘制边界推到状态栏/导航栏下方，应用内容从 `y = 0` 开始画。
主题里的 `statusBarColor` 在 API 35 上**不再生效**，也不会给内容让位。

**修法**：`WindowInsetsHelper.applySystemBarPadding(rootView)`（方式 1：`ViewCompat.setOnApplyWindowInsetsListener`）。

为什么不用 `android:fitsSystemWindows="true"`：
- 它的行为依赖父容器，且**不处理 ime（键盘）**；
- 键盘弹出时输入框仍会被挡，需要另写一套逻辑；
- 显式监听器能同时覆盖 systemBars 与 ime，逻辑集中在一处。

**四个实现要点**（每一条都对应一个真实陷阱）：

| 要点 | 不这么做会怎样 |
| :--- | :--- |
| 用 **padding** 而非 margin | margin 会让容器背景不铺到状态栏，透出 windowBackground，两层色不同就出现色带 |
| 显式 `setDecorFitsSystemWindows(window, false)` | API 24~34 上窗口**默认自己避让**，再叠 padding = 双重留白（Android 15 对、7~14 错） |
| 分别取 `systemBars` 与 `ime`，bottom 用 `maxOf` | 用 `or` 合并成位掩码后无法断言取 max 还是相加；部分 ROM 的 ime 已含导航栏高度，会算两遍 |
| 记录 base padding 再叠加 | 每次 inset 变化都在上次结果上加，转屏/切深色后留白越来越大 |

**覆盖范围**：`WebViewActivity`（`rootFrame`）与 `LoginActivity`（`loginScroll`）都处理了 ——
两个页面同一个 targetSdk，只修一个会留下第二个同类 bug。

**顺带修的前景色问题**：接管留白后状态栏区域显示的是 App 背景色，
若图标颜色不跟随，"白图标画在白底上"就看不见。
`applySystemBarIconAppearance()` 按 `uiMode` 设定（与 `values-night` 同源，不会错配）。

### 5.7 ⚠️ UA 为什么要专门处理

插件用 **UA 设备类别**做会话指纹的硬门（`lib/index.js:281`，v0.12.2 起 IP 漂移放行、UA 类别是唯一硬门）。

- `ApiClient` 登录时显式发一个含 `Android` + `Mobile` 的浏览器式 UA；
- `WebViewActivity` **不覆盖** WebView 默认 UA（同样含 `Android` + `Mobile`）；
- 两边都落在 `uaDeviceClass()` 的 `mobile` 桶，指纹才一致。

⚠️ 若在 `ApiClient` 里改成 `DSH-PostAPI/0.1` 这种裸 UA，`uaDeviceClass()` 会归为 `other`，而 WebView 是 `mobile` ⇒ 会话第一跳就失配 ⇒ 连错 3 次后会话被**吊销**。这是本仓库最容易踩的坑之一。

---

## 六、需要真机验证的清单（按风险排序）

> 下面每一条都是**我无法在本机验证**的。编写环境没有 Android SDK / Gradle / adb，**代码从未编译过**。

### 🔴 R1（最高风险）首次编译可能失败 —— Gradle / AGP / Kotlin 版本组合

**为什么风险最高**：这是唯一一条"根本跑不起来"的风险，且无法靠阅读代码排除。

已知不确定点：

1. **仓库里没有 `gradle/wrapper/`** —— 刻意不提交 wrapper jar（二进制）。首次要执行 `gradle wrapper --gradle-version 8.7`，或从 Android Studio 空项目复制 `gradle/` 与 `gradlew*`。
2. `kotlinOptions { jvmTarget = "17" }` —— Kotlin 2.0 起推荐改用 `compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }`。旧的 `kotlinOptions` 块在 Kotlin 2.0.21 仍可用（deprecated 警告），但若你的 AGP/KGP 组合把它移除，会编译失败。
3. `Widget.Material3.TextInputLayout.OutlinedBox` —— 依赖 Material 1.12.0。若改了 Material 版本，这个 style 名可能不存在。
4. `data object`（`LoginError.kt`）—— 需要 Kotlin **1.9+**。当前 2.0.21 OK；若降级 Kotlin，要改回 `object Timeout : LoginError()`。

**验证方法**：`./gradlew assembleDebug`，把完整报错贴出来。

### 🔴 R2（最高风险）`launchUrl` 的 `Host` 头必须落在 `trustedHosts` 里

**为什么风险高**：这是唯一一条"编译通过、安装成功、登录成功，但页面一片空白"的风险，而且**代码全写对也一样会发生**。

官方 `/api` 门禁（`api-request-trust.ts:103`）判据：`Host` 头必须是 loopback，**或**在 `trustedHosts` 清单里。不在 → **403**（连 cookie 都不看）。

服务器只信任它在 `--trusted-host` 里列出的那几个域名（具体清单以你部署时的
`<supervisor 配置目录>/dsh-web.conf` 为准；本仓库不再记录任何真实域名）。

**验证方法**：
1. App 里填你的 DSH 地址（必须与 `trusted-host` 中的某一项**完全一致**）；
2. 登录成功后看 WebView 能否正常显示会话列表；
3. 若一直转圈/空白：手机连 USB → 电脑 Chrome 打开 `chrome://inspect` → 选中本 App 的 WebView → Console。
   - `/api/*` 返回 **403** = Host 围栏没过（域名不在 trustedHosts）；
   - `/api/*` 返回 **401** = 围栏过了但 cookie 没带上（见 R3）。

这两者含义完全不同，**排查方向也不同**，不要混。

### 🟠 R3 插件会话 cookie 在 WebView 里是否真的带上了

**风险点**：`CookieManager.setCookie(origin, cookie)` 的 domain 推导行为在不同 WebView 版本上不一致。特别是我们**没写** `domain=`（交给 CookieManager 从 URL 推导，避免手写前导点出错），也**没写** `Secure`（内网联调可能是 http）。

**验证方法**：`chrome://inspect` → Network，看 `/api/dsh/v1/*` 请求的 `Cookie` 请求头里是否有 `dsh_postapi_session=...`。
**若没有**：把 `seedCookie()` 的 cookie 串改成显式 `domain=<host>` 再试，并在 README 记录哪版 WebView 需要这样。

### 🟠 R4 官方签名 cookie 与插件 cookie 的有效期不同步

- 官方 cookie：`Max-Age = cookieMaxAgeDays`，**默认 30 天**（`browser-auth.ts:122`，`index.ts:128`）；
- 插件会话：`sessionMaxAgeHours`，**默认 24 小时**，滚动续期（`lib/index.js:1199`）；

⇒ 存在"官方 cookie 还在、插件 cookie 已过期"的窗口：页面能打开，但插件功能（账号管理、第二版的下载）401。

**验证方法**：登录后放置超过 24 小时（或临时把 `sessionMaxAgeHours` 调成很小）再打开，确认表现与预期一致。

### 🟡 R5 同一账号多端登录是否互踢

插件按 `uaDeviceClass`（desktop / mobile / tablet / other）绑定会话，每次登录发一张新 sid。手机 App 与手机浏览器都是 `mobile`，**不会**因类别相同而互踢（互踢只发生在同一 sid 被异地/异类别使用）。

**但要确认**：反复登录会在 `sessions.json` 里累积会话记录；且指纹失配累计 3 次会**吊销**该 sid。

**验证方法**：App 内登录 → 退出重登 3 次 → 查 `$DSH_HOME/server-auth/sessions.json` 与 `access-log.jsonl` 有无 `session-fingerprint-mismatch` 事件。

### 🟡 R6 `mixedContentMode = COMPATIBILITY_MODE` 是否符合你的安全策略

为内网联调放宽了混合内容（https 页面加载 http 子资源）。release 的 `usesCleartextTraffic=false` 只拦**主文档级**明文，不拦子资源。

**若不允许**：删掉 `WebViewActivity.configureWebView()` 里那一行即可（代码已注明位置）。

### 🟡 R7 站外链接跳转

实现的是"同源留在 WebView，不同源交系统浏览器"。`shouldOverrideUrlLoading` 新旧两个签名都实现了（部分 WebView 内部路径仍调旧签名）。

**验证方法**：会话里发一条含外链的消息，点击它 → 应跳系统浏览器而不是在 App 内跳走。

### 🟢 R8 深色模式与自适应图标

`values-night/` 只覆盖需要反转的颜色与状态栏前景。自适应图标 `mipmap-anydpi-v26` 只在 API 26+ 生效，24/25 走 `mipmap-anydpi/` 的 vector 兜底。

**验证方法**：系统切深色 → 登录页与错误页不应出现"白底白字"；在 API 24/25 模拟器上确认图标非空白。

### 🟡 R10 系统栏留白（v0.1.1 新增，需重点看）

代码已按标准做法实现，但仍需真机确认以下三件**代码无法验证**的事：

1. **状态栏高度因 ROM 而异**（含刘海/挖孔/灵动岛机型）。padding 取值来自
   `WindowInsetsCompat.Type.systemBars().top`，这是系统给的真实值，理论上各 ROM 都对；
   但要确认在**带刘海**的机型上没有出现"留白过大"（个别 ROM 会多报一段）。
2. **键盘弹出时底部留白**（API 24~29 风险最高）。`setDecorFitsSystemWindows(false)` 后，
   低版本上的 `adjustResize` 语义会变成"上报 ime inset 而不是 resize 窗口"，
   我们靠 ime inset 接管。需确认：**登录页键盘弹出时「登录」按钮能被滚到键盘上方**。
3. **手势导航条**（Android 10+）与三键导航的 bottom inset 不同。
   需确认三键导航下 WebView 底部内容没有被导航栏盖住。

**排查手段**：`WindowInsetsHelper.debugInsets(view)` 会返回实际
`systemBars.top` / `ime.bottom` 数值，接一个临时日志即可看到真机上报了什么。

### 🟢 R9 ViewBinding 类名

`activity_webview.xml` → 生成的绑定类名是 **`ActivityWebviewBinding`**（小写 w，因为按下划线分词后是 `Activity` + `Webview`），**不是** `ActivityWebViewBinding`。代码里已按此写（`WebViewActivity.kt:19,50,57`）。若 R1 修复时改了布局文件名，这一处必须同步改。

### 🔴 R11 v0.3.0 新增：账号本地记忆（需真机确认，按风险排序）

下面每条都是**代码写得对也可能在真机上不对**的，只有实测能排除：

1. **🔴 Keystore 首次初始化耗时** —— `EncryptedSharedPreferences` 首次要生成
   AES-256-GCM 主密钥（Tink keyset），低端机上可能 500ms+。
   已做：`DshApp` 在后台线程预热。**要确认**：冷启动（进程被杀后）点图标到看到界面
   是否明显变慢；如果慢，说明预热没赶上（`LoginFlow.decideOnStart` 仍会在后台读，
   界面上应当先显示"预填过的登录页"而不是白屏）。
2. **🔴 换机 / 恢复出厂后的降级** —— 这是最容易崩的路径。
   **验证方法**：`adb shell pm clear cd.ptrel.dshpostapi` 不算（那会连文件一起清）。
   要模拟"文件在、密钥没了"：备份 APK 后卸载重装不行（文件也没了）。
   最接近的做法：`adb shell run-as cd.ptrel.dshpostapi` 手动破坏
   `shared_prefs/dsh_account_secure.xml`（改一个字节），然后重开 App。
   **预期**：不崩，回到空登录页，日志是 `AccountStore: decrypt failed (...)`。
3. **🟠 自动重登后 cookie 是否真的生效** —— 自动重登路径的 cookie 由
   `LoginFlow.seedSessionCookie` 种入（不是 `WebViewActivity.seedCookie`）。
   两条种 cookie 的代码是复制关系，**必须确认**自动重登后
   `chrome://inspect` → Network 里 `/api/dsh/v1/*` 请求带了
   `Cookie: dsh_postapi_session=...`。
4. **🟠 冷启动跳过登录页（resume 路径）** —— 加载的是 host 根路径，
   靠 CookieManager 里的官方签名 cookie（HttpOnly，跨进程持久）鉴权。
   **要确认**：杀进程后重开，能否直接看到会话列表（而不是官方登录页）。
   若看到官方 `/login`，说明官方 cookie 没在 CookieManager 里长期存活 ——
   那种情况下应退化为"总是走自动重登"（把 `hasFreshCookie()` 的判断去掉即可）。
5. **🟠 「退出登录」是否真的清干净** —— 三处都要看：
   `adb shell run-as cd.ptrel.dshpostapi cat shared_prefs/dsh_account_secure.xml`
   （应无 password/cookie 字段或文件为空）、重开 App 是否要求登录、
   WebView 里是否还停在会话页。
6. **🟡 「退出」悬浮按钮与网页 UI 是否冲突** —— 放在右下角是为了避开官方前端顶部按钮。
   **要确认**：在会话列表、聊天页、侧边栏展开时都不会挡住官方自己的按钮。
7. **🟡 改地址时的清除时机** —— 输入框 `afterTextChanged` 里做归一化比较。
   **要确认**：从 A 改成 B（真改）时账号被清；把 `https://a.com` 改成 `a.com` 或
   加尾斜杠（等价地址）时**不**清。
8. **🟢 429 场景** —— 需要连续 5 次错误密码才触发，且会锁 10 分钟。建议在
   测试服上调小阈值验证"429 不清密码"。**不要**在生产环境反复试。

### 🟢 R12 v0.3.0 构建期已确认（不需要真机）

- 依赖可解析：`androidx.security:security-crypto:1.1.0` → `tink-android:1.8.0` + `gson:2.8.9`
- 编译无新增警告（`AccountStore` 的 deprecation 已 `@file:Suppress` 并附理由）
- `data_extraction_rules.xml` 被 aapt 正常打包（`assembleDebug` 通过即证明）

---

## 七、用户在手机上的操作步骤

1. **安装 App**：`adb install -r app-debug.apk`，或直接把 APK 传到手机点击安装（需允许"安装未知来源应用"）。

2. **打开 App**：
   - **首次**：看到登录页，三个输入框。「服务地址」预填 `http://127.0.0.1:3080`，
     **必须改成你自己的 DSH 地址**（⚠️ 且必须与服务端 `--trusted-host` 一致，见 §4.3）。
   - **之后**：如果保存过账号，App 会**自己登录**并直接打开网页（看不到登录页）。
     若 cookie 还没过期，连登录请求都不发（更快）。

3. **点「登录」**：
   - 成功 → 直接跳到 DSH 网页版会话列表；
   - 失败 → 错误提示下方会有一行小字（`HTTP 401` / `network: ...`），那是排查用的原始信息，**不要忽略**。

4. **对照提示处理**：

   | 提示 | 含义 | 你该做什么 |
   | :--- | :--- | :--- |
   | 用户名或密码错误 | 401，服务端刻意不区分原因 | 检查账号密码；连错 5 次会锁 10 分钟 |
   | 尝试次数过多，请 10 分钟后再试 | 429，按 IP 限流 | 等 10 分钟，或换网络（换出口 IP） |
   | 服务端暂时不可用 | 503，读不到启动令牌 | 联系管理员检查 `dsh web` 日志 |
   | 该地址上没有登录接口 | 404，域名/反代配错 | 检查域名，确认插件已加载 |
   | 无法连接服务器 | 网络层（DNS/连接被拒/TLS） | 检查网络，确认域名可访问 |

5. **进入网页版后**：与电脑浏览器体验一致（会话列表、聊天、发消息）。

6. **返回键**：
   - 网页内有历史 → 回退一页（SPA 里点了会话再返回就是这个）；
   - 已在首页 → 第一次按提示"再按一次返回键退出"，2 秒内再按才退出。

7. **页面加载失败**：显示错误页 + 原始错误描述，底部按钮：
   - 「重试」：重新加载。**401/403 时这个按钮会隐藏** —— 因为 launchUrl 里的 token 只能兑换一次，重试拿到的还是已作废的 token，重试没有意义；
   - 「返回登录」：回登录页重新取一张 launchUrl。

8. **换账号 / 退出登录**（v0.3.0）：
   - 网页底部右侧有一个半透明的「退出」小按钮 → 确认后清空本机账号并回登录页；
   - 会话失效的错误页上也有「退出登录」按钮；
   - ⚠️ 「退出登录」清除保存的密码与 cookie；「返回登录」只是重新登一次，账号仍保留。

---

## 八、已知限制（第一版刻意不做）

| # | 限制 | 为什么 | 何时处理 |
| :--- | :--- | :--- | :--- |
| 1 | **文件下载** | 官方 cookie 是 HttpOnly+SameSite=Strict，系统 DownloadManager 带不上；要 App 内用 OkHttp 走 `file-ticket` → `file/<token>` | 第二版 |
| 2 | ~~不记忆登录~~ → **已实现**（v0.3.0） | EncryptedSharedPreferences 加密保存账号密码 | ✅ 已完成 |
| 3 | **不能多域名并存**（改地址会清旧凭据） | 多套「地址+账号」是额外状态机；单账号场景下清空是更安全的选择（见 §5.8） | 视需求 |
| 8 | 会话 cookie 被吊销后，"返回登录"需**重新输入密码**（不会自动重登） | 自动重登只发生在冷启动；从 WebView 返回是用户主动路径，两条入口并存会产生歧义。文案已明确提示 | 视需求 |
| 4 | **没有通知 / 保活 / 离线缓存** | 不在 MVP 范围 | 视需求 |
| 5 | **只支持单账号** | 同上 | 视需求 |
| 6 | **release 未接线签名配置** | 仓库不能提交 keystore | 发布前 |
| 7 | **设置面板在远端是只读的** | **官方行为**（`isLoopback=false` ⇒ `persistence='memory'`），不是 bug，不要去"修" | 永不 |
| 9 | **Android 10 及以下：软键盘弹出时页面不上移**（输入框可能被键盘盖住） | **ROM 行为差异，四种方案均无效**。详见 §八之一。**不打算修** —— 收益远低于成本 | 不处理 |

---

## 八之一、Android 10 及以下的键盘让位问题（**已知，不修**）

### 症状

在 **Android 10 及以下**的某些 ROM（实测：华为 VRD-AL10 / EMUI / Android 10）上：

```
点输入框 → 软键盘弹出 → 页面完全不动 → 输入框被键盘盖住
```

**Android 11 及以上正常**（用 `translationY` 自己平移，布局零变化）。

### 试过且全部失败的方案

| 版本 | 方案 | 结果 |
| :--- | :--- | :--- |
| v0.4.1 | `adjustResize` | 改窗口高度 → 前端重排，但**不上移** |
| v0.4.3 | `adjustPan` + `handleIme=false` | 页面完全不动（与 `setDecorFitsSystemWindows(false)` 语义冲突） |
| v0.4.5 | `adjustNothing` + `translationY = -ime.bottom` | `ime.bottom` 恒为 0 ⇒ 平移量为 0 |
| v0.4.10 | Android 10 分支改用 `padding`（依赖同一个 `ime.bottom`） | 同样为 0 ⇒ 无效果 |
| v0.4.11 | 完全不接管 insets，运行时覆写回 `adjustResize`，交还系统 | **仍不动** |

**根因判断**：该 ROM 既不响应 `adjustResize` 的窗口缩短，也不上报 `ime` inset。
我们这边能做的四种「让位责任人切换」（系统 / padding / translationY / 混合）全部无效。

### 为什么不再修

1. **收益低**：Android 10 及以下的设备逐年减少；且该问题只影响「看输入框」，不影响功能（可滚动查看、可正常发送）。
2. **成本高**：需要在这台机器上反复试错，而诊断手段（`adb logcat`）用户现场不具备。
3. **可能无解**：如果 ROM 真的一不缩窗、二不报 inset，App 层拿不到任何键盘高度信号 —— 那就不是「换个 API 调用」能解决的。

### 用户可以做什么

| 做法 | 效果 |
| :--- | :--- |
| **升级系统到 Android 11+** | ✅ 彻底解决（那条路径已验证可用） |
| **换一个输入法** | ⚠️ 部分第三方输入法（如 Gboard）的 inset 上报行为更好，可能有效，未验证 |
| **改用「完整模式」以外的方式输入** | 例如先在别处写好再粘贴 |

### 诊断入口（如需复查）

```bash
adb logcat -s WebViewLayout
```

输出含 `verdict=` 结论字段（`insets-ok` / `system-resized` / `insets-broken` / `no-keyboard`）。
**若想重新尝试修复，先拿这个输出**，否则不要盲改 —— 这条已经试错四次。

---

## 九、第二版要用的接口（MVP 不依赖）

```
GET  /api/dsh/v1/files?sessionId=<sid>   → { ok, files:[{seq,index,turn,time,path,name,description,size}] }
POST /api/dsh/v1/file-ticket { sessionId, seq, index }
     → { ok, url:"/api/dsh/v1/file/<token>", activateDeadline, activateWindowHours, sessionHours, filename, size }
GET  /api/dsh/v1/file/<token>            → 文件流（支持 Range / 206）
```

⚠️ 清单路径是 **`/files`**（v0.12.3 起），不是 `/presented` —— 后者与宿主 `present.open` 路由冲突会被劫持（`lib/index.js:1845`）。

票据规则：**签发 → 48h 内可触发 → 触发起 5h 内不限次数（含 Range 续传）→ 过期作废**。

这些接口在 v0.12.2 里正调整鉴权（人类通道认 cookie），当前线上可能 401 —— **MVP 刻意不依赖它们**。

---

## 十、已知服务端配置现状（只读确认，未改动）

| 项 | 现状 |
| :--- | :--- |
| `dsh-web` 启动参数 | `--profile web --trusted-host <你的域名> …`（按实际部署核对） |
| 插件版本 | `dsh-postapi-bridge@1.8.0` |
| 启动令牌来源 | 从 `<DSH 日志目录>/dsh-web.log` 解析 `dsh web: ...?token=...`（60s 缓存） |
| 插件会话有效期 | `sessionMaxAgeHours` 默认 24h，滚动续期 |
| 官方 cookie 有效期 | `cookieMaxAgeDays` 默认 30 天 |

---

## 十一、检查更新（v0.2.0）

```
① 登录成功（LoginActivity.openWebView）
   → UpdateSession.set(host, cookieName, cookieValue)   ← 仅内存
   → Updates.checkQuietly()                              ← 异步，5s 超时，失败静默
② WebView 首次 onPageFinished → Updates.checkQuietly()  ← 去抖 5s，只查一次
③ GET /api/dsh/v1/app-version（Cookie: <插件会话>，UA 必须 mobile 类别）
   → 200 且 code > BuildConfig.VERSION_CODE → 弹窗
   → 503 / 网络错误 / JSON 非法 → 只记 Log（不打扰用户）
④ 「稍后」→ SharedPreferences 记 skipped_code，同版本不再提示
   「立即更新」→ 下载到 <cacheDir>/updates/dsh-postapi-<code>.apk
        · .part 临时文件 → 收尾 fsync → sha256 校验 → rename
        · 进度对话框：已下载 / 总大小 / 百分比（可取消）
⑤ 安装前 canRequestPackageInstalls() 检查 → 未授权引导去设置页
   → FileProvider.getUriForFile(<applicationId>.fileprovider)
   → ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION
```

**几个容易改错的地方**

| 点 | 为什么 |
| :--- | :--- |
| `buildFeatures.buildConfig = true` | AGP 8 默认关闭 BuildConfig，不打开无法读 `VERSION_CODE` |
| 下载不用 DownloadManager | 它在独立进程，带不上我们的会话 cookie，也无法在交安装器前做 sha256 |
| UA 必须与登录同属 mobile | 插件用 UA 类别做会话指纹硬门，失配累计 3 次吊销 sid（README §5.7） |
| 校验通过前文件是 `.part` | 避免进程被杀后留下半截 APK，装它只会得到看不懂的解析错误 |
| file_paths.xml 只暴露 `updates/` | 写 `<cache-path path="." />` 等于把整个缓存暴露给任意应用 |

---

## 十二、版本

`0.3.0`（versionCode 4）—— **账号本地记忆（加密）**：EncryptedSharedPreferences 保存
host/username/password/cookie，启动免登录；cookie 失效自动重登并按 401/403 清密码；
新增「退出登录」；默认服务地址改为占位值 `http://127.0.0.1:3080`（清除仓库真实域名）；
新增 `.gitignore`。`UpdateSession` 改为从持久层读（不再单独存 cookie）。

`0.2.0`（versionCode 3）—— 检查更新 + 下载 + 安装（FileProvider / sha256 / 进度）。

`0.1.1` —— MVP：登录 + WebView + 系统栏留白（edge-to-edge 修复）。

`0.1.0` —— MVP 首版：登录 + WebView。
