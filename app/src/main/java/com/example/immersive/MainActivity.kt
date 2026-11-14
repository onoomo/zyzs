package com.example.immersive

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.downx.app.BiliLoginActivity
import com.downx.app.BiliSession
import com.downx.app.DlService
import com.downx.app.FilesActivity
import com.downx.app.HelpActivity
import com.downx.app.IgLoginActivity
import com.downx.app.IgScraper
import com.downx.app.HistoryStore
import com.downx.app.IgSession
import com.downx.app.ImageLoader
import com.downx.app.LocalFiles
import com.downx.app.Prefs
import com.downx.app.ShortVideo
import com.downx.app.ShortVideoScraper
import com.downx.app.PlayerActivity
import com.downx.app.Store
import com.downx.app.ThemeMode
import com.downx.app.ThumbLoader
import com.downx.app.TweetFetcher
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 纯原生 Activity（不继承 AppCompat），全屏隐藏状态栏 + 导航栏，支持 API 21~36。
 *
 * 三页面：首页（两框）/ 媒体管理（下载列表）/ 设置，由悬浮胶囊导航切换。
 * 首页框 1「视频下载」→ 图1 深色弹窗 → 粘贴链接 → 调用移植自 DownX 的真实下载链路：
 *   TweetFetcher.fetchSync（B站/IG/X/小红书/TikTok/FB/Pinterest/YouTube）
 *   或 ShortVideoScraper（抖音/快手内置浏览器嗅探）
 *   → 统一 Result/Media → DlService.start（Range 续传 / 8 路分片 / HLS / 双流合成 / 写相册）
 * 账号：BiliLoginActivity（扫码）/ IgLoginActivity（网页登录），登录态给解析器取高清晰度。
 */
class MainActivity : Activity() {

    private data class Task(
        var title: String,
        val url: String,
        val quality: String,
        val category: String,
        val time: String,
        var state: String = "下载中",
        var file: String = "",         // 下载完成时 DlService 广播的真实落盘文件名
        var recId: Int = -1,           // >=0 表示这是原版引擎的实时录制任务行
        var recMeta: String = ""       // 录制行副标题（时长·大小）
    )

    companion object {
        private const val PREF = "downx_demo"
        private const val KEY_TASKS = "tasks"
        private const val KEY_QUALITY = "quality"
        private const val KEY_AUTO_PASTE = "auto_paste"
        private const val KEY_SAVE_GALLERY = "save_gallery"
        private val URL_RE = Regex("https?://\\S+")
    }

    private val tasks = mutableListOf<Task>()
    private var currentTab = 3          // 图1：0全部 1视频 2音频 3任务 4下载，默认「任务」
    private var quality = "自动"
    private var autoPaste = true
    private var saveGallery = true
    private var settingsVisible = false   // 视频下载弹窗的设置区默认关闭，点齿轮才展开
    private var currentPage = 0
    private var safeTopPx = 0             // 顶部挖孔 / 安全区高度，解析态上顶时避让

    /** 图1 条目「更多」菜单：11 项，逐项落到真实文件 / 下载历史 */
    private val itemMenu = listOf(
        "打开", "分享", "选取", "属性", "删除", "打开为", "重命名",
        "重新下载", "查看文件位置", "复制下载地址", "打开所在目录"
    )

    /** 图2 解析结果卡片：解析成功后先展示，点「下载 · 无水印」才真正开始下载 */
    private var parsedRaw = ""
    private var parsedResult: TweetFetcher.Result? = null
    private var selectedIndex = 0
    private lateinit var resultArea: View
    private lateinit var resultChips: LinearLayout
    private val resultChipViews = mutableListOf<TextView>()

    /** 原版 startParse() 解析态：status 提示/错误行 + skeleton「正在解析…」加载态 */
    private lateinit var parseStatus: TextView
    private lateinit var parseSkeleton: View
    private lateinit var parseHint: TextView

    private lateinit var pages: List<View>
    private lateinit var navItems: List<TextView>
    private lateinit var tabRows: List<TextView>
    private lateinit var tabInds: List<View>
    private lateinit var qualityChips: List<TextView>
    private lateinit var emptyState: View
    private lateinit var taskScroll: View
    private lateinit var taskList: LinearLayout
    private lateinit var dialogOverlay: View
    private lateinit var settingsArea: View
    private lateinit var etLink: EditText
    private lateinit var swAutoPaste: TextView
    private lateinit var swSaveGallery: TextView
    private lateinit var scrapeWeb: WebView

    private var shortScraper: ShortVideoScraper? = null
    private var igScraper: IgScraper? = null
    private var receiverRegistered = false

    /** 接收 DlService 的进度 / 完成 / 失败广播，实时更新任务卡片状态。 */
    private val dlReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (!::taskList.isInitialized) return
            val msg = i?.getStringExtra("msg").orEmpty()
            when (i?.action) {
                DlService.A_PROG -> {
                    val pct = msg.toIntOrNull() ?: 0
                    tasks.firstOrNull { it.state.startsWith("下载中") }?.state = "下载中 $pct%"
                    if (taskList.childCount > 0) {
                        taskList.getChildAt(0).findViewById<TextView>(R.id.task_state)?.text =
                            "下载中 $pct%"
                    }
                }
                DlService.A_DONE -> {
                    val done = tasks.firstOrNull { it.state.startsWith("下载中") }
                    done?.state = "已下载"
                    // DlService 完成广播带的 msg 就是真实落盘文件名，记下来供文件菜单定位
                    if (done != null && msg.isNotEmpty()) done.file = msg
                    saveTasks(); renderTasks(); toast("下载完成：$msg")
                }
                DlService.A_FAIL -> {
                    tasks.firstOrNull { it.state.startsWith("下载中") }?.state = "失败"
                    saveTasks(); renderTasks(); toast("下载失败：$msg")
                }
                DlService.A_CANCEL -> {
                    tasks.firstOrNull { it.state.startsWith("下载中") }?.state = "已取消"
                    saveTasks(); renderTasks()
                }
            }
        }
    }

    /** 明暗主题：在 Activity 创建前覆盖 uiMode，让 values-night/ 资源生效。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(ThemeMode.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 内容延伸到系统栏区域（全版本通用）
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        configureBars()
        applySafeArea()
        initDownX()
        loadSettings()
        loadTasks()
        bindViews()
        setupPages()
        // 切换明暗后 recreate()：优先从 savedInstanceState 恢复当前页，避免跳回首页
        val restore = savedInstanceState?.getInt("page", -1)
            ?: intent.getIntExtra("restore_page", -1)
        restore.takeIf { it in 0..2 }?.let { switchPage(it) }
        setupMediaTabs()
        setupDialog()
        setupSettingsPage()
        setupAccounts()
        registerDlReceiver()
        refreshAccountUi()
        renderTasks()
        selectTab(3)
        if (intent.getBooleanExtra("goto_rec", false)) switchPage(1)
        hideSystemBars()
        requestNotificationPermissionIfNeeded()
        handleShareIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", currentPage)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        refreshAccountUi()   // 从 B站 / IG 登录页返回后刷新登录状态
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
        if (intent.getBooleanExtra("goto_rec", false)) {
            switchPage(1)
            selectTab(3)
            renderTasks()
        }
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(dlReceiver) } catch (e: Exception) { /* 忽略 */ }
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (::dialogOverlay.isInitialized && dialogOverlay.visibility == View.VISIBLE) {
            hideDialog()
            return
        }
        super.onBackPressed()
    }

    // ---------- 移植自 DownX 的初始化 ----------

    /** 登录态初始化 + 内置嗅探 WebView（抖音/快手/IG 解析要拦截它的网络请求）。 */
    private fun initDownX() {
        BiliSession.init(this)
        IgSession.init(this)
        scrapeWeb = findViewById(R.id.scrape_web)
        // IgScraper 构造会把 WebView UA 改成桌面版，先存设备原始 UA 给快手嗅探用
        val deviceUa = scrapeWeb.settings.userAgentString
        igScraper = IgScraper(scrapeWeb)
        shortScraper = ShortVideoScraper(scrapeWeb, deviceUa)
    }

    private fun registerDlReceiver() {
        val f = IntentFilter().apply {
            addAction(DlService.A_PROG)
            addAction(DlService.A_DONE)
            addAction(DlService.A_FAIL)
            addAction(DlService.A_CANCEL)
            addAction(DlService.A_PAUSE)
        }
        ContextCompat.registerReceiver(this, dlReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    /** Android 13+ 前台下载服务的进度通知需要通知权限，否则通知栏看不到进度。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001
            )
        }
    }

    /** 从其它 App「分享 → 复制链接」进来时，自动填入并弹出下载弹窗。 */
    private fun handleShareIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND && ::etLink.isInitialized) {
            val text = i.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link = URL_RE.find(text)?.value.orEmpty()
            if (link.isNotEmpty()) {
                etLink.setText(link)
                showDialog()
            }
        }
    }

    // ---------- 系统栏 / 挖孔安全区 ----------

    private fun configureBars() {
        // 窗口背景显式跟随当前主题（app 层 uiMode 覆盖），
        // 与二级页 Immersive.apply() 对称，确保挖孔安全区不露出与页面不一致的色带。
        window.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(
                ContextCompat.getColor(this, R.color.bg_page)
            )
        )
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    /**
     * 页面背景铺满全屏（贴顶全屏）：状态栏与导航栏已隐藏，内容从屏幕顶部开始，
     * 不再把整条 statusBar 高度叠加到顶部锚点；只保留左右 inset 避让横屏挖孔。
     * gone 的页面也一并更新，切回来时立刻生效；弹窗覆盖层同样只避让左右挖孔。
     */
    private fun applySafeArea() {
        val content = findViewById<View>(R.id.content)
        val homeSpacer = findViewById<View>(R.id.home_top_spacer)
        val tabRow = findViewById<View>(R.id.tab_row)
        val settingsTop = findViewById<View>(R.id.settings_top)
        val overlay = findViewById<FrameLayout>(R.id.dialog_overlay)
        val d = resources.displayMetrics.density
        val baseHome = (18 * d).toInt()
        val baseSettings = (8 * d).toInt()
        val baseTabLR = (12 * d).toInt()
        val baseSettingsLR = (18 * d).toInt()

        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val ins = safeInsets(v, insets)
            val l = ins[0]
            val r = ins[2]
            safeTopPx = ins[1]          // 解析态弹窗上顶时用它避开挖孔
            homeSpacer.layoutParams.height = baseHome
            homeSpacer.requestLayout()
            // 顶部文字标签栏：只补左右挖孔避让，不动上下 padding
            tabRow.updatePadding(left = baseTabLR + l, right = baseTabLR + r)
            settingsTop.updatePadding(
                left = baseSettingsLR + l,
                top = baseSettings,
                right = baseSettingsLR + r
            )
            overlay.updatePadding(left = l, right = r)
            insets
        }
    }

    private fun safeInsets(v: View, insets: WindowInsetsCompat): IntArray {
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout()
        )
        var left = bars.left
        var top = bars.top
        var right = bars.right
        var bottom = bars.bottom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            v.rootWindowInsets?.displayCutout?.let { cutout ->
                left = maxOf(left, cutout.safeInsetLeft)
                top = maxOf(top, cutout.safeInsetTop)
                right = maxOf(right, cutout.safeInsetRight)
                bottom = maxOf(bottom, cutout.safeInsetBottom)
            }
        }
        return intArrayOf(left, top, right, bottom)
    }

    // ---------- 视图绑定与页面 ----------

    private fun bindViews() {
        pages = listOf(
            findViewById(R.id.page_home),
            findViewById(R.id.page_media),
            findViewById(R.id.page_settings)
        )
        navItems = listOf(
            findViewById(R.id.nav_home),
            findViewById(R.id.nav_media),
            findViewById(R.id.nav_settings)
        )
        tabRows = listOf(
            findViewById(R.id.tab_all),
            findViewById(R.id.tab_video),
            findViewById(R.id.tab_audio),
            findViewById(R.id.tab_task),
            findViewById(R.id.tab_download)
        )
        tabInds = listOf(
            findViewById(R.id.ind_all),
            findViewById(R.id.ind_video),
            findViewById(R.id.ind_audio),
            findViewById(R.id.ind_task),
            findViewById(R.id.ind_download)
        )
        qualityChips = listOf(
            findViewById(R.id.chip_auto),
            findViewById(R.id.chip_1080),
            findViewById(R.id.chip_720)
        )
        emptyState = findViewById(R.id.empty_state)
        taskScroll = findViewById(R.id.task_scroll)
        taskList = findViewById(R.id.task_list)
        dialogOverlay = findViewById(R.id.dialog_overlay)
        settingsArea = findViewById(R.id.settings_area)
        resultArea = findViewById(R.id.result_area)
        resultChips = findViewById(R.id.result_chips)
        parseStatus = findViewById(R.id.parse_status)
        parseSkeleton = findViewById(R.id.parse_skeleton)
        parseHint = findViewById(R.id.parse_hint)
        etLink = findViewById(R.id.et_link)
        swAutoPaste = findViewById(R.id.switch_auto_paste)
        swSaveGallery = findViewById(R.id.switch_save_gallery)
    }

    private fun setupPages() {
        navItems.forEachIndexed { i, tv -> tv.setOnClickListener { switchPage(i) } }
        switchPage(0)
        // 首页框 1「视频下载」→ 下载弹窗；框 2「资源嗅探」→ 入口弹窗（图1）；
        // 框 3「播放器」→ 选播放源（粘贴视频/直播地址 或 选本地已下载文件）
        findViewById<View>(R.id.box1).setOnClickListener { showDialog() }
        // 框 2「资源嗅探」：先弹图1 入口弹窗（资源嗅探 / 抖音直播开 / 粘贴链接 / 开始嗅探），再进嗅探页
        findViewById<View>(R.id.box2).setOnClickListener {
            showSniffDialog()
        }
        findViewById<View>(R.id.box3).setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
    }

    /**
     * 资源嗅探入口弹窗（对齐原版 Assistant 图1）：标题「资源嗅探」+ 绿色「抖音直播 开」，
     * 链接输入框 + 粘贴按钮，底部「取消 / 开始嗅探」。点「开始嗅探」把链接带进内置浏览器嗅探页；
     * 抖音直播开关打开且没填链接时，直接进 live.douyin.com。
     */
    private fun showSniffDialog() {
        val d = resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_sniff, null)
        val liveOn = booleanArrayOf(true)
        val liveTv = view.findViewById<TextView>(R.id.sniff_live)
        val urlEt = view.findViewById<EditText>(R.id.sniff_url)
        val pasteBtn = view.findViewById<View>(R.id.sniff_paste)
        val cancelBtn = view.findViewById<View>(R.id.sniff_cancel)
        val goBtn = view.findViewById<View>(R.id.sniff_go)

        // 进弹窗自动识别剪贴板里的链接（只识别链接，不把中文口令塞进输入框）
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        if (clip != null && clip.itemCount > 0) {
            val t = clip.getItemAt(0).text?.toString().orEmpty()
            val link = com.downx.app.SniffActivity.extractUrl(t)
            if (!link.isNullOrEmpty()) {
                urlEt.setText(link)
                urlEt.setSelection(link.length)
            }
        }

        pasteBtn.setOnClickListener {
            val c = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (c != null && c.itemCount > 0) {
                val t = c.getItemAt(0).text?.toString().orEmpty()
                val link = com.downx.app.SniffActivity.extractUrl(t)
                if (!link.isNullOrEmpty()) {
                    urlEt.setText(link)
                    urlEt.setSelection(link.length)
                } else {
                    toast("剪贴板里没有链接")
                }
            } else {
                toast("剪贴板里没有链接")
            }
        }

        // 抖音直播 开 / 关 切换
        liveTv.setOnClickListener {
            liveOn[0] = !liveOn[0]
            if (liveOn[0]) {
                liveTv.text = "抖音直播 开"
                liveTv.setTextColor(ContextCompat.getColor(this, R.color.set_ok))
            } else {
                liveTv.text = "抖音直播 关"
                liveTv.setTextColor(ContextCompat.getColor(this, R.color.dialog_subtext))
            }
        }

        val dlg = AlertDialog.Builder(this).setView(view).setCancelable(true).create()
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        cancelBtn.setOnClickListener { dlg.dismiss() }
        goBtn.setOnClickListener {
            val raw = urlEt.text.toString().trim()
            val url = if (raw.isNotEmpty()) com.downx.app.SniffActivity.extractUrl(raw) else ""
            dlg.dismiss()
            val i = Intent(this, com.ziyuan.Assistant.SniffActivity::class.java)
            // 原版嗅探页读 "input" extra；抖音直播开/无链接时直接进抖音直播间
            val dest = if (liveOn[0] && url.isEmpty()) "https://live.douyin.com/" else url
            if (dest.isNotEmpty()) i.putExtra("input", dest)
            startActivity(i)
        }
        dlg.show()
    }

    private fun switchPage(index: Int) {
        currentPage = index
        pages.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navItems.forEachIndexed { i, tv ->
            val active = i == index
            tv.background = if (active)
                ContextCompat.getDrawable(this, R.drawable.bg_nav_item_selected) else null
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.nav_text_selected else R.color.nav_text
                )
            )
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    // ---------- 媒体管理明暗：设置页「深色模式」开关直接控制（代码级双保险） ----------
    // 只靠 attachBaseContext 覆盖 uiMode + values-night 时，个别 ROM 会干扰夜间资源选择。
    // 这里按 ThemeMode.isDark() 直接对本页每个元素显式着色，开关切换后立即生效。

    private data class MediaPalette(
        val bg: Int, val tabNormal: Int, val tabSelected: Int,
        val divider: Int, val empty: Int, val card: Int, val cardStroke: Int,
        val title: Int, val sub: Int, val state: Int,
        val menuBg: Int, val menuText: Int, val menuLine: Int,
        val thumbBg: Int, val thumbPlay: Int
    )

    private fun mediaPalette(): MediaPalette =
        if (ThemeMode.isDark(this)) MediaPalette(
            bg = 0xFF000000.toInt(), tabNormal = 0xFF8A9AAB.toInt(), tabSelected = 0xFF4DB8FF.toInt(),
            divider = 0xFF242430.toInt(), empty = 0xFF6E7E90.toInt(),
            card = 0x66141414, cardStroke = 0x33FFFFFF,
            title = 0xFFE6F0FA.toInt(), sub = 0xFF7A8B9C.toInt(), state = 0xFF4DB8FF.toInt(),
            menuBg = 0xFF0D0D0D.toInt(), menuText = 0xFFEDEDF2.toInt(), menuLine = 0xFF242430.toInt(),
            thumbBg = 0xFF10141D.toInt(), thumbPlay = 0xFF4DB8FF.toInt()
        ) else MediaPalette(
            bg = 0xFFE9F6FF.toInt(), tabNormal = 0xFF216BB6.toInt(), tabSelected = 0xFF1D9BF0.toInt(),
            divider = 0xFFD2E4F3.toInt(), empty = 0xFF7BA7CC.toInt(),
            card = 0x66FFFFFF.toInt(), cardStroke = 0xB3FFFFFF.toInt(),
            title = 0xFF216BB6.toInt(), sub = 0xFF5B87A8.toInt(), state = 0xFF1D9BF0.toInt(),
            menuBg = 0xFFFFFFFF.toInt(), menuText = 0xFF17324B.toInt(), menuLine = 0xFFD2E4F3.toInt(),
            thumbBg = 0xFFD7EAF8.toInt(), thumbPlay = 0xFF1D9BF0.toInt()
        )

    /** 按开关状态给媒体管理页静态部分上色；行与菜单在渲染时各自取 palette */
    private fun applyMediaTheme() {
        if (!::tabRows.isInitialized || !::taskList.isInitialized) return
        val p = mediaPalette()
        val d = resources.displayMetrics.density
        findViewById<View>(R.id.page_media).setBackgroundColor(p.bg)
        findViewById<View>(R.id.tab_row).setBackgroundColor(p.bg)
        findViewById<View>(R.id.tab_divider).setBackgroundColor(p.divider)
        findViewById<TextView>(R.id.empty_state).setTextColor(p.empty)
        tabInds.forEach { ind ->
            ind.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(p.tabSelected)
                cornerRadius = 2 * d
            }
        }
    }

    private fun setupMediaTabs() {
        tabRows.forEachIndexed { i, row -> row.setOnClickListener { selectTab(i) } }
        // 「读取剪贴板录制」入口改为长按顶部标签栏（顶部外观与图1 一致，不再多一行按钮）
        findViewById<View>(R.id.tab_row).setOnLongClickListener {
            recFromClip(); true
        }
        selectTab(3)
    }

    /** 读取剪贴板里的真链（不硬拼 URL），交给原版录制引擎开录 */
    private fun recFromClip() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val raw = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        val url = com.ziyuan.Assistant.SniffActivity.extractUrl(raw)
            ?.trim()?.trimEnd('\\', '"', '\'', ',')
        if (url.isNullOrEmpty() || !url.startsWith("http")) {
            toast("剪贴板里没有有效链接"); return
        }
        com.ziyuan.Assistant.RecManager.startRecFromClip(url)
        renderTasks()
        toast("已开始录制")
    }

    /** 图1 顶部文字标签：选中项蓝色加粗 + 下方短下划线，其余常规蓝 */
    private fun selectTab(index: Int) {
        currentTab = index.coerceIn(0, tabRows.size - 1)
        val p = mediaPalette()
        tabRows.forEachIndexed { i, tv ->
            val active = i == currentTab
            tv.setTextColor(if (active) p.tabSelected else p.tabNormal)
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            tabInds[i].visibility = if (active) View.VISIBLE else View.INVISIBLE
        }
        applyMediaTheme()
        renderTasks()
    }

    // ---------- 图1 弹窗 ----------

    private fun setupDialog() {
        dialogOverlay.setOnClickListener { hideDialog() }
        findViewById<View>(R.id.dialog_root).setOnClickListener { /* 拦截，避免点卡片关闭 */ }
        findViewById<View>(R.id.btn_paste).setOnClickListener { detectClipboardLink(false) }
        findViewById<View>(R.id.btn_download).setOnClickListener { addTask() }
        findViewById<View>(R.id.btn_settings_toggle).setOnClickListener {
            if (resultArea.visibility == View.VISIBLE
                || parseSkeleton.visibility == View.VISIBLE
                || parseStatus.visibility == View.VISIBLE) {
                // 结果页 / 解析态点齿轮：回到输入态并展开设置
                clearResult()
                settingsVisible = true
                settingsArea.visibility = View.VISIBLE
            } else {
                settingsVisible = !settingsVisible
                settingsArea.visibility = if (settingsVisible) View.VISIBLE else View.GONE
            }
        }
        findViewById<View>(R.id.btn_result_download).setOnClickListener { downloadSelected() }
        findViewById<View>(R.id.btn_result_back).setOnClickListener { clearResult() }
        findViewById<View>(R.id.result_open).setOnClickListener {
            val u = parsedResult?.tweetUrl
            if (!u.isNullOrEmpty()) {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) }
                catch (e: Exception) { toast("打不开原帖") }
            }
        }
        qualityChips.forEachIndexed { i, chip ->
            chip.setOnClickListener { selectQuality(i) }
        }
        selectQuality(listOf("自动", "1080P", "720P").indexOf(quality).coerceAtLeast(0))
        swAutoPaste.setOnClickListener {
            autoPaste = !autoPaste; paintSwitch(swAutoPaste, autoPaste); saveSettings()
        }
        swSaveGallery.setOnClickListener {
            saveGallery = !saveGallery; paintSwitch(swSaveGallery, saveGallery); saveSettings()
        }
        paintSwitch(swAutoPaste, autoPaste)
        paintSwitch(swSaveGallery, saveGallery)
        etLink.setOnEditorActionListener { _, _, _ -> addTask(); true }
    }

    private fun selectQuality(index: Int) {
        val labels = listOf("自动", "1080P", "720P")
        quality = labels[index]
        qualityChips.forEachIndexed { i, chip ->
            val active = i == index
            chip.background = ContextCompat.getDrawable(
                this,
                if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            chip.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.dialog_text else R.color.dialog_subtext
                )
            )
        }
        saveSettings()
    }

    private fun paintSwitch(tv: TextView, on: Boolean) {
        tv.text = if (on) "开" else "关"
        tv.background = ContextCompat.getDrawable(
            this,
            if (on) R.drawable.bg_chip_selected else R.drawable.bg_chip
        )
        tv.setTextColor(
            ContextCompat.getColor(
                this,
                if (on) R.color.dialog_text else R.color.dialog_subtext
            )
        )
    }

    /**
     * 弹窗定位：默认（只有输入条）垂直居中；只有出现解析内容
     * （正在解析骨架 / 状态行 / 结果卡片）时才往上顶一些，让长结果卡片
     * 从挖孔安全区下方完整展开，避免被底部悬浮胶囊挡住。
     */
    private fun syncDialogPosition() {
        val card = findViewById<View>(R.id.dialog_root) ?: return
        val lp = card.layoutParams as? FrameLayout.LayoutParams ?: return
        val hasParse =
            (::parseSkeleton.isInitialized && parseSkeleton.visibility == View.VISIBLE) ||
                (::parseStatus.isInitialized && parseStatus.visibility == View.VISIBLE) ||
                (::resultArea.isInitialized && resultArea.visibility == View.VISIBLE)
        val targetGravity = if (hasParse)
            Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        val targetTop = if (hasParse)
            safeTopPx + (resources.displayMetrics.heightPixels * 0.10f).toInt() else 0
        if (lp.gravity == targetGravity && lp.topMargin == targetTop) return
        lp.gravity = targetGravity
        lp.topMargin = targetTop
        lp.bottomMargin = 0
        card.layoutParams = lp
    }

    private fun showDialog() {
        settingsVisible = false          // 每次打开弹窗都回到「设置默认关闭」
        clearResult()
        syncDialogPosition()
        dialogOverlay.visibility = View.VISIBLE
        // 只有点击「视频下载」打开弹窗时才自动识别剪贴板，且只识别链接，
        // 不把中文/口令等任意文本塞进输入框（图1 那条中文就是旧逻辑粘进来的）。
        if (autoPaste) detectClipboardLink(true)
        hideSystemBars()
    }

    private fun hideDialog() {
        hideKeyboard()
        dialogOverlay.visibility = View.GONE
        hideSystemBars()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etLink.windowToken, 0)
    }

    /** 只从剪贴板提取链接（复用 DownX 的 TweetFetcher.extractLink），输入框已有内容时不覆盖。 */
    private fun detectClipboardLink(silent: Boolean) {
        if (etLink.text.isNotEmpty()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        val text = if (clip != null && clip.itemCount > 0)
            clip.getItemAt(0).text?.toString()?.trim().orEmpty() else ""
        val link = TweetFetcher.extractLink(text)
        if (!link.isNullOrEmpty()) {
            etLink.setText(link)
            etLink.setSelection(link.length)
        } else if (!silent) {
            toast("剪贴板里没有链接")
        }
    }

    // ---------- 真实下载：解析 → DlService ----------

    // ---------- 原版 startParse() 解析态 ----------

    /** 1:1 原版 hint()：把提示 / 失败原因写进弹窗状态行（不再用一闪而过的 toast）。 */
    private fun setStatus(s: String) {
        parseStatus.text = s
        parseStatus.visibility = View.VISIBLE
        syncDialogPosition()
    }

    /** 1:1 原版 startParse() 开头：亮起「正在解析…」骨架，收起结果卡片 / 设置区 / 旧状态。 */
    private fun beginParse(text: String = "正在解析…") {
        parsedResult = null
        selectedIndex = 0
        hideKeyboard()
        parseStatus.visibility = View.GONE
        parseHint.text = text
        parseSkeleton.visibility = View.VISIBLE
        resultArea.visibility = View.GONE
        settingsArea.visibility = View.GONE
        syncDialogPosition()
    }

    /** 解析中更新文案（抖音 / 快手内置浏览器阶段等，1:1 原版 hint 的阶段提示）。 */
    private fun setParsing(text: String) {
        parseHint.text = text
        parseSkeleton.visibility = View.VISIBLE
        parseStatus.visibility = View.GONE
        syncDialogPosition()
    }

    /** 1:1 原版解析失败：收起骨架，把美化后的原因写回 status 行。 */
    private fun failParse(msg: String) {
        parseSkeleton.visibility = View.GONE
        setStatus(msg)
    }

    /** 点「下载」：先在弹窗内亮起原版「正在解析…」加载态，再按平台解析。 */
    private fun addTask() {
        val raw = etLink.text.toString().trim()
        if (raw.isEmpty()) { setStatus("请先粘贴视频 / 图片链接"); return }
        val link = URL_RE.find(raw)?.value ?: raw
        beginParse()

        // 抖音 / 快手：DownX 用内置 WebView 嗅探官方分享页的网络请求
        if (ShortVideo.isDouyin(link) || ShortVideo.isKuaishou(link)) {
            val platform = if (ShortVideo.isDouyin(link)) "douyin" else "kuaishou"
            val sc = shortScraper
            if (sc == null) { failParse("嗅探解析器未就绪"); return }
            setParsing(if (platform == "douyin")
                "正在用内置浏览器打开抖音页面…" else "正在用内置浏览器打开快手页面…")
            sc.scrape(platform, link, object : ShortVideoScraper.Callback {
                override fun onResult(r: TweetFetcher.Result) {
                    runOnUiThread {
                        if (r.items.isEmpty()) failParse("没有嗅探到视频地址")
                        else renderResult(link, r)
                    }
                }
                override fun onFail(msg: String) {
                    runOnUiThread { failParse(TweetFetcher.beautify(msg)) }
                }
            })
            return
        }

        // 其余平台（B站/IG/X/小红书/TikTok/FB/Pinterest/YouTube）走同步解析
        Thread {
            try {
                val r = TweetFetcher.fetchSync(link)
                runOnUiThread { renderResult(link, r) }
            } catch (e: Exception) {
                val msg = TweetFetcher.beautify(e.message ?: "解析失败")
                runOnUiThread {
                    // 非平台链接（普通 http/https 直链）解析器不认，直接交给下载服务下
                    if (link.startsWith("http://", true) || link.startsWith("https://", true)) {
                        startDirectDownload(link)
                    } else {
                        failParse(msg)
                    }
                }
            }
        }.start()
    }

    /** 解析成功：先展示图2 原版的解析结果卡片，点「下载 · 无水印」才真正开始下载。 */
    private fun renderResult(raw: String, r: TweetFetcher.Result) {
        // 1:1 原版解析成功：收起骨架与状态行，亮出结果卡片
        parseSkeleton.visibility = View.GONE
        parseStatus.visibility = View.GONE
        if (r.items.isEmpty()) { failParse("没有解析到可下载的媒体"); return }
        parsedRaw = raw
        parsedResult = r
        selectedIndex = 0
        settingsArea.visibility = View.GONE
        resultArea.visibility = View.VISIBLE
        findViewById<TextView>(R.id.result_platform).text = platformName(r.platform)
        val handle = r.handle ?: ""
        val date = r.date ?: ""
        findViewById<TextView>(R.id.result_meta).text =
            if (handle.isNotEmpty()) "@$handle · $date" else (r.author ?: "")
        val caption = r.text ?: ""
        val tvText = findViewById<TextView>(R.id.result_text)
        tvText.text = caption
        tvText.visibility = if (caption.isEmpty()) View.GONE else View.VISIBLE
        val avatar = findViewById<ImageView>(R.id.result_avatar)
        avatar.setImageDrawable(null)
        if (!r.avatar.isNullOrEmpty()) ImageLoader.load(r.avatar, avatar)
        renderResultChips(r)
        renderResultPreview(r.items[0])
        syncDialogPosition()
        hideSystemBars()
    }

    private fun platformName(p: String?): String = when (p) {
        "douyin" -> "抖音"
        "kuaishou" -> "快手"
        "bilibili" -> "哔哩哔哩"
        "xiaohongshu" -> "小红书"
        "instagram" -> "Instagram"
        "twitter", "x" -> "X"
        "tiktok" -> "TikTok"
        "facebook" -> "Facebook"
        "pinterest" -> "Pinterest"
        "youtube" -> "YouTube"
        "weibo" -> "微博"
        else -> "视频"
    }

    /** 档位短标签：从完整 label 里取 1080 / 720 这类分辨率，图片帖显示「图N」。 */
    private fun chipLabel(m: TweetFetcher.Media, idx: Int, photos: Int): String {
        if (m.kind == "photo") return if (photos > 1) "图${idx + 1}" else "原图"
        val l = m.label ?: ""
        val q = listOf("2160", "1440", "1080", "720", "480", "360")
            .firstOrNull { l.contains(it, true) }
        return if (q != null) "${q}P" else (l.take(10).ifEmpty { "默认" })
    }

    private fun renderResultChips(r: TweetFetcher.Result) {
        resultChips.removeAllViews()
        resultChipViews.clear()
        val photos = r.items.count { it.kind == "photo" }
        val d = resources.displayMetrics.density
        r.items.forEachIndexed { i, m ->
            val tv = TextView(this)
            tv.text = chipLabel(m, i, photos)
            tv.textSize = 12.5f
            tv.gravity = Gravity.CENTER
            val px = (12 * d).toInt()
            val py = (6 * d).toInt()
            tv.setPadding(px, py, px, py)
            tv.setOnClickListener { selectResultMedia(i) }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (8 * d).toInt()
            tv.layoutParams = lp
            resultChips.addView(tv)
            resultChipViews.add(tv)
        }
        paintResultChips()
    }

    private fun paintResultChips() {
        resultChipViews.forEachIndexed { i, tv ->
            val active = i == selectedIndex
            tv.background = ContextCompat.getDrawable(
                this,
                if (active) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.dialog_text else R.color.dialog_subtext
                )
            )
        }
    }

    private fun selectResultMedia(index: Int) {
        val r = parsedResult ?: return
        val m = r.items.getOrNull(index) ?: return
        selectedIndex = index
        paintResultChips()
        renderResultPreview(m)
    }

    private fun renderResultPreview(m: TweetFetcher.Media) {
        val iv = findViewById<ImageView>(R.id.result_media)
        val orient = findViewById<TextView>(R.id.result_orient)
        val badge = m.orient()
        orient.text = badge
        orient.visibility = if (badge.isEmpty()) View.GONE else View.VISIBLE
        iv.setImageDrawable(null)
        val thumb = if (m.thumb.isNullOrEmpty()) (parsedResult?.avatar ?: "") else (m.thumb ?: "")
        if (thumb.isNotEmpty()) ImageLoader.load(thumb, iv)
    }

    /** 回到输入态：隐藏结果卡片，按 settingsVisible 恢复设置区（默认关闭）。 */
    private fun clearResult() {
        parsedResult = null
        parsedRaw = ""
        selectedIndex = 0
        if (::resultArea.isInitialized) resultArea.visibility = View.GONE
        if (::parseSkeleton.isInitialized) parseSkeleton.visibility = View.GONE
        if (::parseStatus.isInitialized) parseStatus.visibility = View.GONE
        if (::settingsArea.isInitialized)
            settingsArea.visibility = if (settingsVisible) View.VISIBLE else View.GONE
        syncDialogPosition()
    }

    /** 点「下载 · 无水印」：按选中档位，带上 Referer / 登录 Cookie，交给 DlService。 */
    private fun downloadSelected() {
        val r = parsedResult ?: return
        val m = r.items.getOrNull(selectedIndex) ?: return
        val title = deriveTitle(parsedRaw, r)
        val category = if (m.kind == "photo") "image" else "video"
        val cookie =
            if (r.platform == "bilibili" && BiliSession.has(this))
                (BiliSession.cookie(this) ?: "") else ""
        DlService.setCookie(cookie)

        val started = startDownload(
            url = m.url ?: "",
            audio = m.audioUrl ?: "",
            name = title,
            kind = m.kind ?: "video",
            referer = refererFor(r.platform),
            thumb = if (m.thumb.isNullOrEmpty()) (r.avatar ?: "") else (m.thumb ?: ""),
            page = r.tweetUrl ?: "",
            sub = r.author ?: "",
            key = parsedRaw.hashCode().toString(),
            label = m.label
        )
        if (!started) return

        tasks.add(0, Task(title, parsedRaw, qualityOf(m.label), category, now(), "下载中"))
        saveTasks()
        clearResult()
        hideDialog()
        switchToMediaAndShow()
        toast("已开始下载：$title")
    }

    /** 普通直链（非平台分享链接）：解析失败时直接下载。 */
    private fun startDirectDownload(link: String) {
        val title = deriveTitle(link, null)
        val category = categoryOf(link)
        val started = startDownload(
            url = link,
            audio = "",
            name = title,
            kind = if (category == "image") "photo" else "video",
            referer = "",
            thumb = "",
            page = link,
            sub = "直链下载",
            key = link.hashCode().toString(),
            label = null
        )
        if (!started) return
        tasks.add(0, Task(title, link, quality, category, now(), "下载中"))
        saveTasks()
        hideDialog()
        switchToMediaAndShow()
        toast("已开始下载：$title")
    }

    /** DlService 11 参数重载：队列 / Range 续传 / 分片并发 / HLS / 双流合成 / 写相册。 */
    private fun startDownload(
        url: String, audio: String, name: String, kind: String,
        referer: String, thumb: String, page: String, sub: String,
        key: String, label: String?
    ): Boolean {
        if (url.isEmpty()) { toast("没有拿到下载直链"); return false }
        return try {
            DlService.start(
                this, url, audio, name, kind, referer,
                Prefs.galleryVisible(this), thumb, page, sub, key
            )
            true
        } catch (e: Exception) {
            toast("无法启动下载：${e.message}")
            false
        }
    }

    /** 自动取解析器给的第一档；指定 1080P / 720P 时按展示名匹配。 */
    private fun pickMedia(r: TweetFetcher.Result): TweetFetcher.Media {
        val items = r.items
        if (items.size == 1) return items[0]
        if (quality == "1080P") {
            items.firstOrNull { (it.label ?: "").contains("1080", true) }?.let { return it }
        }
        if (quality == "720P") {
            items.firstOrNull { (it.label ?: "").contains("720", true) }?.let { return it }
        }
        return items[0]
    }

    private fun refererFor(p: String?): String = when (p) {
        "instagram" -> "https://www.instagram.com/"
        "bilibili" -> "https://www.bilibili.com/"
        "xiaohongshu" -> "https://www.xiaohongshu.com/"
        "douyin" -> "https://www.douyin.com/"
        "kuaishou" -> "https://www.kuaishou.com/"
        "tiktok" -> "https://www.tiktok.com/"
        "twitter" -> "https://x.com/"
        "facebook" -> "https://www.facebook.com/"
        "pinterest" -> "https://www.pinterest.com/"
        "youtube" -> "https://www.youtube.com/"
        else -> ""
    }

    private fun switchToMediaAndShow() {
        switchPage(1)
        selectTab(3)
    }

    // ---------- 列表 / 设置页 / 账号 ----------

    private val recTick = android.os.Handler(android.os.Looper.getMainLooper())

    /** 原版引擎的实时录制任务 → 图2 行（完整标题 + 三行独立信息，每秒刷新） */
    private fun recRows(): List<Task> = try {
        com.ziyuan.Assistant.RecManager.listRecords().map { r ->
            val title = if (r.title.contains("抖音·")) r.title else "抖音·原画 " + r.title
            // 图2 三行：录制时长 / 录制大小 / 录制状态（各占一行）
            val state = if (r.state == "暂停录制") "暂停录制" else r.state
            val meta = "录制时长: ${r.secs}\n录制大小: ${r.size}\n录制状态: $state"
            Task(title, com.ziyuan.Assistant.RecManager.recUrl(r.id), "原画", "录制", "", r.state, "", r.id, meta)
        }
    } catch (_: Throwable) { emptyList() }

    /** 图1 五个标签的过滤：全部/任务显示全部，视频/音频/下载各自筛选 */
    private fun currentPool(): List<Task> {
        val all = recRows() + tasks
        return when (currentTab) {
            1 -> all.filter { it.category == "video" }
            2 -> all.filter { it.category == "audio" }
            4 -> all.filter { it.recId < 0 && (it.state == "已下载" || it.state == "已完成") }
            else -> all
        }
    }

    /** 行副标题：录制行「时长/大小/状态」三行（图2），下载行「画质 · 时间」 */
    private fun metaOf(t: Task) = if (t.recId >= 0) t.recMeta else "${t.quality} · ${t.time}"
    /** 行右侧状态：录制行状态已并入 meta 第三行，右侧不再单独显示；下载行直接显示状态 */
    private fun stateOf(t: Task) = if (t.recId >= 0) "" else t.state

    /** 按开关状态给一行任务卡上色（背景描边卡 + 缩略块 + 标题/副信息/状态/⋮） */
    private fun tintTaskRow(item: View) {
        val p = mediaPalette()
        val d = resources.displayMetrics.density
        item.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(p.card)
            setStroke(maxOf(1, (1 * d).toInt()), p.cardStroke)
            cornerRadius = 14 * d
        }
        item.findViewById<TextView>(R.id.task_title).setTextColor(p.title)
        item.findViewById<TextView>(R.id.task_meta).setTextColor(p.sub)
        item.findViewById<TextView>(R.id.task_state).setTextColor(p.state)
        item.findViewById<TextView>(R.id.task_more).setTextColor(p.sub)
        val box = item.findViewById<FrameLayout>(R.id.task_thumb_box)
        box.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(p.thumbBg)
            cornerRadius = 8 * d
        }
        box.outlineProvider = ViewOutlineProvider.BACKGROUND
        box.clipToOutline = true
        item.findViewById<TextView>(R.id.task_play).setTextColor(p.thumbPlay)
    }

    /** 录制行已绑定的缩略图 uri（uri 变化才重绑，避免每秒刷新重复解码正在写的 ts） */
    private val recThumbBound = java.util.HashMap<Int, String>()
    /** 录制行抽帧失败后的节流重试时间戳（分段刚落盘太小时，隔几秒再试） */
    private val recThumbRetryAt = java.util.HashMap<Int, Long>()

    /**
     * 行左侧缩略图：录制中/完成都取本地帧（正在落盘的 seg*.ts 或合并后的 mp4）/ 网络封面。
     * ▶ 常驻下层兜底：抽到帧后 ImageView 铺满盖住它，抽不到帧自然露出 ▶。
     */
    private fun bindThumb(item: View, t: Task) {
        val iv = item.findViewById<ImageView>(R.id.task_thumb)
        val (e, f) = try { locate(t) } catch (_: Throwable) { null to null }
        val netThumb = e?.thumb.orEmpty()
        val kind = if (t.category == "image" || t.category == "photo") "photo" else "video"
        val localUri: Uri? = when {
            f != null && f.exists() -> Uri.fromFile(f)
            e != null && (e.uri.startsWith("file://") || e.uri.startsWith("content://")) -> Uri.parse(e.uri)
            else -> null
        }
        // 录制中优先用正在落盘的分段 ts 抽帧，合并完成后自动换成 mp4
        val recUri = if (t.recId >= 0) {
            val path = try { com.ziyuan.Assistant.RecManager.recThumbPath(t.recId) } catch (_: Throwable) { null }
            if (!path.isNullOrEmpty()) Uri.parse(path) else null
        } else null
        val uri = localUri ?: recUri
        if (uri != null || netThumb.startsWith("http")) {
            iv.visibility = View.VISIBLE
            ThumbLoader.load(this, iv, uri, kind, netThumb)
        } else {
            iv.visibility = View.GONE
        }
        if (t.recId >= 0) recThumbBound[t.recId] = uri?.toString().orEmpty()
    }

    private fun renderTasks() {
        if (!::taskList.isInitialized) return
        applyMediaTheme()
        val filtered = currentPool()
        recTick.removeCallbacksAndMessages(null)
        if (filtered.any { it.recId >= 0 }) recTick.postDelayed({ renderTasks() }, 1000)
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        taskScroll.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE

        // diff 刷新：行集合不变时只更新副标题与状态文字，不重建 View（菜单不被打断）
        val keys = filtered.map { if (it.recId >= 0) "R${it.recId}" else "T${it.title}#${it.file}" }
        val curKeys = (0 until taskList.childCount).map { taskList.getChildAt(it).tag as? String ?: "" }
        if (keys == curKeys) {
            filtered.forEachIndexed { i, t ->
                val row = taskList.getChildAt(i)
                val m = metaOf(t)
                val s = stateOf(t)
                val tv = row.findViewById<TextView>(R.id.task_meta)
                if (tv.text.toString() != m) tv.text = m
                val sv = row.findViewById<TextView>(R.id.task_state)
                if (sv.text.toString() != s) sv.text = s
                tintTaskRow(row)   // 明暗切换后即使行不重建也能立刻换色
                // 录制中：分段 ts 长到可抽帧 → 补真实封面；合并出 mp4（uri 变化）→ 再补一次。
                // 只按 uri 变化重绑，避免每秒刷新重复解码正在写入的 ts。
                if (t.recId >= 0) {
                    val rp = try { com.ziyuan.Assistant.RecManager.recThumbPath(t.recId) } catch (_: Throwable) { null }
                    val key = rp.orEmpty()
                    val iv = row.findViewById<ImageView>(R.id.task_thumb)
                    // uri 变了 → 重绑；或还没抽到帧且距上次重试 > 4s → 节流重试（ts 逐渐长大）
                    val noFrame = iv.drawable == null
                    val now = android.os.SystemClock.elapsedRealtime()
                    val due = noFrame && now - (recThumbRetryAt[t.recId] ?: 0L) > 4000
                    if (key.isNotEmpty() && (recThumbBound[t.recId] != key || due)) {
                        if (noFrame) recThumbRetryAt[t.recId] = now
                        bindThumb(row, t)
                    }
                }
            }
            return
        }

        taskList.removeAllViews()
        val inflater = LayoutInflater.from(this)
        filtered.forEachIndexed { idx, t ->
            // 录制行用图2 三行排版，下载行用截图单行排版
            val layout = if (t.recId >= 0) R.layout.item_rec else R.layout.item_task
            val item = inflater.inflate(layout, taskList, false)
            item.tag = keys[idx]
            item.findViewById<TextView>(R.id.task_title).text = t.title
            item.findViewById<TextView>(R.id.task_meta).text = metaOf(t)
            item.findViewById<TextView>(R.id.task_state).text = stateOf(t)
            val more = item.findViewById<View>(R.id.task_more)
            more.setOnClickListener { if (t.recId >= 0) showRecMenu(more, t) else showItemMenu(more, t) }
            item.setOnLongClickListener {
                if (t.recId >= 0) showRecMenu(more, t) else showItemMenu(more, t); true
            }
            if (t.recId < 0) item.setOnClickListener { openEntry(t) }
            tintTaskRow(item)
            bindThumb(item, t)
            taskList.addView(item)
        }
    }

    // ---------- 图1 条目操作菜单：打开 / 分享 / 选取 / 属性 / 删除 / 打开为 /
    //           重命名 / 重新下载 / 查看文件位置 / 复制下载地址 / 打开所在目录 ----------

    /** 锚定条目右上角 ⋮ 的深色上下文菜单（图1 11 项），逐项落到真实文件 / 下载历史。 */
    /**
     * 录制任务 ⋮ 菜单：1:1 原版 RecordActivity.showMenu()
     * 打开 / 继续录制(录制中则暂停) / 结束录制(转MP4) / 复制下载地址 /
     * 打开所在目录 / 播放视频 / 播放（本 App 播放器） / 取消。
     * 深色小尺寸浮层，字号 13f、紧凑 padding。
     */
    private fun showRecMenu(anchor: View, t: Task) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val p = mediaPalette()
        val bg = p.menuBg
        val textColor = p.menuText
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(bg)
            setPadding(dp(2), dp(5), dp(2), dp(5))
        }
        var popup: PopupWindow? = null
        val live = try { com.ziyuan.Assistant.RecManager.recLive(t.recId) } catch (_: Throwable) { false }
        val playPath = try { com.ziyuan.Assistant.RecManager.recPlayPath(t.recId) } catch (_: Throwable) { null }
        val items = mutableListOf<String>()
        if (live) items.add("暂停")
        else { items.add("打开"); items.add("继续录制") }
        items.add("结束录制(转MP4)")
        items.add("复制下载地址")
        items.add("打开所在目录")
        items.add("播放视频")
        items.add("播放")
        items.add("取消")

        /** 用本 App 播放器（原版 NativePlayerActivity）播放指定地址 */
        fun playInApp(path: String) {
            startActivity(Intent(this@MainActivity, com.ziyuan.Assistant.NativePlayerActivity::class.java)
                .putExtra("url", path).putExtra("title", t.title))
        }

        items.forEach { label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(textColor)
                setPadding(dp(13), dp(9), dp(14), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
            }
            tv.setOnClickListener {
                popup?.dismiss()
                when (label) {
                    "暂停", "继续录制" -> { com.ziyuan.Assistant.RecManager.recToggle(t.recId); toast(if (label == "暂停") "录制已暂停" else "录制已继续") }
                    "打开" -> {
                        val p = try { com.ziyuan.Assistant.RecManager.recPlayPath(t.recId) } catch (_: Throwable) { null }
                        if (p != null) playInApp(p) else toast("还没生成视频，结束后才能播放")
                    }
                    "结束录制(转MP4)" -> { com.ziyuan.Assistant.RecManager.recFinish(t.recId); toast("录制已结束，正在合并 MP4") }
                    "复制下载地址" -> {
                        val u = try { com.ziyuan.Assistant.RecManager.recUrl(t.recId) } catch (_: Throwable) { "" }
                        if (u.isNotEmpty()) { copyText("下载地址", u); toast("已复制下载地址") }
                        else toast("这条录制没有下载地址")
                    }
                    "打开所在目录" -> openRecDir(t.recId)
                    "播放视频" -> {
                        // 原版 playWithCopy：优先直播流地址，其次已合并的最新 MP4
                        val u = try { com.ziyuan.Assistant.RecManager.recUrl(t.recId) } catch (_: Throwable) { "" }
                        val p = try { com.ziyuan.Assistant.RecManager.recPlayPath(t.recId) } catch (_: Throwable) { null }
                        val target = u.ifEmpty { p.orEmpty() }
                        if (target.isNotEmpty()) {
                            if (u.isNotEmpty()) copyText("下载地址", u)
                            playInApp(target)
                        } else toast("还没生成视频，结束后才能播放")
                    }
                    "播放" -> {
                        val p = try { com.ziyuan.Assistant.RecManager.recPlayPath(t.recId) } catch (_: Throwable) { null }
                        if (p != null) playInApp(p) else toast("还没生成视频，结束后才能播放")
                    }
                    "取消" -> { com.ziyuan.Assistant.RecManager.recCancel(t.recId); toast("已取消该录制") }
                }
                renderTasks()
            }
            panel.addView(tv)
        }
        popup = PopupWindow(panel, dp(164),
            LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(ColorDrawable(bg))
            elevation = dp(8).toFloat()
            showAsDropDown(anchor, 0, -dp(4), Gravity.END)
        }
    }

    /** 打开录制文件所在目录（系统文件管理器，失败则展示并复制路径） */
    private fun openRecDir(jid: Int) {
        val abs = try { com.ziyuan.Assistant.RecManager.recDir(jid) } catch (_: Throwable) { "" }
        if (abs.isEmpty()) { toast("还没有生成录制文件"); return }
        val rel = when {
            abs.startsWith("/storage/emulated/0/") -> abs.removePrefix("/storage/emulated/0/")
            abs.startsWith("/sdcard/") -> abs.removePrefix("/sdcard/")
            else -> ""
        }
        if (rel.isNotEmpty()) {
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$rel"
            )
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(i); return } catch (_: Throwable) { }
        }
        copyText("录制目录", abs)
        toast("目录：$abs\n已复制路径")
    }

    private fun showItemMenu(anchor: View, t: Task) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val pm = mediaPalette()
        val cardColor = pm.menuBg
        val textColor = pm.menuText
        val lineColor = pm.menuLine
        val typed = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        val ripple = if (typed.resourceId != 0) ContextCompat.getDrawable(this, typed.resourceId) else null

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ColorDrawable(cardColor)
            setPadding(dp(2), dp(8), dp(2), dp(8))
        }
        var popup: PopupWindow? = null
        itemMenu.forEachIndexed { idx, label ->
            // 原图在「重新下载」「查看文件位置」前有分组留白
            if (idx == 7 || idx == 8) {
                val gap = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
                    ).apply { setMargins(dp(14), dp(7), dp(14), dp(7)) }
                    background = ColorDrawable(lineColor)
                }
                panel.addView(gap)
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 16f
                setTextColor(textColor)
                setPadding(dp(13), dp(9), dp(14), dp(9))
                background = ripple?.constantState?.newDrawable() ?: ripple
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
            }
            tv.setOnClickListener {
                popup?.dismiss()
                when (label) {
                    "播放视频" -> openEntry(t)
                    "打开" -> openEntry(t)
                    "分享" -> shareTasks(listOf(t))
                    "选取" -> multiSelect()
                    "属性" -> showProperties(t)
                    "删除" -> confirmDelete(listOf(t))
                    "打开为" -> openAs(t)
                    "重命名" -> renameEntry(t)
                    "重新下载" -> redownload(t)
                    "查看文件位置" -> viewLocation(t)
                    "复制下载地址" -> copyLink(t)
                    "打开所在目录" -> openFolderOf(t)
                }
            }
            panel.addView(tv)
        }
        val scroller = ScrollView(this).apply {
            background = ColorDrawable(cardColor)
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
        val w = PopupWindow(
            scroller, dp(168),
            LinearLayout.LayoutParams.WRAP_CONTENT, true
        )
        w.setBackgroundDrawable(ColorDrawable(cardColor))
        w.setOutsideTouchable(true)
        w.elevation = dp(8).toFloat()
        popup = w
        w.showAsDropDown(anchor, 0, -dp(4), Gravity.END)
    }

    /** 从真实下载历史里定位这条任务对应的落盘记录（DlService 完成广播带 fname）。 */
    private fun entryFor(t: Task): HistoryStore.Entry? {
        val all = try { HistoryStore.list(this) } catch (_: Throwable) { return null }
        if (t.file.isNotEmpty()) {
            all.firstOrNull { it.name == t.file }?.let { return it }
        }
        val base = t.title.trim()
        if (base.isEmpty()) return null
        return all.firstOrNull { e ->
            e.pageUrl == t.url ||
                e.name.substringBeforeLast('.').contains(base) ||
                base.contains(e.name.substringBeforeLast('.'))
        }
    }

    private fun fileFor(e: HistoryStore.Entry): File {
        // 原版引擎桥接的记录：file:// 直通本地文件；content:// 从 MediaStore 反查路径
        if (e.uri.startsWith("file://")) return File(Uri.parse(e.uri).path ?: "")
        if (e.uri.startsWith("content://")) {
            try {
                getContentResolver().query(Uri.parse(e.uri),
                    arrayOf(android.provider.MediaStore.MediaColumns.DATA), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        val p = cur.getString(0)
                        if (p != null && File(p).exists()) return File(p)
                    }
                }
            } catch (_: Throwable) { }
        }
        return if (e.visible) Store.publicFile(e.kind, e.name)
        else Store.privateFile(this, e.kind, e.name)
    }

    /** 任务 → (历史记录, 真实文件)；找不到文件时仍允许复制地址 / 重新下载。 */
    private fun locate(t: Task): Pair<HistoryStore.Entry?, File?> {
        val e = entryFor(t)
        if (e != null) return e to fileFor(e)
        if (t.file.isNotEmpty()) {
            val kind = if (t.category == "image") "photo" else "video"
            val priv = Store.privateFile(this, kind, t.file)
            if (priv.exists()) return null to priv
            val pub = Store.publicFile(kind, t.file)
            if (pub.exists()) return null to pub
        }
        return null to null
    }

    private fun mimeOf(name: String): String {
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".aac") ||
                n.endsWith(".wav") || n.endsWith(".flac") || n.endsWith(".ogg") -> "audio/*"
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") ||
                n.endsWith(".webp") || n.endsWith(".gif") -> "image/*"
            else -> "video/*"
        }
    }

    /** 打开：Store.playUri 会校验 MediaStore uri，失效自动回退到本 App 的 content 通道。 */
    private fun openEntry(t: Task, asMime: String? = null) {
        val (e, f) = locate(t)
        val file = f ?: run { toast("还没找到本地文件，可能仍在下载"); return }
        if (!file.exists()) { toast("文件已不在本地，可用「重新下载」"); return }
        // 「打开」= 本 App 播放器（原版 NativePlayerActivity，dkplayer/Ijk 内核 + 沉浸式）
        try {
            startActivity(android.content.Intent(this, com.ziyuan.Assistant.NativePlayerActivity::class.java)
                .putExtra("url", "file://" + file.absolutePath)
                .putExtra("title", t.title))
            return
        } catch (_: Throwable) { }
        val uri = if (e != null) Store.playUri(this, e) else LocalFiles.uriFor(this, file)
        if (uri == null) { toast("打不开这个文件"); return }
        val mime = asMime ?: (if (e != null) Store.mime(e.name, e.kind) else mimeOf(file.name))
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "打开")) }
        catch (_: Throwable) { toast("没有能打开这个文件的 App") }
    }

    /** 分享：单条 ACTION_SEND / 多条 ACTION_SEND_MULTIPLE，content uri 临时授权读。 */
    private fun shareTasks(list: List<Task>) {
        val uris = ArrayList<Uri>()
        for (t in list) {
            val (e, f) = locate(t)
            val file = f ?: continue
            if (!file.exists()) continue
            val u = if (e != null) Store.playUri(this, e) else LocalFiles.uriFor(this, file)
            if (u != null) uris.add(u)
        }
        if (uris.isEmpty()) { toast("这些任务还没有可分享的本地文件"); return }
        val mime = if (list.size == 1 && uris.size == 1) {
            val (e, f) = locate(list[0])
            if (e != null) Store.mime(e.name, e.kind) else mimeOf(f?.name ?: "")
        } else "*/*"
        val i = if (uris.size == 1)
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        i.type = mime
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { startActivity(Intent.createChooser(i, "分享到")) }
        catch (_: Throwable) { toast("没有可用的分享应用") }
    }

    /** 选取：进入多选，勾若干条后批量分享 / 批量删除（删除同样落到真实文件）。 */
    private fun multiSelect() {
        val pool = currentPool().filter { it.recId < 0 }
        if (pool.isEmpty()) { toast("当前没有可选取的文件"); return }
        val checked = BooleanArray(pool.size)
        val labels = Array<CharSequence>(pool.size) { i -> "${pool[i].title}  ·  ${pool[i].state}" }
        AlertDialog.Builder(this)
            .setTitle("选取（可多选）")
            .setMultiChoiceItems(labels, checked) { _, w, on -> checked[w] = on }
            .setNegativeButton("取消", null)
            .setNeutralButton("分享所选") { _, _ ->
                val picked = pool.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) toast("没有勾选任何条目") else shareTasks(picked)
            }
            .setPositiveButton("删除所选") { _, _ ->
                val picked = pool.filterIndexed { i, _ -> checked[i] }
                if (picked.isEmpty()) toast("没有勾选任何条目") else confirmDelete(picked)
            }
            .show()
    }

    /** 属性：真实读文件大小 / 修改时间 / 路径 / 保存位置 / 来源链接。 */
    private fun showProperties(t: Task) {
        val (e, f) = locate(t)
        val exists = f?.exists() == true
        val size = if (exists) Formatter.formatFileSize(this, f!!.length()) else "—"
        val whenStr = if (exists)
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(f!!.lastModified()))
            else t.time
        val kind = if (t.category == "image") "photo" else "video"
        val fallbackDir = if (e?.visible == true) Store.publicDir(kind) else Store.privateDir(this, kind)
        val path = f?.absolutePath
            ?: File(fallbackDir, e?.name ?: t.file.ifEmpty { t.title }).absolutePath
        val name = e?.name ?: t.file.ifEmpty { t.title }
        val msg = buildString {
            append("名称：").append(name).append('\n')
            append("类型：").append(if (e != null) Store.mime(e.name, e.kind) else mimeOf(name)).append('\n')
            append("大小：").append(size).append('\n')
            append("保存位置：").append(if (e?.visible == true) "系统相册（可见）" else "App 私有目录（隐藏）").append('\n')
            append("修改时间：").append(whenStr).append('\n')
            append("状态：").append(t.state).append('\n')
            append("清晰度：").append(t.quality).append('\n')
            append("来源：").append(t.url).append('\n')
            append('\n').append("路径：").append(path)
        }
        AlertDialog.Builder(this)
            .setTitle("属性")
            .setMessage(msg)
            .setPositiveButton("复制路径") { _, _ -> copyText("文件路径", path) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmDelete(list: List<Task>) {
        AlertDialog.Builder(this)
            .setTitle("删除")
            .setMessage("将删除 ${list.size} 项的记录与本地文件，删除后不再出现在列表里。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteTasks(list) }
            .show()
    }

    /** 删除：删真实文件 + HistoryStore.remove（bury，补扫描不会复活）+ 移除任务。 */
    private fun deleteTasks(list: List<Task>) {
        var n = 0
        for (t in list) {
            val (e, f) = locate(t)
            if (e != null) {
                if (e.visible) {
                    try { Store.removePublic(this, e) } catch (_: Throwable) { }
                } else {
                    try { fileFor(e).delete() } catch (_: Throwable) { }
                }
                try { HistoryStore.remove(this, e) } catch (_: Throwable) { }
            } else {
                try { f?.delete() } catch (_: Throwable) { }
                if (t.file.isNotEmpty())
                    try { HistoryStore.bury(this, t.file) } catch (_: Throwable) { }
            }
            tasks.remove(t)
            n++
        }
        saveTasks(); renderTasks(); toast("已删除 $n 项")
    }

    /** 打开为：指定 mime 用其它应用打开（视频 / 音频 / 图片 / 文本）。 */
    private fun openAs(t: Task) {
        val types: Array<CharSequence> = arrayOf("视频", "音频", "图片", "文本")
        val mimes = arrayOf("video/*", "audio/*", "image/*", "text/*")
        AlertDialog.Builder(this)
            .setTitle("打开为")
            .setItems(types) { _, w -> openEntry(t, mimes[w]) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 重命名：改真实文件名，并同步更新 HistoryStore 记录与任务标题。 */
    private fun renameEntry(t: Task) {
        val (e, f) = locate(t)
        val file = f ?: run { toast("文件还没准备好，无法重命名"); return }
        if (!file.exists()) { toast("本地文件不存在"); return }
        val old = file.name
        val ext = old.substringAfterLast('.', "")
        val base = if (ext.isNotEmpty()) old.substringBeforeLast('.') else old
        val density = resources.displayMetrics.density
        val input = EditText(this).apply {
            setText(base)
            setSelection(base.length)
            hint = "新文件名"
        }
        val box = FrameLayout(this).apply {
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名")
            .setView(box)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val newBase = input.text.toString().trim()
                if (newBase.isEmpty() || newBase == base) return@setPositiveButton
                val newName = if (ext.isNotEmpty()) "$newBase.$ext" else newBase
                val target = File(file.parentFile, newName)
                if (target.exists()) { toast("已存在同名文件"); return@setPositiveButton }
                val ok = try { file.renameTo(target) } catch (_: Throwable) { false }
                if (!ok) { toast("重命名失败（系统不允许改动该文件）"); return@setPositiveButton }
                // 相册可见文件：同步 MediaStore 里的显示名，避免列表与文件对不上
                if (e != null && e.visible && e.uri.startsWith("content://") &&
                    Build.VERSION.SDK_INT >= 29) {
                    try {
                        val cv = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, newName)
                        }
                        contentResolver.update(Uri.parse(e.uri), cv, null, null)
                    } catch (_: Throwable) { }
                }
                if (e != null) {
                    val ne = HistoryStore.Entry()
                    ne.name = newName; ne.sub = e.sub; ne.pageUrl = e.pageUrl
                    ne.thumb = e.thumb; ne.kind = e.kind; ne.uri = e.uri
                    ne.key = e.key; ne.visible = e.visible; ne.ts = e.ts
                    ne.orientation = e.orientation
                    try { HistoryStore.remove(this, e) } catch (_: Throwable) { }
                    try { HistoryStore.add(this, ne) } catch (_: Throwable) { }
                } else {
                    try { HistoryStore.bury(this, old) } catch (_: Throwable) { }
                }
                t.file = newName
                t.title = newBase
                saveTasks(); renderTasks(); toast("已重命名为 $newName")
            }
            .show()
    }

    /** 重新下载：把原始链接放回弹窗，复用原版「解析 → 结果卡片 → 下载」完整链路。 */
    private fun redownload(t: Task) {
        if (t.url.isEmpty()) { toast("这条任务没有原始链接"); return }
        switchPage(0)
        etLink.setText(t.url)
        etLink.setSelection(t.url.length)
        showDialog()
        addTask()
    }

    /** 查看文件位置：显示真实绝对路径，可一键复制。 */
    private fun viewLocation(t: Task) {
        val (e, f) = locate(t)
        val kind = if (t.category == "image") "photo" else "video"
        val fallbackDir = if (e?.visible == true) Store.publicDir(kind) else Store.privateDir(this, kind)
        val name = e?.name ?: t.file.ifEmpty { t.title }
        val path = f?.absolutePath ?: File(fallbackDir, name).absolutePath
        val dirPath = f?.parentFile?.absolutePath ?: fallbackDir.absolutePath
        AlertDialog.Builder(this)
            .setTitle("查看文件位置")
            .setMessage("文件名：$name\n所在目录：$dirPath\n\n完整路径：\n$path")
            .setPositiveButton("复制路径") { _, _ -> copyText("文件路径", path) }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun copyLink(t: Task) {
        if (t.url.isEmpty()) { toast("这条任务没有下载地址"); return }
        copyText("下载地址", t.url)
        toast("已复制下载地址")
    }

    private fun copyText(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /**
     * 打开所在目录：用 DocumentsContract 请求系统文件管理器定位到目录（不暴露 file://）。
     * Android 11+ 通常拒绝直接打开 Android/data，失败则退回「查看文件位置」并复制路径。
     */
    private fun openFolderOf(t: Task) {
        val (e, f) = locate(t)
        val kind = if (t.category == "image") "photo" else "video"
        val dir = f?.parentFile
            ?: (if (e?.visible == true) Store.publicDir(kind) else Store.privateDir(this, kind))
        val abs = dir.absolutePath
        val rel = when {
            abs.startsWith("/storage/emulated/0/") -> abs.removePrefix("/storage/emulated/0/")
            abs.startsWith("/sdcard/") -> abs.removePrefix("/sdcard/")
            else -> ""
        }
        if (rel.isNotEmpty()) {
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:$rel"
            )
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(docUri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try { startActivity(i); return } catch (_: Throwable) { }
        }
        toast("系统不允许直接打开该目录，已显示位置")
        viewLocation(t)
    }

    // ---------- 设置页：1:1 原版深色卡片，接 DownX 真实 Prefs / Store / HistoryStore ----------

    private fun setupSettingsPage() {
        // 明暗切换：写入偏好后重建 Activity，attachBaseContext 会用新 uiMode 加载 -night 资源
        val swDark = findViewById<Switch>(R.id.sw_dark)
        swDark.isChecked = ThemeMode.isDark(this)
        swDark.setOnCheckedChangeListener { _, on ->
            ThemeMode.setDark(this, on)
            // 立即按新开关值重刷媒体管理页（代码级着色，不依赖 recreate / 系统夜间资源）
            selectTab(currentTab)   // 内部会 applyMediaTheme() + renderTasks()
            toast(if (on) "已开启深色模式，媒体管理已变暗" else "已关闭深色模式，媒体管理已变亮")
            intent.putExtra("restore_page", currentPage)
            recreate()
        }
        val sw = findViewById<Switch>(R.id.sw_gallery)
        sw.isChecked = Prefs.galleryVisible(this)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.setGalleryVisible(this, on)
            saveGallery = on
            saveSettings()
            refreshSettingsUi()
            // 已有文件是否需要一起搬家（原版 SettingsActivity.onGalleryToggle 的对账逻辑）
            val st = stats()
            val need = if (on) st[1] else (st[0] - st[1]).coerceAtLeast(0)
            if (need == 0) {
                toast("设置已保存，之后下载的文件按新规则保存")
            } else {
                AlertDialog.Builder(this)
                    .setTitle(if (on) "把已有文件放回相册？" else "把已有文件移出相册？")
                    .setMessage(
                        "有 $need 个文件需要" +
                            (if (on) "放回相册目录。" else "移动到 App 私有目录，移出后相册里就看不到了。") +
                            "\n\n这是搬家不是复制，文件总数不变。"
                    )
                    .setNegativeButton("只影响以后") { _, _ -> toast("已保存设置，已有文件保持原样") }
                    .setPositiveButton("现在搬") { _, _ -> doMigrate(on) }
                    .show()
            }
        }

        // 逐条管理文件：进入移植过来的 FilesActivity（每文件一个开关 / 多选 / 批量删 / moveOne 单条搬家）
        findViewById<View>(R.id.row_files).setOnClickListener {
            startActivity(Intent(this, FilesActivity::class.java))
        }
        // 使用说明与支持平台：进入移植过来的 HelpActivity（原版完整平台清单与用法）
        findViewById<View>(R.id.row_help).setOnClickListener {
            startActivity(Intent(this, HelpActivity::class.java))
        }
        // 重新扫描相册：与磁盘对账，把还在但被删掉的记录补回来（原版 doRescan）
        findViewById<View>(R.id.row_rescan).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("重新扫描相册")
                .setMessage("会把手机上还在、但列表里没有的下载补回来。\n\n以前主动删掉的记录也会一起回来（只限文件还在的那些）。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始扫描") { _, _ -> doRescan() }
                .show()
        }
        refreshSettingsUi()
    }

    private fun stats(): IntArray =
        try { Store.stats(this) } catch (t: Throwable) { intArrayOf(0, 0) }

    /** 1:1 原版 refresh()/galleryHint()：文件统计 + 相册可见说明。 */
    private fun refreshSettingsUi() {
        if (!::etLink.isInitialized) return
        val st = stats()
        val visible = Prefs.galleryVisible(this)
        findViewById<TextView>(R.id.tv_files_state).text = when {
            st[0] == 0 -> "暂无文件"
            st[1] == 0 -> "全部可见"
            else -> "${st[1]} 个已隐藏"
        }
        var hint = if (visible) "新下载的文件存进相册，相册里能看到"
        else "新下载的文件只在本 App 内可见，相册看不到"
        if (st[0] > 0) hint += "\n已有 ${st[0]} 个文件，其中 ${st[1]} 个处于隐藏状态"
        if (!visible) {
            hint += "\n隐藏文件位置：Android/data/$packageName/files/ 下的 Movies/DownX、Pictures/DownX"
            hint += "\n卸载 App 会一并删除，重要文件请先转存"
        }
        hint += "\n只想处理某一个：进「逐条管理文件」单独开关"
        findViewById<TextView>(R.id.tv_gallery_hint).text = hint
    }

    /** Store.migrate：真实的文件搬家（相册目录 ↔ App 私有目录），文件总数不变。 */
    private fun doMigrate(toVisible: Boolean) {
        toast(if (toVisible) "正在移动到相册目录…" else "正在从相册移出…")
        Thread {
            val n = try { Store.migrate(this, toVisible) } catch (t: Throwable) { 0 }
            val bad = Store.failed
            runOnUiThread {
                refreshSettingsUi()
                toast(
                    if (n > 0)
                        (if (toVisible) "已把 $n 个文件放回相册" else "已把 $n 个文件从相册隐藏")
                    else "设置已保存，之后下载的文件按新规则保存"
                )
                if (bad > 0) toast("有 $bad 个旧文件系统不允许移动")
            }
        }.start()
    }

    /** HistoryStore.clearBuried + Store.restoreHistory：与磁盘对账补回记录。 */
    private fun doRescan() {
        toast("正在扫描…")
        Thread {
            var added = 0
            try {
                HistoryStore.clearBuried(this)
                added = Store.restoreHistory(this)
            } catch (t: Throwable) { }
            val n = added
            runOnUiThread {
                loadTasks(); renderTasks(); refreshSettingsUi()
                toast(
                    if (n > 0) "补回 $n 条，回首页就能看到"
                    else "没有可以补回的记录（文件可能已经删掉了）"
                )
            }
        }.start()
    }

    /** 账号入口：调用移植过来的 B 站扫码 / IG 网页登录 Activity。 */
    private fun setupAccounts() {
        findViewById<View>(R.id.row_bili).setOnClickListener {
            if (BiliSession.has(this)) {
                AlertDialog.Builder(this)
                    .setTitle("B 站已登录")
                    .setItems(arrayOf("重新登录", "退出登录")) { _, w ->
                        if (w == 1) {
                            BiliSession.clear(this); toast("已退出 B 站，下载会回到 720P")
                        } else {
                            startActivity(Intent(this, BiliLoginActivity::class.java))
                        }
                        refreshAccountUi()
                    }
                    .setNegativeButton("取消", null).show()
            } else {
                startActivity(Intent(this, BiliLoginActivity::class.java))
            }
            refreshAccountUi()
        }
        findViewById<View>(R.id.row_ig).setOnClickListener {
            if (IgSession.has(this) && !IgSession.expired(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Instagram 已登录")
                    .setItems(arrayOf("重新登录", "退出登录")) { _, w ->
                        if (w == 1) {
                            IgSession.clear(this)
                            toast("已退出 Instagram：公开帖仍能下，私密内容需要登录")
                        } else {
                            startActivity(Intent(this, IgLoginActivity::class.java))
                        }
                        refreshAccountUi()
                    }
                    .setNegativeButton("取消", null).show()
            } else {
                startActivity(Intent(this, IgLoginActivity::class.java))
            }
            refreshAccountUi()
        }
        findViewById<View>(R.id.dialog_account).setOnClickListener {
            hideDialog()
            switchPage(2)
        }
    }

    /** 登录态 → 状态胶囊 + 说明文案，1:1 原版 SettingsActivity.refresh()。 */
    private fun refreshAccountUi() {
        if (!::etLink.isInitialized) return
        val bili = BiliSession.has(this)
        val ig = IgSession.has(this)
        val igBad = IgSession.expired(this)
        setChip(findViewById(R.id.tv_bili_state), bili, "已登录", "未登录")
        findViewById<TextView>(R.id.tv_bili_desc).text = if (bili)
            "已登录：能取到账号权限内的清晰度，1080P / 大会员 4K 原画"
        else
            "不登录：最高 720P；登录后：1080P / 大会员 4K 原画"
        if (igBad) {
            val tv = findViewById<TextView>(R.id.tv_ig_state)
            tv.text = "已失效"
            tv.setBackgroundResource(R.drawable.set_chip)
            tv.setTextColor(ContextCompat.getColor(this, R.color.set_warn))
        } else {
            setChip(findViewById(R.id.tv_ig_state), ig, "已登录", "未登录")
        }
        findViewById<TextView>(R.id.tv_ig_desc).text = when {
            !ig -> "不登录：公开帖直接能下；登录后：多档清晰度 + 私密内容"
            igBad -> "登录已失效，点这里重新登录（直接输账号密码）"
            else -> "已登录：可列出多档清晰度，私密 / 受限内容也能试"
        }
        val bs = if (bili) "已登录" else "未登录"
        val gs = if (ig) "已登录" else "未登录"
        findViewById<TextView>(R.id.dialog_account).text =
            "账号：B站$bs · IG$gs（点此去设置）"
        refreshSettingsUi()
    }

    /** 状态胶囊：已登录绿色描边，未登录中性色（原版 setChip）。 */
    private fun setChip(tv: TextView, on: Boolean, onText: String, offText: String) {
        tv.text = if (on) onText else offText
        tv.setBackgroundResource(if (on) R.drawable.set_chip_ok else R.drawable.set_chip)
        tv.setTextColor(ContextCompat.getColor(this, if (on) R.color.set_ok else R.color.set_dim))
    }

    // ---------- 本地持久化（SharedPreferences + 系统 org.json，零第三方依赖） ----------

    private fun saveTasks() {
        val arr = JSONArray()
        tasks.forEach { t ->
            arr.put(JSONObject().apply {
                put("title", t.title)
                put("url", t.url)
                put("quality", t.quality)
                put("category", t.category)
                put("time", t.time)
                put("state", t.state)
                put("file", t.file)
            })
        }
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
            .putString(KEY_TASKS, arr.toString()).apply()
    }

    private fun loadTasks() {
        tasks.clear()
        val raw = getSharedPreferences(PREF, MODE_PRIVATE)
            .getString(KEY_TASKS, "").orEmpty()
        if (raw.isEmpty()) return
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tasks.add(
                    Task(
                        o.optString("title"),
                        o.optString("url"),
                        o.optString("quality"),
                        o.optString("category"),
                        o.optString("time"),
                        o.optString("state", "已下载"),
                        o.optString("file", "")
                    )
                )
            }
        } catch (e: Exception) {
            tasks.clear()
        }
    }

    private fun loadSettings() {
        val p = getSharedPreferences(PREF, MODE_PRIVATE)
        quality = p.getString(KEY_QUALITY, "自动") ?: "自动"
        autoPaste = p.getBoolean(KEY_AUTO_PASTE, true)
        saveGallery = p.getBoolean(KEY_SAVE_GALLERY, true)
    }

    private fun saveSettings() {
        getSharedPreferences(PREF, MODE_PRIVATE).edit()
            .putString(KEY_QUALITY, quality)
            .putBoolean(KEY_AUTO_PASTE, autoPaste)
            .putBoolean(KEY_SAVE_GALLERY, saveGallery)
            .apply()
    }

    // ---------- 工具 ----------

    private fun deriveTitle(url: String, r: TweetFetcher.Result?): String {
        if (r != null) {
            val t = (r.text ?: "").trim()
            if (t.isNotEmpty() && t.length < 60) return t.take(42)
        }
        val clean = url.substringAfterLast('/').substringBefore('?').substringBefore('#')
        val name = clean.ifBlank { Uri.parse(url).host ?: "下载任务" }
        return if (name.length > 42) name.take(42) + "…" else name
    }

    private fun categoryOf(url: String): String {
        val u = url.lowercase(Locale.ROOT)
        return when {
            Regex("\\.(mp3|m4a|wav|flac|aac|ogg)(\\?|\\z)").containsMatchIn(u) -> "audio"
            Regex("\\.(jpg|jpeg|png|webp|gif|bmp)(\\?|\\z)").containsMatchIn(u) -> "image"
            else -> "video"
        }
    }

    private fun qualityOf(label: String?): String = when {
        label == null -> quality
        label.contains("1080", true) -> "1080P"
        label.contains("720", true) -> "720P"
        else -> quality
    }

    private fun now(): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
