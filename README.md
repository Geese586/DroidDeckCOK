<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
    <img alt="DroidDeck" src="artwork/droiddeck-banner-light.svg" width="100%">
  </picture>
</p>

# DroidDeckCOK · 中文本地一键安装版

**DroidDeckCOK**（COK = **C**hinese **O**ne **K**ey）是本仓库的名字，指这份**在官方 DroidDeck 上加了「首次本地一键安装」**的中文本地化改造版。**改名只落在构建产物的文件名上**：包名、版本号、应用名与上游完全相同（见「[二、安装 APK](#二安装-apk)」）。

离线一键安装包文件，百度网盘，永久分享：
链接: https://pan.baidu.com/s/1PY7ow7K81tvcqq0AAj8uNQ 提取码: 9527 

离线一键安装包文件，夸克网盘，永久分享：
链接：https://pan.quark.cn/s/3962c8e97fde
提取码：DRMQ

中文说明安装教学视频地址B站：
https://www.bilibili.com/video/BV1XAHd6PEKT/?vd_source=55a7f582a98d214a03f037a275fc90b3#reply320122768208

中文说明安装教学视频地址B站：
https://www.youtube.com/watch?v=ND505ZGazeA

DroidDeck 把 SteamOS 的体验搬到 Android：在 Adreno 掌机上跑 Valve 的 Steam 客户端（Big Picture 大屏界面），Windows 游戏走 Valve 的 ARM64 Proton。

本仓库是 [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) 的改造版，**跟随上游滚动更新，本文不绑定任何具体版本号**。功能与上游一致，只做了下面四件事，并因为密钥的原因**重新签了名**（见「[五、签名说明](#五签名说明为什么本仓库是自己签的)」）：

| # | 改动 | 说明 |
|---|---|---|
| 1 | **补齐必要文件** | 上游交给 CI 现场生成、仓库里从不提交的原生载荷（37 个文件，含 guest 的执行入口 `libproot.so`）被提取回源码树，并给构建加了一道硬关卡 |
| 2 | **中文字体支持** | 新增一步把 `fonts\` 里的中文字体装进 Linux 运行环境，让 Steam 大屏与桌面能正确显示中文 |
| 3 | **首次本地一键安装** | Steam 游玩页新增一个按钮，从手机上的一个文件夹把运行环境、Proton、Steam 客户端、桌面、字体一次装完，全程不依赖手机联网 |
| 4 | **更新页改为手动** | 本仓库的包是自有密钥签名的，官方的应用内更新装不到它上面，所以左侧「更新」页换成了说明原因、并给出去官方的 GitHub 发布页查看新版 |

> 上游项目没有独立官网。任何自称 DroidDeck 团队、提供下载链接的网站都不可信。

---

## 一、系统要求

- **Android 14（API 34）及以上**
- **Adreno 730 或更高**（Adreno 8xx 也可以）。**Adreno 6xx 为实验性支持**：DirectX 11 走 DXVK 2，可能可以运行；DirectX 12 游戏仍可能崩溃。Mali、Xclipse、PowerVR、Adreno 710 不支持
- arm64 设备，**不需要 root**
- 存储空间：Linux 运行环境约 3 GB；桌面与模拟器再约 1.1 GB
- 首次启动 Steam **之前**，必须在系统「开发者选项」里关掉 **「限制子进程」**（Restrict child processes）。Android 12 / 13 若没有这个开关，首次启动 Steam 时会出现一个「帮我修好」按钮自动完成设置

## 二、安装 APK

用本仓库构建出来的包是 `DroidDeckCOK-<版本>-release.apk`（`<版本>` 取自构建时的 `versionName`；包名 `com.droiddeck.launcher`）。

文件名里的 `DroidDeckCOK` 只是本仓库给构建产物起的名字（COK 即 Chinese One Key）。**包名、版本号、应用名全部沿用上游，一个都没改**，所以装到设备上桌面图标仍然显示 `DroidDeck`，覆盖升级的行为也和以前一样。

装上、打开之后，先装好 Linux 运行环境（或直接用下节的本地一键安装），再按 **Play** 登录 Steam —— Steam 客户端会在首次启动时自行下载。

> ⚠️ 本包是**自有密钥签名**的，和 GitHub 上官方发布的 DroidDeck **不能互相覆盖安装**：装过官方版要先**卸载**，反之亦然。详见第五节。

## 三、首次本地一键安装（核心功能）

### 3.1 它是用来做什么的

官方版第一次跑 Steam 时，Steam 客户端要从 Valve 的 CDN 下载（约 344 MB），运行环境、桌面等则从 GitHub 拉。中国大陆地区网络下这些源常常很慢甚至完全连不上，而首次安装又是绕不开的。

这个功能让你**先在电脑上把文件下好，拷进手机，再由 App 按顺序一次装完**，装的过程中手机完全可以不联网。

### 3.2 准备哪些文件、放在哪里

#### 电脑上：全部放进仓库根目录的 `FirstLocalInstall\`

| 文件名 | 是什么 | 必需 | 大小 | 从哪来 |
|---|---|---|---|---|
| `linuxfs.tar.zst` | Linux 运行环境（runtime） | **必需** | 754 MiB | 托管目录里的 runtime 一项 |
| `proton-experimental-arm64-<build>.tar.zst` | Valve 的 Proton Experimental（ARM64）种子 | **必需** | 380 MiB | 托管目录里的 Proton 种子一项 |
| `steam-client\`（文件夹，17 个 zip + manifest） | Steam 客户端本体 | **必需** | 344 MiB | 运行根目录的 `download-steam-client.bat` 自动下载 |
| `desktop.tar.zst` | LXQt 桌面（含 Firefox、模拟器入口） | 推荐 | 406 MiB | 托管目录里的 desktop 一项 |
| `fonts\`（文件夹，`.ttf` / `.ttc` / `.otf`） | 中文字体 | 推荐 | 自备 | 自行放入，随附简繁两款霞鹜文楷 |
| `GE-Proton*.tar.gz` / `proton-cachyos*` | 第三方 Proton | 可选 | — | 各自的发布页 |

**前三项是必需的**，缺任何一项设备都跑不起来；后三项可选，装不装都不影响按钮能用。上述文件合计约 **1.9 GiB**（不含第三方 Proton）。

文件名**不需要改**——安装时按文件名识别（`linuxfs*`、`proton*`、`desktop*`、`steam-client\`、`fonts\`），也用原作自己的名字去核对。

#### 手机上：整个 `FirstLocalInstall` 文件夹拷进内部存储

推荐位置：

```
/sdcard/Download/FirstLocalInstall
```

即手机内部存储的 `Download` 目录下。用数据线拷贝：

```bat
adb push FirstLocalInstall /sdcard/Download/
```

放在哪儿其实都行，只是后面选文件夹时好找。**不要解压**里面的 `.tar.zst`——它们是安装用的压缩包本身。

### 3.3 操作步骤

1. 装好本仓库构建出的 APK 并打开 DroidDeck
2. 进入 **Steam → 游玩 Steam** 页面
3. 点齿轮图标**右边**的 **「首次本地一键安装」** 按钮
4. 在弹出的选择器里选中手机上的 `FirstLocalInstall` 文件夹（**选它的上一级 `/Download` 也可以**）
5. 它开始按顺序自动安装，每步都显示进度；**已经装过的步骤会自动跳过**，所以可以反复点，也可以中途退出后重来

## 四、从源码构建

### Windows 一键构建（推荐）

```bat
build-apk.bat
```

它会自动完成：找 Android SDK / JDK 17+ / NDK、链接 NDK 到 Gradle 要求的位置、**从中国大陆地区的镜像取 Gradle 本体**（`gradlew` 会去 `services.gradle.org`，那个域名会重定向到 `github.com`，在中国大陆地区的网络下常连不上）、写入 `local.properties`、编译、**校验 APK 里真的带上了原生载荷**、签名，最后把包复制到 `dist\` 并打开该目录。

产出：`dist\DroidDeckCOK-<版本>-release.apk`（`<版本>` 取自 `app/build.gradle` 的 `versionName`）

所需环境：Android SDK（含 **Platform 34**，项目 `compileSdk 34`）、JDK 17 或更高、NDK `27.3.13750724`。

常用参数：

| 参数 | 作用 |
|---|---|
| `debug` | 构建 debug 变体 |
| `clean` | 先清理再全量构建 |
| `--own-key` / `--no-own-key` | 强制使用自有密钥签名 / 强制回退 AOSP testkey |
| `--offline` | 离线构建 |
| `--no-open` / `--no-pause` | 不打开产物目录 / 结束时不等待按键（适合脚本调用） |

日志在 `build-logs\build-<变体>-<时间戳>.log`（判断成败看这里，不要只看 bat 的输出）。

### Linux / Docker 构建

上游原路径仍然保留：`tools/build_local.sh`（Docker + NDK）与 `tools/deploy_local.sh`（装到已连接的设备）。

### 补齐必要文件这件事

上游把一部分构建产物（37 个原生载荷，解包后约 18 MiB）交给 CI 用 Docker + NDK 现场生成，仓库里 `.gitignore` 掉、从不提交。这些文件是让 Linux 真正跑起来的东西——尤其 `lib/arm64-v8a/libproot.so`，它是 guest 的执行入口；缺了它，界面照常能开、运行环境也能装，但一点「游玩」就卡在加载界面。

本仓库把它们**提取回源码树**，因此不装 Docker 也能在本机构建出功能完整的 APK；同时构建脚本多了一道**硬关卡**：载荷不全就直接判失败，不把残缺的包放进 `dist\`。

## 五、签名说明（为什么本仓库是自己签的）

GitHub 官方发布的 APK 由 DroidDeck 的发布密钥签名（证书 `CN=DroidDeck, O=The412Banner`，SHA-256 `b241ea7d…`）。**这把私钥只在作者的 CI 密钥库里，不公开**，所以本仓库不可能用它签名，只能**用自己的密钥重新签**。

但如果只是随便换一把新密钥，Android 会把本包当成另一个应用，连本项目此前用公开 AOSP testkey 构建的旧包都无法覆盖安装。因此本仓库采用 **testkey → 自有密钥的 lineage 轮换**（与上游 CI 同一套做法）：同一个 APK 里，**v1 / v2 签名来自 AOSP testkey，v3 来自自有密钥**，Android 会把它记录为「由 testkey 升级而来」——本仓库签出的旧包因此可以**原地覆盖升级**，已下载的运行环境 / 种子 / 客户端不会丢。

需要注意：

- **本包与 GitHub 官方版不能互相覆盖安装**（签名不同）：装过官方版，必须先**卸载**再装本包；反过来也一样。
- **只有本仓库签出的 APK 之间才能互相覆盖升级**：换成任何其他来源（官方版、他人改的版本），都只能卸载重装。
- 这把密钥**一旦丢失就再也无法原地覆盖升级**，只能卸载重装——请务必把密钥与它的配置一起备份好。

签名在 `build-apk.bat` 构建完成后自动进行。密钥与其口令（密码）**不随本仓库发布**，只留在本机一个 `.gitignore` 已排除的目录里；具体路径与用法见 `build-apk.bat` 的帮助文本与 `tools/release/sign-apk-local.py`。**没有这把密钥时构建不会失败**，会自动回退成 AOSP testkey 签名 —— 包照样能出，只是证明不了是同一把密钥签的；显式写 `--own-key` 时缺密钥才是硬错误。

## 六、中文字体（随附的字体与许可）

`FirstLocalInstall\fonts\` 里随附的是开源中文字体：

- **简体中文 · 霞鹜文楷 / LXGW WenKai**（`LXGWWenKai-Regular.ttf`）— <https://github.com/lxgw/LxgwWenKai>
- **繁体中文 · 霞鹜文楷 TC / LXGW WenKai TC**（`LXGWWenKaiTC-Regular.ttf`）— <https://github.com/lxgw/LxgwWenkaiTC>

两者均以 **SIL Open Font License 1.1** 发布，可自由使用与再分发。你也可以把任意中文字体（`.ttf` / `.ttc` / `.otf`）放进这个文件夹，一键安装会把它们一并装进去。

## 七、致谢与许可

本项目基于 [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck)，沿用其 **GPL-3.0** 许可，详见 [LICENSE](LICENSE)。

运行环境、shim、输入与手柄相关工作建立在 WinNative 与 Bannerlator（maxjivi05）之上。LSFG 帧生成来自 Camille LaVey 与 [Eden](https://eden-emu.dev) 模拟器项目的工作，遵循 [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)，由 [@maxjivi05](https://github.com/maxjivi05) 移植到 WinNative 与 DroidDeck；它需要你自己购买 [Lossless Scaling](https://store.steampowered.com/app/993090/)，本身不附带其任何 shader。x86 AppImage 以及自身运行时无法解包的那些，使用 VHSgunzo 的 [uruntime](https://github.com/VHSgunzo/uruntime)（MIT）解包，原样附带其许可证。

Steam 与 Proton 归 Valve Corporation 所有；本项目与 Valve 无隶属关系。
