<p align="center">
 <picture>
 <source media="(prefers-color-scheme: dark)" srcset="artwork/droiddeck-banner-dark.svg">
 <img alt="DroidDeck" src="artwork/droiddeck-banner-light.svg" width="100%">
 </picture>
</p>

# DroidDeckCOK 0.3.1 · 中文字地安裝版

**DroidDeckCOK**（COK = **C**hinese **O**ne **K**ey）是本倉庫的名字，指這份以「首次本地一鍵安裝」為核心的中文本地化改造版。**改名只落在建構產物的檔案名上**：套件名稱、版本號、應用程式名稱與上游 0.3.1 完全相同（見「[二、安裝 APK](#二安裝-apk)」）。

DroidDeck 把 SteamOS 的體驗搬到 Android：在 Adreno 掌機上跑 Valve 的 Steam 用戶端（Big Picture 大螢幕介面），Windows 遊戲走 Valve 的 ARM64 Proton。

本倉庫為 [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck) **0.3.1** 版本的改造版。功能與上游一致，只做了下面四件事，並因為金鑰的原因**重新簽了名**（見「[五、簽名說明](#五簽名說明為什麼本倉庫是自己簽的)」）：

| # | 改動 | 說明 |
|---|---|---|
| 1 | **補齊必要文件** | 上游交給 CI 現場生成、倉庫裡從不提交的原生載荷（37 個文件，含 guest 的執行入口 `libproot.so`）被提取回源碼樹，並給構建加了一道硬關卡 |
| 2 | **中文字體支援** | 新增一步把 `fonts\` 裡的中文字體裝進 Linux 運行環境，讓 Steam 大螢幕與桌面能正確顯示中文 |
| 3 | **首次本地一鍵安裝** | Steam 遊玩頁新增一個按鈕，從手機上的一個資料夾把運作環境、Proton、Steam 用戶端、桌面、字體一次裝完，全程不依賴手機連網 |
| 4 | **更新頁改為手動** | 本倉庫的包是自有金鑰簽名的，官方的應用程式內更新裝不到它上面，所以左側「更新」頁換成了說明原因、並給出去官方的 GitHub 發行頁查看新版 |

> 上游工程沒有獨立官網。任何自稱 DroidDeck 團隊、提供下載連結的網站都不可信。

---

## 一、系統需求

- **Android 14（API 34）以上**
- **Adreno 730 或更高**（Adreno 8xx 也可以）。 Mali、Xclipse、PowerVR、Adreno 710 不支援
- arm64 設備，**不需要 root**
- 儲存空間：Linux 運作環境約 3 GB；桌面與模擬器再約 1.1 GB
- 首次啟動 Steam **之前**，必須在系統「開發者選項」裡關掉 **「限制子進程」**（Restrict child processes）。 Android 12 / 13 若沒有這個開關，首次啟動 Steam 時會出現一個「幫我修好」按鈕自動完成設置

## 二、安裝 APK

用本倉庫建構出來的套件在 `dist\DroidDeckCOK-0.3.1-release.apk`（版本 `0.3.1`，versionCode `11`，套件名稱 `com.droiddeck.launcher`）。

檔案名裡的 `DroidDeckCOK` 只是本倉庫給建構產物起的名字（COK 即 Chinese One Key）。**套件名稱、版本號、應用程式名稱全部沿用上游 0.3.1，一個都沒改**，所以裝到裝置上桌面圖示仍然顯示 `DroidDeck`，覆蓋升級的行為也和以前一樣。

裝上、開啟後，先裝好 Linux 運行環境（或直接用下節的本機一鍵安裝），再按 **Play** 登入 Steam － Steam 用戶端會在首次啟動時自行下載。

> ⚠️ 本包是**自有密鑰簽名**的，和 GitHub 上官方發布的 DroidDeck **不能互相覆蓋安裝**：裝過官方版要先**卸載**，反之亦然。詳見第五節。

## 三、首次本地一鍵安裝（核心功能）

### 3.1 它是用來做什麼的

官方版第一次跑 Steam 時，Steam 用戶端要從 Valve 的 CDN 下載（約 344 MB），運行環境、桌面等則從 GitHub 拉。中國大陸地區網路下這些源常常很慢甚至完全連不上，而首次安裝又是繞不開的。

這個功能讓你**先在電腦上把文件下好，拷進手機，再由 App 按順序一次裝完**，裝的過程中手機完全可以不聯網。

### 3.2 準備哪些文件、放在哪裡

#### 電腦上：全部放進倉庫根目錄的 `FirstLocalInstall\`

| 檔案名稱 | 是什麼 | 必需 | 大小 | 從哪裡來 |
|---|---|---|---|---|
| `linuxfs.tar.zst` | Linux 運行環境（runtime） | **必要** | 754 MiB | 託管目錄 `linuxfs-r9` |
| `proton-experimental-arm64-<build>.tar.zst` | Valve 的 Proton Experimental（ARM64）種子 | **必需** | 380 MiB | 託管目錄 `steam-proton-arm64-25502785` |
| `steam-client\`（資料夾，17 個 zip + manifest） | Steam 用戶端本體 | **必要** | 344 MiB | 執行根目錄的 `download-steam-client.bat` 自動下載 |
| `desktop.tar.zst` | LXQt 桌面（含 Firefox、模擬器入口） | 推薦 | 406 MiB | 託管目錄 `steamdeck-desktop-r1` |
| `fonts\`（資料夾，`.ttf` / `.ttc` / `.otf`） | 中文字體 | 推薦 | 自備 | 自行放入，隨附簡繁兩款霞鶓文楷 |
| `GE-Proton*.tar.gz` / `proton-cachyos*` | 第三方 Proton | 可選 | — | 各自的發布頁 |

**前三項是必需的**，缺少任何一項裝備都跑不起來；後三項可選，裝不裝都不影響按鈕能用。上述文件總計約 **1.9 GiB**（不含第三方 Proton）。

檔案名稱**不需要改**－安裝時依檔案名稱辨識（`linuxfs*`、`proton*`、`desktop*`、`steam-client\`、`fonts\`），也用原作自己的名字去核對。

#### 手機上：整個 `FirstLocalInstall` 資料夾拷貝內部存儲

推薦位置：

```
/sdcard/Download/FirstLocalInstall
```

即手機內部儲存的 `Download` 目錄下。用資料線拷貝：

```bat
adb push FirstLocalInstall /sdcard/Download/
```

放在哪裡其實都行，只是後選資料夾時好找。 **不要解壓縮**裡面的 `.tar.zst`——它們是安裝用的壓縮包本身。

### 3.3 操作步驟

1. 裝好本 APK（`dist\DroidDeckCOK-0.3.1-release.apk`）並開啟 DroidDeck
2. 進入 **Steam → 遊玩 Steam** 頁面
3. 點齒輪圖示**右邊**的 **「首次本地一鍵安裝」** 按鈕
4. 在彈出的選擇器中選取手機上的 `FirstLocalInstall` 資料夾（**選取它的上一層 `/Download` 也可以**）
5. 它開始依序自動安裝，每個步驟都顯示進度；**已經裝過的步驟會自動跳過**，所以可以重複一點，也可以中途退出後重來

## 四、從原始碼構建

### Windows 一鍵建置（建議）

```bat
build-apk.bat
```

它會自動完成：找 Android SDK / JDK 17+ / NDK、連結 NDK 到 Gradle 要求的位置、**從中國大陸地區的鏡像取 Gradle 本體**（`gradlew` 會去 `services.gradle.org`，那個網域會重定向到 `github.com`，在中國大陸上的網路寫入區域會重新設定** .裡真的帶了原生載重**、簽名，最後把包複製到 `dist\` 並打開該目錄。

產出：`dist\DroidDeckCOK-0.3.1-release.apk`

所需環境：Android SDK（含 **Platform 34**，專案 `compileSdk 34`）、JDK 17 或更高、NDK `27.3.13750724`。

常用參數：

| 參數 | 作用 |
|---|---|
| `debug` | 建構 debug 變體 |
| `clean` | 先清理再全量建置 |
| `--own-key` / `--no-own-key` | 強制使用自有金鑰簽章 / 強制回退 AOSP testkey |
| `--offline` | 離線建置 |
| `--no-open` / `--no-pause` | 不開啟產物目錄 / 結束時不等待按鍵（適合腳本呼叫） |

日誌在 `build-logs\build-<變體>-<時間戳記>.log`（判斷成敗看這裡，不要只看 bat 的輸出）。

### Linux / Docker 構建

上游原路徑仍保留：`tools/build_local.sh`（Docker + NDK）與 `tools/deploy_local.sh`（裝至已連線的裝置）。

### 補齊必要文件這件事

上游把一部分建置產物（37 個原生載重，解包後約 18 MiB）交給 CI 用 Docker + NDK 現場生成，倉庫裡 `.gitignore` 掉、從不提交。這些檔案是讓 Linux 真正跑起來的東西——尤其 `lib/arm64-v8a/libproot.so`，它是 guest 的執行入口；缺了它，介面照常能開、運行環境也能裝，但一點「遊玩」就卡在載入介面。

本倉庫把它們**提取回源碼樹**，因此不裝 Docker 也能在本機構建出功能完整的 APK；同時構建腳本多了一道**硬關卡**：負載不全就直接判失敗，不把殘缺的包放進 `dist\`。

## 五、簽名說明（為什麼本倉庫是自己簽的）

GitHub 官方發布的 APK 由 DroidDeck 的發布金鑰簽署（憑證 `CN=DroidDeck, O=The412Banner`，SHA-256 `b241ea7d…`）。 **這把私鑰只在作者的 CI 金鑰庫裡，不公開**，所以本倉庫不可能用它簽名，只能**用自己的金鑰重新簽**。

但如果只是隨便換一把新密鑰，Android 會把本包當成另一個應用，連本專案先前用公開 AOSP testkey 構建的舊包都無法覆蓋安裝。因此本倉庫採用 **testkey → 自有密鑰的 lineage 輪換**（與上游 CI 同一套做法）：同一個 APK 裡，**v1 / v2 簽名來自 AOSP testkey，v3 來自自有密鑰**，Android 會把它記錄為“由 testkey 升級而來”——本倉庫簽出的舊包因此

需要注意：

- **本包與 GitHub 官方版無法互相覆蓋安裝**（簽名不同）：裝過官方版，必須先**卸載**再裝本包；反過來也一樣。
- **只有本倉庫簽出的 APK 之間才能互相覆蓋升級**：換成任何其他來源（官方版、他人改的版本），都只能卸載重裝。
- 這把金鑰**一旦遺失就再也無法原地覆蓋升級**，只能卸載重裝－請務必把金鑰與它的設定一起備份好。

簽章在 `build-apk.bat` 建置完成後自動進行。金鑰與其口令（密碼）**不隨本倉庫發布**，只留在本機一個 `.gitignore` 已排除的目錄裡；具體路徑與用法見 `build-apk.bat` 的幫助文本與 `tools/release/sign-apk-local.py`。 **沒有這把金鑰時建置不會失敗**，會自動回退成 AOSP testkey 簽章 —— 包照樣能出，只是證明不了是同一把密鑰籤的；顯式寫 `--own-key` 時缺少密鑰才是硬錯誤。

## 六、中文字體（隨附的字體與授權）

`FirstLocalInstall\fonts\` 裡隨附的是開源中文字體：

- **簡體中文 · 霞鷚文楷 / LXGW WenKai**（`LXGWWenKai-Regular.ttf`）— <https://github.com/lxgw/LxgwWenKai>
- **繁體中文 · 霞鷚文楷 TC / LXGW WenKai TC**（`LXGWWenKaiTC-Regular.ttf`）— <https://github.com/lxgw/LxgwWenkaiTC>

兩者均以 **SIL Open Font License 1.1** 發布，可自由使用與再分發。你也可以把任意中文字體（`.ttf` / `.ttc` / `.otf`）放進這個資料夾，一鍵安裝會把它們一併裝進去。

## 七、致謝與許可

本專案基於 [Droid-Deck/DroidDeck](https://github.com/Droid-Deck/DroidDeck)（0.3.1），沿用其 **GPL-3.0** 許可，詳見 [LICENSE](LICENSE)。

運作環境、shim、輸入與手把相關工作建立在 WinNative 與 Bannerlator（maxjivi05）之上。 LSFG 幀產生來自 Camille LaVey 與 [Eden](https://eden-emu.dev) 模擬器專案的工作，遵循 [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk)，由 [@maxjivi05](https://github.com/maxjiN5) 與你購買 Doss服務） Scaling](https://store.steampowered.com/app/993090/)，本身不附其任何 shader。 x86 AppImage 以及自身運作時無法解包的那些，使用 VHSgunzo 的 [uruntime](https://github.com/VHSgunzo/uruntime)（MIT）解包，原樣附帶其許可證。

Steam 與 Proton 歸 Valve Corporation 所有；本項目與 Valve 無隸屬關係。