package com.ztdrop.android

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.provider.DocumentsContract
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.TextUtils
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native views replace the prototype WebView; LanEngine remains the protocol owner. */
class MainActivity : Activity() {
    private enum class Page { HOME, CHAT, CONTACT, HISTORY, SETTINGS }
    private enum class Tab { CHATS, DEVICES, FEATURES }
    private enum class ComposerPanel { NONE, EMOJI, EXTRAS }
    private val ink get() = Color.rgb(0, 0, 0)
    private val muted get() = Color.rgb(142, 142, 147)
    private val blue get() = Color.rgb(0, 122, 255)
    private val paleBlue get() = Color.rgb(242, 242, 247)
    private val surface get() = Color.rgb(242, 242, 247)
    private val headerSurface get() = Color.rgb(247, 247, 247)
    private val chatSurface get() = Color.rgb(242, 242, 247)
    private val outgoingBubble get() = Color.rgb(149, 236, 105)
    private val border get() = Color.rgb(229, 229, 234)
    private lateinit var engine: LanEngine
    private lateinit var root: LinearLayout
    private val uiPrefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private var page = Page.HOME
    private var tab = Tab.CHATS
    private val featureDrafts = arrayOf("")
    private var featureInput: EditText? = null
    private var peerId = ""
    private var lastSnapshot = ""
    private var lastBackTap = 0L
    private var hasRenderedPage = false
    private var modernFrame: FrameLayout? = null
    private var modernScroll: ScrollView? = null
    private var segmentThumb: View? = null
    private var segmentTrack: FrameLayout? = null
    private val segmentItems = ArrayList<LinearLayout>()
    private var pageTransitionRunning = false
    private var tabGestureCandidate = false
    private var tabGestureActive = false
    private var tabGestureX = 0f
    private var tabGestureY = 0f
    /** Activity dispatch observes every home-page surface, even non-clickable blank background. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val onTabs = page in setOf(Page.HOME, Page.SETTINGS) && !pageTransitionRunning &&
            receiveSheet?.isShowing != true && shareSheet?.isShowing != true
        val x=event.rawX; val y=event.rawY
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tabGestureX=x; tabGestureY=y; tabGestureActive=false
                tabGestureCandidate=onTabs && x>dp(24) && x<resources.displayMetrics.widthPixels-dp(24) &&
                    y>statusInset && y<resources.displayMetrics.heightPixels-maxOf(dp(24),root.paddingBottom)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tabGestureCandidate=false
                if(tabGestureActive) { tabGestureActive=false; return true }
            }
            MotionEvent.ACTION_MOVE -> if(tabGestureCandidate && event.pointerCount==1) {
                val dx=x-tabGestureX; val dy=y-tabGestureY
                val slop=ViewConfiguration.get(this).scaledTouchSlop
                if(!tabGestureActive && kotlin.math.abs(dy)>slop && kotlin.math.abs(dy)>kotlin.math.abs(dx)*1.5f) {
                    tabGestureCandidate=false
                } else if(!tabGestureActive && kotlin.math.abs(dx)>slop && kotlin.math.abs(dx)>kotlin.math.abs(dy)*1.5f) {
                    tabGestureActive=true
                    val cancel=MotionEvent.obtain(event)
                    cancel.action=MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                if(tabGestureActive) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed=tabGestureActive
                if(consumed && onTabs && event.actionMasked==MotionEvent.ACTION_UP && kotlin.math.abs(x-tabGestureX)>dp(48)) {
                    selectModernPage(modernPageIndex()+if(x<tabGestureX) 1 else -1)
                }
                tabGestureCandidate=false; tabGestureActive=false
                if(consumed) return true
            }
        }
        val handled=super.dispatchTouchEvent(event)
        return handled || tabGestureCandidate
    }
    private var listRefreshing = false
    private fun refreshList() {
        if (listRefreshing || page != Page.HOME || tab == Tab.FEATURES) return
        val content=homeContent ?: return
        val selected=tab
        listRefreshing=true
        content.removeAllViews()
        content.addView(text("正在更新列表…", 14f, muted).apply { setPadding(dp(8), dp(16), 0, dp(16)) })
        repeat(3) {
            content.addView(View(this).apply {
                contentDescription="加载骨架屏"
                background=rounded(Color.rgb(229,229,234),18)
                alpha=.6f
                UiMotion.enter(this)
            },LinearLayout.LayoutParams(-1,dp(86)).apply { bottomMargin=dp(12) })
        }
        content.postDelayed({
            if(homeContent!==content || page!=Page.HOME || tab!=selected) return@postDelayed
            listRefreshing=false
            if(page==Page.HOME && tab==selected && homeContent===content) {
                lastSnapshot=""; refresh(); toast("列表已更新")
            }
        },180)
    }
    private fun modernPageIndex() = if (page == Page.SETTINGS) 3 else when (tab) {
        Tab.FEATURES -> 0; Tab.CHATS -> 1; Tab.DEVICES -> 2
    }
    private fun selectModernPage(index: Int) {
        val previous = modernPageIndex()
        if (index == previous || index !in 0..3 || pageTransitionRunning) return
        val frame = modernFrame ?: return
        val oldScroll = modernScroll ?: return
        hideKeyboard()
        listRefreshing = false
        featureInput = null
        page = if (index == 3) Page.SETTINGS else Page.HOME
        if (index != 3) tab = arrayOf(Tab.FEATURES, Tab.CHATS, Tab.DEVICES)[index]
        lastSnapshot = ""
        val newScroll = createModernScroll()
        modernScroll = newScroll
        settingsContent = if (index == 3) homeContent else null
        frame.addView(newScroll, 1, FrameLayout.LayoutParams(-1, -1))
        refresh()
        segmentItems.forEachIndexed { i, item -> styleNavItem(item, i == index) }
        val direction = if (index > previous) 1 else -1
        val distance = frame.width.toFloat()
        val enabled = ValueAnimator.areAnimatorsEnabled() && distance > 0
        val curve = PathInterpolator(.22f, 1f, .36f, 1f)
        val thumb = segmentThumb ?: return
        val target = index * ((segmentTrack?.width ?: 0) - dp(12)) / 4f
        thumb.animate().cancel()
        if (!enabled) {
            thumb.translationX = target
            frame.removeView(oldScroll)
            return
        }
        pageTransitionRunning = true
        newScroll.translationX = direction * distance
        thumb.animate().translationX(target).setDuration(380).setInterpolator(curve).start()
        oldScroll.animate().translationX(-direction * distance).setDuration(380).setInterpolator(curve).start()
        newScroll.animate().translationX(0f).setDuration(380).setInterpolator(curve).withEndAction {
            frame.removeView(oldScroll)
            pageTransitionRunning = false
        }.start()
    }
    private inner class PagerScroll : ScrollView(this@MainActivity) {
        private var downX = 0f; private var downY = 0f
        private var pulling = false; private var edgeGesture = false
        private var startedAtTop = false
        private var pullDistance = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val pullPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private fun beginGesture(event: MotionEvent) {
            downX = event.x; downY = event.y; pulling = false; pullDistance = 0f
            startedAtTop = scrollY == 0
            edgeGesture = event.x < dp(24) || event.x > width - dp(24) || event.y > height - dp(24)
        }
        private fun shouldStartPull(event: MotionEvent): Boolean =
            !edgeGesture && startedAtTop && scrollY == 0 && !listRefreshing &&
                page == Page.HOME && tab != Tab.FEATURES && event.y - downY > slop * 2 &&
                event.y - downY > kotlin.math.abs(event.x - downX) * 1.5f
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && shouldStartPull(event)) {
                pulling = true
                return true
            }
            return super.onInterceptTouchEvent(event)
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            // Blank/short lists deliver MOVE directly here once ScrollView owns DOWN.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
            if (event.actionMasked == MotionEvent.ACTION_MOVE && !pulling && shouldStartPull(event)) pulling = true
            if (pulling) {
                if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                    pullDistance = (event.y - downY).coerceAtLeast(0f)
                    homeContent?.translationY = (pullDistance * .35f).coerceAtMost(dp(72).toFloat())
                    invalidate()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val refresh = event.actionMasked == MotionEvent.ACTION_UP && event.y - downY > dp(72)
                    pulling = false; pullDistance = 0f
                    homeContent?.animate()?.translationY(0f)?.setDuration(180)?.start()
                    invalidate()
                    if (refresh) refreshList()
                }
                return true
            }
            return super.onTouchEvent(event)
        }
        override fun dispatchDraw(canvas: android.graphics.Canvas) {
            super.dispatchDraw(canvas)
            if (!pulling) return
            val label = if (pullDistance > dp(72)) "松开刷新" else "下拉刷新"
            val center = width / 2f
            val top = scrollY + dp(8).toFloat()
            pullPaint.color = Color.WHITE
            canvas.drawRoundRect(center - dp(64), top, center + dp(64), top + dp(32),
                dp(16).toFloat(), dp(16).toFloat(), pullPaint)
            pullPaint.color = blue; pullPaint.textSize = dp(13).toFloat()
            pullPaint.textAlign = android.graphics.Paint.Align.CENTER
            canvas.drawText(label, center, top + dp(16) - (pullPaint.ascent() + pullPaint.descent()) / 2, pullPaint)
        }
    }
    private fun createModernScroll(): ScrollView = PagerScroll().apply {
        isFillViewport = true; clipToPadding = false; setBackgroundColor(surface)
        homeContent = vertical().apply {
            clipChildren = false
            setPadding(if (page == Page.SETTINGS) 0 else dp(20), dp(10),
                if (page == Page.SETTINGS) 0 else dp(20), dp(130))
        }
        addView(homeContent)
    }
    private var backCallback: Any? = null
    private var statusInset = 0
    private var statusSpacer: View? = null
    private var chatBottomSpacer: View? = null
    private var inputSafeLeft = 0
    private var inputSafeBottom = 0
    private var pinChatOnIme = false
    private var homeContent: LinearLayout? = null
    private var contactContent: LinearLayout? = null
    private var historyContent: LinearLayout? = null
    private var conversationPopup: PopupWindow? = null
    private var settingsContent: LinearLayout? = null
    private var chatList: ListView? = null
    private var chatAdapter: MessageAdapter? = null
    private var chatName: TextView? = null
    private var chatPresence: View? = null
    private data class PeerAppearance(val name: String, val color: Int, val online: Boolean)
    private var chatAppearance = PeerAppearance("?", Color.rgb(88, 86, 214), false)
    private var selfAppearance = PeerAppearance("我", Color.rgb(52, 199, 89), true)
    private fun peerAppearance(id: String, name: String, online: Boolean): PeerAppearance {
        val palette = intArrayOf(Color.rgb(88, 86, 214), Color.rgb(52, 199, 89), Color.rgb(255, 149, 0), blue)
        val key = "avatar_color_$id"
        val color = if (uiPrefs.contains(key)) uiPrefs.getInt(key, palette[0]) else {
            val next = uiPrefs.getInt("avatar_color_next", 0)
            palette[next % palette.size].also {
                uiPrefs.edit().putInt(key, it).putInt("avatar_color_next", next + 1).apply()
            }
        }
        return PeerAppearance(name, color, online)
    }
    private fun presenceShape(online: Boolean) = rounded(
        if (online) Color.rgb(52, 199, 89) else Color.rgb(255, 59, 48), 50).apply {
            setStroke(dp(2), Color.WHITE)
        }
    private fun presenceDot(online: Boolean) = View(this).apply {
        background = presenceShape(online)
        contentDescription = if (online) "在线" else "离线"
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private fun peerAvatar(appearance: PeerAppearance, size: Int, radius: Int): FrameLayout = FrameLayout(this).apply {
        addView(text(appearance.name.firstOrNull()?.uppercase() ?: "?", if (size == 54) 22f else 17f,
            Color.WHITE, true).apply {
            gravity = Gravity.CENTER; background = rounded(appearance.color, radius)
        }, FrameLayout.LayoutParams(-1, -1))
        addView(presenceDot(appearance.online), FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END))
        contentDescription = "${appearance.name}，${if (appearance.online) "在线" else "离线"}"
    }
    private var chatInput: EditText? = null
    private var chatAction: TextView? = null
    private var chatPlus: ImageView? = null
    private var chatEmoji: ImageView? = null
    private var composerDrawer: LinearLayout? = null
    private var composerPanel = ComposerPanel.NONE
    private val drafts = HashMap<String, String>()
    private var pendingShareMode = ""
    private var pendingShareFolder = false
    private var pendingPeerId = ""
    private var pendingReceive: ReceiveOffer? = null
    private var shareSheet: Dialog? = null
    private var sheetMode = ""
    private var sheetValue: TextView? = null
    private var sheetStatus: TextView? = null
    private var sheetStop: TextView? = null
    private var sheetCopy: TextView? = null
    private var sheetFiles: TextView? = null
    private var sheetDevices: TextView? = null
    private var sheetQr: ImageView? = null
    private var sheetStats: TextView? = null
    private var sheetRipple: ShareCodeRippleView? = null
    private var sheetQrUrl = ""
    private var sheetToken = ""
    private var sharePopover: PopupWindow? = null
    private var popoverContent: LinearLayout? = null
    private var popoverKind = ""
    private var popoverSignature = ""
    private val sheetHandler = Handler(Looper.getMainLooper())
    private val sheetTick = object : Runnable {
        override fun run() {
            if (shareSheet?.isShowing != true) return
            updateShareSheet(engine.state(peerId))
            sheetHandler.postDelayed(this, 1000)
        }
    }
    private var latestCodeToken = ""
    private var latestDirectToken = ""
    private val requestSource = 4101
    private val requestImage = 4102
    private val requestDestination = 4103
    private val requestDefaultDirectory = 4104
    private var receiveSheet: Dialog? = null
    private var receiveName: TextView? = null
    private var receiveStatus: TextView? = null
    private var receiveAmount: TextView? = null
    private var receivePath: TextView? = null
    private var receiveProgress: ProgressBar? = null
    private var receiveAction: TextView? = null
    private var receiveStop: TextView? = null
    private var receiveStoppingText = ""
    private var receiveThreadCount = 8
    private var receiveRetry: (() -> Unit)? = null
    private var receiveStopping = false
    private var receiveDestinationLabel = ""
    private var receiveTaskId = ""
    private var receiveEpoch = 0L
    private val receiveHandler = Handler(Looper.getMainLooper())
    private val receiveTick = object : Runnable {
        override fun run() {
            if(receiveSheet?.isShowing != true) return
            updateReceiveSheet(engine.state(peerId))
            receiveHandler.postDelayed(this,250)
        }
    }
    private fun configuredDownloadTree(): Uri? {
        val stored=uiPrefs.getString("default_download_tree", null) ?: return null
        val tree=Uri.parse(stored)
        return tree.takeIf { contentResolver.persistedUriPermissions.any { grant -> grant.uri==tree && grant.isWritePermission } }
    }
    private fun chooseDownloadDirectory() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            configuredDownloadTree()?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI,it) }
        },requestDefaultDirectory)
    }
    private fun showReceiveSheet() {
        if(receiveSheet?.isShowing==true) return
        val card=vertical().apply { background=rounded(Color.WHITE,24); setPadding(dp(24),dp(16),dp(24),dp(24)) }
        card.addView(View(this).apply { background=rounded(border,50) },LinearLayout.LayoutParams(dp(38),dp(4)).apply {
            gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(20)
        })
        card.addView(text("接收文件",22f,ink,true))
        receiveName=text("正在查找分享…",16f,ink,true).apply { setPadding(0,dp(18),0,dp(12)) }
        card.addView(receiveName)
        receiveProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=1000; isIndeterminate=true; progressTintList=ColorStateList.valueOf(blue)
            indeterminateTintList=ColorStateList.valueOf(blue)
        }
        card.addView(receiveProgress,LinearLayout.LayoutParams(-1,dp(8)))
        receiveStatus=text("等待接收",14f,muted).apply { setPadding(0,dp(12),0,dp(6)) }; card.addView(receiveStatus)
        receiveAmount=text("",13f,muted); card.addView(receiveAmount)
        receivePath=text(receiveDestinationLabel,12f,muted).apply { setPadding(0,dp(12),0,dp(18)) }; card.addView(receivePath)
        val dialog=Dialog(this)
        val actions = horizontal(Gravity.CENTER_VERTICAL)
        receiveAction = modernButton("暂停接收", false) {
            if (engine.isReceiving(receiveTaskId)) {
                receiveStopping = true; receiveStoppingText = "正在暂停接收…"
                receiveAction?.isEnabled = false; receiveStop?.isEnabled = false
                receiveStatus?.text = receiveStoppingText
                engine.pauseReceive(receiveTaskId)
            } else receiveRetry?.invoke()
        }.apply { visibility = View.GONE }
        receiveStop = modernButton("停止并清理", false) {
            AlertDialog.Builder(this).setTitle("停止接收").setMessage("停止后删除本任务临时文件并清除续传进度，已完成的下载文件不受影响。")
                .setNegativeButton("返回", null).setPositiveButton("停止并清理") { _, _ ->
                    receiveStopping = true; receiveStoppingText = "正在停止并清理…"
                    receiveAction?.isEnabled = false; receiveStop?.isEnabled = false
                    receiveStatus?.text = receiveStoppingText
                    engine.stopReceive(receiveTaskId)
                }.show()
        }.apply { visibility = View.GONE }
        actions.addView(receiveAction, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(10) })
        actions.addView(receiveStop, LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(actions)
        card.addView(modernButton("收起", false) { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        dialog.setContentView(card); dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            receiveHandler.removeCallbacks(receiveTick)
            if(receiveSheet===dialog) receiveSheet=null
        }
        receiveSheet=dialog; UiMotion.apply(card); dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM); setLayout(resources.displayMetrics.widthPixels-dp(24),-2)
            setWindowAnimations(R.style.BottomSheetAnimation)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes=attributes.apply { dimAmount=.35f }
        }
        receiveHandler.post(receiveTick)
    }
    private fun updateReceiveSheet(state: JSONObject) {
        if(receiveSheet?.isShowing!=true || receiveTaskId.isEmpty()) return
        val array=state.optJSONArray("transfers") ?: return
        val task=(0 until array.length()).mapNotNull { array.optJSONObject(it) }.lastOrNull {
            it.optString("id")==receiveTaskId && it.optString("receiver_ip").isEmpty() && it.optLong("updated_at")>=receiveEpoch
        } ?: return
        val done=task.optLong("transferred"); val total=task.optLong("total")
        val status=task.optString("status")
        receiveName?.text = task.optString("name")
        val terminal = status in setOf("completed", "error", "paused", "cancelled", "cleanup_error")
        if (terminal) receiveStopping = false
        receiveAction?.apply {
            visibility = if (status !in setOf("completed", "cancelled", "cleanup_error") && receiveRetry != null) View.VISIBLE else View.GONE
            text = if (status in setOf("error", "paused")) "继续接收" else "暂停接收"
            isEnabled = !receiveStopping && (engine.isReceiving(receiveTaskId) || terminal)
        }
        receiveStop?.apply {
            visibility = if (status !in setOf("completed", "cancelled")) View.VISIBLE else View.GONE
            isEnabled = !receiveStopping
        }
        if (status == "cancelled") receiveRetry = null
        receiveProgress?.isIndeterminate=status in setOf("connecting","preparing") || (total==0L && !terminal)
        receiveProgress?.progress=if(status=="completed") 1000 else if(total>0) ((done.toDouble()/total*1000).toInt().coerceIn(0,1000)) else 0
        receiveStatus?.text=if (receiveStopping) receiveStoppingText else when(status) {
            "connecting" -> "正在连接发送设备…"
            "transferring" -> "正在下载 · 单连接"
            "parallel" -> "正在分段下载 · 最多 $receiveThreadCount 线程"
            "saving" -> "正在保存到下载目录…"
            "completed" -> "接收完成"
            "paused" -> "接收已暂停，临时文件已保留，可继续接收"
            "cancelled" -> "接收已停止，临时文件已清理"
            "cleanup_error" -> task.optString("error", "临时文件清理失败，请重试停止并清理")
            "error" -> task.optString("error","接收失败")
            else -> "正在准备接收…"
        }
        receiveStatus?.setTextColor(if(status=="error") Color.rgb(255,59,48) else if(status=="completed") Color.rgb(52,199,89) else muted)
        receiveAmount?.text="${formatBytes(done)} / ${formatBytes(total)}" +
            if(status in setOf("transferring", "parallel")) " · ${formatBytes(task.optLong("bytes_per_second"))}/s" else ""
    }
    private fun beginReceive(offer: ReceiveOffer, destination: Uri, destinationIsTree: Boolean) {
        if (engine.isReceiving(receiveTaskId)) { showReceiveSheet(); return }
        showReceiveSheet()
        receiveRetry = { beginReceive(offer, destination, destinationIsTree) }
        receiveStopping = false
        receiveThreadCount = uiPrefs.getInt("download_threads", 8)
        receiveTaskId=offer.messageId.ifEmpty { offer.token }; receiveEpoch=System.currentTimeMillis()
        receiveName?.text=offer.name
        receiveStatus?.text="正在连接发送设备…"
        receiveProgress?.isIndeterminate=true
        receiveDestinationLabel = if(destinationIsTree && destination == configuredDownloadTree())
            "保存到：${uiPrefs.getString("default_download_name","默认下载目录")}" else "保存到：本次选择的位置"
        receivePath?.text = receiveDestinationLabel
        receiveAmount?.text = ""
        receiveAction?.apply { text = "暂停接收"; isEnabled = true; visibility = View.VISIBLE }
        receiveStop?.apply { isEnabled = true; visibility = View.VISIBLE }
        engine.receive(offer,destination,destinationIsTree)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tab = Tab.FEATURES
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = headerSurface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        root = vertical().apply { setBackgroundColor(surface); isFocusableInTouchMode = true }
        setContentView(root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
            if (top != statusInset) {
                statusInset = top
                statusSpacer?.let { spacer ->
                    spacer.layoutParams.height = top
                    spacer.requestLayout()
                }
            }
            val cutoutLeft = when {
                Build.VERSION.SDK_INT >= 30 -> insets.getInsets(WindowInsets.Type.displayCutout()).left
                Build.VERSION.SDK_INT >= 28 -> insets.displayCutout?.safeInsetLeft ?: 0
                else -> 0
            }
            val bottomCorner = if (Build.VERSION.SDK_INT >= 31)
                insets.getRoundedCorner(android.view.RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
            else 0
            val cornerAllowance = minOf(dp(8), bottomCorner / 3)
            val left = maxOf(dp(6), cutoutLeft, cornerAllowance)
            val bottom = maxOf(dp(6), cornerAllowance)
            if (inputSafeLeft != left || inputSafeBottom != bottom) {
                inputSafeLeft = left
                inputSafeBottom = bottom
                applyInputSafeArea()
            }
            if (Build.VERSION.SDK_INT >= 30) {
                val bottom = if (insets.isVisible(WindowInsets.Type.ime()))
                    insets.getInsets(WindowInsets.Type.ime()).bottom
                else insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                applyBottomInset(bottom)
            }
            insets
        }
        if (Build.VERSION.SDK_INT < 30) root.viewTreeObserver.addOnGlobalLayoutListener {
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val hidden = (resources.displayMetrics.heightPixels - visible.bottom).coerceAtLeast(0)
            val navigation = root.rootWindowInsets?.stableInsetBottom ?: 0
            applyBottomInset(maxOf(hidden, navigation))
        }
        engine = LanRuntime.attach(this, this) { runOnUiThread { if (!isDestroyed) refresh() } }
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { handleBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(0, callback)
        }
        showPage(Page.HOME)
        try { engine.start() } catch (error: Exception) { toast("启动局域网服务失败：${error.message}") }
        if (uiPrefs.getBoolean("keep_alive", false)) startKeepAlive()
    }

    override fun onDestroy() {
        conversationPopup?.dismiss()
        DrawerMotion.finish()
        if (Build.VERSION.SDK_INT >= 33) (backCallback as? OnBackInvokedCallback)?.let {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
        }
        shareSheet?.dismiss(); receiveSheet?.dismiss()
        receiveHandler.removeCallbacks(receiveTick)
        LanRuntime.detach(this)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if(requestCode==requestDestination) { pendingReceive=null; receiveStatus?.text="已取消选择保存位置"; receiveProgress?.isIndeterminate=false }
            return
        }
        val uri = data?.data ?: return
        if(requestCode==requestDefaultDirectory) {
            try {
                val flags=data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) { "目录未授予写入权限" }
                contentResolver.takePersistableUriPermission(uri,flags)
                val document=DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
                val name=contentResolver.query(document,arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {
                    if(it.moveToFirst()) it.getString(0) else "下载目录"
                } ?: "下载目录"
                uiPrefs.edit().putString("default_download_tree",uri.toString()).putString("default_download_name",name).apply()
                lastSnapshot=""; refresh()
            } catch(error:Exception) {
                AlertDialog.Builder(this).setTitle("目录设置失败").setMessage(error.message).setPositiveButton("确定",null).show()
            }
            return
        }
        if (requestCode == requestDestination) {
            val offer = pendingReceive ?: return
            pendingReceive = null
            try { contentResolver.takePersistableUriPermission(uri,
                data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) } catch (_: SecurityException) { }
            beginReceive(offer, uri, offer.folder)
            return
        }
        if (requestCode !in setOf(requestSource, requestImage)) return
        try {
            if (requestCode == requestSource) contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) { /* Some providers grant only for this selection. */ }
        if (requestCode == requestImage) {
            val preview = ZoomImageView(this).apply {
                setImageURI(uri)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(-1, dp(280))
            }
            val previewBox=vertical().apply {
                addView(preview)
                val zoomActions=horizontal(Gravity.CENTER)
                listOf("缩小" to .8f,"放大" to 1.25f,"复位" to 0f).forEach { (label,factor) ->
                    zoomActions.addView(text(label,14f,blue,true).apply {
                        gravity=Gravity.CENTER; setPadding(dp(18),dp(12),dp(18),dp(12))
                        setOnClickListener { if(factor==0f) preview.resetZoom() else preview.zoomBy(factor) }
                    })
                }
                addView(zoomActions)
            }
            AlertDialog.Builder(this).setTitle("发送图片").setView(previewBox)
                .setNegativeButton("取消", null)
                .setPositiveButton("发送") { _, _ -> sendSelected(uri, false, "chat", pendingPeerId) }
                .show()
        } else sendSelected(uri, pendingShareFolder, pendingShareMode, pendingPeerId)
    }

    private fun selectSource(mode: String, folder: Boolean = false, image: Boolean = false, peer: String = "") {
        pendingShareMode = mode
        pendingShareFolder = folder
        pendingPeerId = peer
        val intent = when {
            image && Build.VERSION.SDK_INT >= 33 -> Intent(MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
            image -> Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
            folder -> Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            else -> Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        startActivityForResult(intent, if (image) requestImage else requestSource)
    }

    private fun sendSelected(uri: Uri, folder: Boolean, mode: String, peer: String) {
        when (mode) {
            "code" -> engine.startCodeShare(uri, folder) { code, error -> runOnUiThread {
                if (error != null) toast(error) else { refresh(); showShareSheet("code") }
            } }
            "direct" -> engine.startDirectShare(uri) { url, error -> runOnUiThread {
                if (error != null) toast(error) else { refresh(); showShareSheet("direct") }
            } }
            "chat" -> engine.sendAttachment(peer, uri, folder) { error -> runOnUiThread {
                if (error != null) toast(error) else setComposerPanel(ComposerPanel.NONE)
            } }
        }
    }

    private fun selectDestination(offer: ReceiveOffer) {
        if (engine.isReceiving(receiveTaskId)) { showReceiveSheet(); return }
        showReceiveSheet(); receiveName?.text=offer.name
        val tree=configuredDownloadTree()
        if(tree!=null) { beginReceive(offer,tree,true); return }
        receiveTaskId=""
        receiveProgress?.isIndeterminate=false
        receiveStatus?.text=if(uiPrefs.contains("default_download_tree")) "默认目录授权失效，请选择保存位置" else "请选择保存位置，或在设置中指定默认下载目录"
        pendingReceive = offer
        val intent = if (offer.folder) Intent(Intent.ACTION_OPEN_DOCUMENT_TREE) else
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/octet-stream"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, offer.name)
            }
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        startActivityForResult(intent, requestDestination)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() = handleBack()

    private fun handleBack() {
        if (Build.VERSION.SDK_INT >= 30 && root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true) {
            hideKeyboard()
            return
        }
        if (composerPanel != ComposerPanel.NONE) { setComposerPanel(ComposerPanel.NONE); return }
        if (conversationPopup?.isShowing == true) { conversationPopup?.dismiss(); return }
        if (page == Page.HISTORY) { showPage(Page.CONTACT, peerId); return }
        if (page == Page.CONTACT) { showPage(Page.CHAT, peerId); return }
        if (page != Page.HOME) { showPage(Page.HOME); return }
        val now = System.currentTimeMillis()
        if (now - lastBackTap < 2000) finish() else {
            lastBackTap = now
            toast("再按一次返回退出 ZTDrop")
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        chatInput?.clearFocus()
        root.requestFocus()
    }

    private fun applyBottomInset(bottom: Int) {
        if (root.paddingBottom == bottom) return
        val shouldPin = page == Page.CHAT && (pinChatOnIme ||
            (chatList?.let { it.count == 0 || it.lastVisiblePosition >= it.count - 2 } == true))
        root.setPadding(0, 0, 0, bottom)
        val list = chatList
        if (shouldPin && list != null) list.post {
            if (page == Page.CHAT && list === chatList && list.count > 0)
                list.setSelection(list.count - 1)
        }
    }

    private fun applyInputSafeArea() {
        chatInput?.let { input ->
            val params = input.layoutParams as? LinearLayout.LayoutParams ?: return@let
            if (params.marginStart != inputSafeLeft) {
                params.marginStart = inputSafeLeft
                input.layoutParams = params
            }
        }
        chatBottomSpacer?.let { spacer ->
            val height = maxOf(dp(8), inputSafeBottom)
            if (spacer.layoutParams.height != height) {
                spacer.layoutParams.height = height
                spacer.requestLayout()
            }
        }
    }

    private fun showPage(next: Page, id: String = "") {
        DrawerMotion.finish()
        val previousPage = page
        fun depth(value: Page) = when(value) { Page.HOME,Page.SETTINGS -> 0; Page.CHAT -> 1; Page.CONTACT -> 2; Page.HISTORY -> 3 }
        val drawerBitmap = if(hasRenderedPage && next != previousPage &&
            (depth(next)>0 || depth(previousPage)>0)) DrawerMotion.snapshot(root) else null
        if (page == Page.CHAT && peerId.isNotEmpty()) drafts[peerId] = chatInput?.text?.toString().orEmpty()
        conversationPopup?.dismiss()
        conversationPopup = null
        shareSheet?.dismiss()
        shareSheet = null
        hideKeyboard()
        page = next
        peerId = if (next in setOf(Page.CHAT, Page.CONTACT, Page.HISTORY)) id else ""
        lastSnapshot = ""
        homeContent = null
        contactContent = null
        historyContent = null
        pinChatOnIme = false
        chatBottomSpacer = null
        settingsContent = null
        featureInput = null
        chatList = null
        chatAdapter = null
        chatName = null; chatPresence = null
        chatInput = null
        chatAction = null
        chatPlus = null
        chatEmoji = null
        composerDrawer = null
        composerPanel = ComposerPanel.NONE
        modernFrame = null; modernScroll = null; segmentThumb = null; segmentTrack = null
        segmentItems.clear(); pageTransitionRunning = false; listRefreshing = false
        root.removeAllViews()
        root.setBackgroundColor(surface)
        @Suppress("DEPRECATION")
        window.navigationBarColor = surface
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        statusSpacer = View(this).apply {
            setBackgroundColor(if (next in setOf(Page.HOME, Page.SETTINGS)) surface else headerSurface)
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, statusInset)) }
        when (next) {
            Page.HOME -> makeHome()
            Page.CHAT -> makeChat()
            Page.CONTACT -> makeDetailPage("设备名片", Page.CHAT).also { contactContent = it }
            Page.HISTORY -> makeDetailPage("聊天记录", Page.CONTACT).also { historyContent = it }
            Page.SETTINGS -> {
                makeHome()
                settingsContent = homeContent
            }
        }
        applyInputSafeArea()
        root.requestApplyInsets()
        refresh()
        UiMotion.apply(root)
        if (drawerBitmap != null) DrawerMotion.transition(root, drawerBitmap, depth(next)<depth(previousPage))
        else if (hasRenderedPage) UiMotion.enter(root)
        hasRenderedPage = true
    }

    private fun refresh() {
        if (!::engine.isInitialized || listRefreshing) return
        if (conversationPopup?.isShowing == true) return
        if (page == Page.HOME && tab == Tab.FEATURES && featureInput != null) {
            updateFeatureProgress(engine.state(peerId))
            return
        }
        try {
            val snapshot = engine.state(peerId)
            snapshot.put("background_service_running", LanRuntime.isServiceRunning())
                .put("background_service_enabled", uiPrefs.getBoolean("keep_alive", false))
                .put("background_service_error", uiPrefs.getString("keep_alive_error", ""))
            val encoded = snapshot.toString()
            if (encoded == lastSnapshot && !(page == Page.HOME && tab == Tab.CHATS)) return
            lastSnapshot = encoded
            when (page) {
                Page.HOME -> renderHome(snapshot)
                Page.CHAT -> renderChat(snapshot)
                Page.CONTACT -> renderContact(snapshot)
                Page.HISTORY -> renderHistory(snapshot)
                Page.SETTINGS -> renderSettings(snapshot)
            }
            UiMotion.apply(root)
        } catch (error: Exception) { toast(error.message ?: "界面刷新失败") }
    }


    private fun makeModernHome() {
        val frame = FrameLayout(this).apply { clipChildren = true }
        modernFrame = frame
        val scroll = createModernScroll()
        modernScroll = scroll
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        val track = FrameLayout(this).apply {
            contentDescription = "简洁四栏导航"
            background = rounded(Color.WHITE, 24, border)
            elevation = dp(3).toFloat()
        }
        segmentTrack = track
        val thumb = View(this).apply {
            background = rounded(surface, 18)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        segmentThumb = thumb
        track.addView(thumb, FrameLayout.LayoutParams(0, dp(58)).apply { leftMargin = dp(6); topMargin = dp(6) })
        val nav = horizontal(Gravity.CENTER_VERTICAL).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
        listOf(R.drawable.ic_modern_transfer to "传输", R.drawable.ic_modern_messages to "消息",
            R.drawable.ic_modern_devices to "设备", R.drawable.ic_modern_profile to "我的").forEachIndexed { index, (icon, title) ->
            val item = modernNavItem(icon, title, modernPageIndex() == index) { selectModernPage(index) }
            segmentItems.add(item)
            nav.addView(item, LinearLayout.LayoutParams(0, dp(58), 1f))
        }
        track.addView(nav, FrameLayout.LayoutParams(-1, -2))
        track.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                val width = (right - left - dp(12)) / 4
                thumb.layoutParams = (thumb.layoutParams as FrameLayout.LayoutParams).apply { this.width = width }
                thumb.translationX = modernPageIndex() * width.toFloat()
            }
        }
        frame.addView(track, FrameLayout.LayoutParams(-1, dp(70), Gravity.BOTTOM).apply {
            marginStart = dp(22); marginEnd = dp(22); bottomMargin = dp(18)
        })
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun styleNavLabel(label: TextView, selected: Boolean) {
        label.setTextColor(if (selected) Color.BLACK else muted)
        label.typeface = Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
    }

    private fun styleNavItem(item: LinearLayout, selected: Boolean) {
        item.isSelected = selected
        (item.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(if (selected) blue else muted)
        styleNavLabel(item.getChildAt(1) as TextView, selected)
    }

    private fun modernNavItem(icon: Int, label: String, selected: Boolean, click: () -> Unit) = vertical().apply {
        gravity = Gravity.CENTER
        background = null
        tag = "segmented-item"
        addView(ImageView(this@MainActivity).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(muted)
        }, LinearLayout.LayoutParams(dp(24), dp(24)))
        addView(text(label, 11f, muted, false).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0)
        })
        contentDescription = label
        styleNavItem(this, selected)
        setOnClickListener { click() }
    }

    private fun modernTitle(content: LinearLayout, title: String, subtitle: String) {
        content.addView(text(title, 32f, ink, true).apply { setPadding(0, dp(10), 0, dp(8)) })
        content.addView(text(subtitle, 15f, muted).apply { setPadding(0, 0, 0, dp(24)) })
    }

    private fun modernReceiveBand() = horizontal(Gravity.CENTER_VERTICAL).apply {
        val code = EditText(this@MainActivity).apply {
            contentDescription = "接收分享码输入"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
            setSingleLine(true)
            hint = "输入接收分享码…"
            textSize = 16f
            setTextColor(ink); setHintTextColor(muted)
            background = rounded(Color.WHITE, 14, border)
            setPadding(dp(16), 0, dp(12), 0)
            setText(featureDrafts[0]); setSelection(text.length)
        }
        featureInput = code
        code.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                featureDrafts[0] = s?.toString().orEmpty()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        addView(code, LinearLayout.LayoutParams(0, dp(52), 1f))
        addView(modernButton("接收", true) { submitShareCode(code.text.toString()) },
            LinearLayout.LayoutParams(dp(86), dp(52)).apply { marginStart = dp(12) })
    }

    private fun modernButton(label: String, primary: Boolean, click: () -> Unit) =
        text(label, 16f, if (primary) Color.WHITE else blue, true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (primary) blue else paleBlue, 14)
            minimumHeight = dp(48)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            contentDescription = label
            setOnClickListener { click() }
        }

    private fun renderModernHome(state: JSONObject) {
        val content = homeContent ?: return
        // Keep the EditText and selection stable while engine progress updates.
        if (tab == Tab.FEATURES && featureInput != null) { updateFeatureProgress(state); return }
        val scroll = content.parent as ScrollView
        val y = scroll.scrollY
        content.removeAllViews()
        val friends = state.optJSONArray("friends") ?: JSONArray()
        when (tab) {
            Tab.FEATURES -> {
                modernTitle(content, "传输", "局域网快速分享文件")
                content.addView(modernReceiveBand(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(24) })
                content.addView(modernShareCard("码连分享", "输入4位分享码，快速配对", "code"), modernMargin())
                content.addView(modernShareCard("直链分享", "生成下载链接和二维码", "direct"), modernMargin())
                updateFeatureProgress(state)
            }
            Tab.CHATS -> {
                modernTitle(content, "消息", "局域网通讯记录")
                content.addView(text("刷新列表",12f,blue).apply {
                    gravity=Gravity.END; setPadding(0,0,dp(4),dp(10)); setOnClickListener { refreshList() }
                })
                val sessions = (0 until friends.length()).mapNotNull { friends.optJSONObject(it) }
                    .filter { it.optString("status") == "accepted" }.map { friend ->
                        val messages = engine.state(friend.optString("device_id")).optJSONArray("messages") ?: JSONArray()
                        friend to messages.optJSONObject(messages.length() - 1)
                    }.sortedWith(compareByDescending<Pair<JSONObject, JSONObject?>> { it.first.optBoolean("pinned") }
                        .thenByDescending { it.second?.optLong("created_at") ?: 0L })
                sessions.forEach { (friend, last) ->
                    val preview = if (last == null) "暂无消息" else if (last.optString("kind") == "text") last.optString("content") else "文件"
                    val time = if (last == null) "" else SimpleDateFormat("HH:mm", Locale.CHINA)
                        .format(Date(last.optLong("created_at") * 1000L))
                    val id = friend.optString("device_id")
                    val item=modernListRow(displayName(friend), preview, time, friend.optBoolean("pinned"), peerAppearance(id, displayName(friend), friend.optBoolean("online")),
                        { showPage(Page.CHAT, id) }, { showConversationMenu(it, id, friend.optBoolean("pinned")) })
                    content.addView(item,modernMargin())
                }
                if (sessions.isEmpty()) content.addView(empty("暂无消息，去设备页添加好友"))
            }
            Tab.DEVICES -> {
                content.addView(text("设备", 32f, ink, true).apply { setPadding(0, dp(10), 0, dp(22)) })
                content.addView(text("刷新列表",12f,blue).apply {
                    gravity=Gravity.END; setPadding(0,0,dp(4),dp(10)); setOnClickListener { refreshList() }
                })
                content.addView(text("附近设备", 13f, muted, true).apply { setPadding(dp(4), dp(8), 0, dp(12)) })
                val byId = (0 until friends.length()).mapNotNull { friends.optJSONObject(it) }.associateBy { it.optString("device_id") }
                val peers = state.optJSONArray("peers") ?: JSONArray()
                val nearby = (0 until peers.length()).mapNotNull { peers.optJSONObject(it) }
                val offline = byId.values.filter { friend -> friend.optString("status") in setOf("pending_in", "pending_out") &&
                    nearby.none { it.optString("node_id") == friend.optString("device_id") } }
                    .map { JSONObject().put("node_id", it.optString("device_id")).put("device_name", it.optString("device_name")).put("ip", "") }
                (nearby + offline).forEachIndexed { index, peer ->
                    val id = peer.optString("node_id")
                    val friend = byId[id]
                    val status = friend?.optString("status")
                    val ip = peer.optString("ip")
                    val name = if (friend == null) peer.optString("device_name", "局域网设备") else displayName(friend)
                    val detail = when (status) {
                        "pending_in" -> "对方请求加好友"
                        "pending_out" -> "等待确认"
                        else -> if (ip.isBlank()) "离线" else "在线 · $ip"
                    }
                    content.addView(modernDeviceRow(name, detail, ip.isNotBlank(), index,
                        when (status) {
                            "accepted" -> "会话"
                            "pending_out" -> "等待"
                            else -> "连接"
                        },
                        { anchor -> if (status == "accepted") showPage(Page.CHAT, id) else showDeviceMenu(anchor, id, status) }), modernMargin())
                }
                if (nearby.isEmpty() && offline.isEmpty()) content.addView(empty("暂无附近设备"))
            }
        }
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun modernMargin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }

    private fun modernDeviceRow(name: String, detail: String, online: Boolean, colorIndex: Int,
        button: String, connect: (View) -> Unit) =
        horizontal(Gravity.CENTER_VERTICAL).apply {
            contentDescription = "设备列表项：$name"
            background = rounded(Color.WHITE, 18)
            setPadding(dp(20), dp(18), dp(20), dp(18))
            val colors = intArrayOf(blue, Color.rgb(88, 86, 214), Color.rgb(255, 149, 0))
            addView(text(name.firstOrNull()?.uppercase() ?: "?", 20f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER; background = rounded(colors[colorIndex % colors.size], 15)
            }, LinearLayout.LayoutParams(dp(50), dp(50)))
            val info = vertical().apply { setPadding(dp(15), 0, dp(8), 0) }
            info.addView(text(name, 17f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            val status = horizontal(Gravity.CENTER_VERTICAL).apply { setPadding(0, dp(3), 0, 0) }
            status.addView(View(this@MainActivity).apply {
                contentDescription = "在线状态"
                background = rounded(if (online) Color.rgb(52, 199, 89) else Color.rgb(199, 199, 204), 4)
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
            status.addView(text(detail, 13f, muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            info.addView(status)
            addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            val control = text(button, 15f, blue, true).apply {
                gravity = Gravity.CENTER; background = rounded(paleBlue, 12)
                setPadding(dp(22), dp(10), dp(22), dp(10))
                minimumHeight = dp(48)
                setOnClickListener { connect(this) }
            }
            addView(control, LinearLayout.LayoutParams(-2, -2))
        }

    private fun modernListRow(name: String, preview: String, time: String, pinned: Boolean, appearance: PeerAppearance,
        click: () -> Unit, longPress: (View) -> Unit) =
        horizontal(Gravity.CENTER_VERTICAL).apply {
            contentDescription = "设备列表项：$name"
            background = rounded(Color.WHITE, 18).apply {
                if (pinned) setStroke(dp(1),Color.rgb(205,208,214))
            }
            elevation=if(pinned) dp(3).toFloat() else 0f
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(peerAvatar(appearance, 54, 18), LinearLayout.LayoutParams(dp(54), dp(54)))
            val body = vertical().apply { setPadding(dp(15), 0, dp(8), 0) }
            body.addView(text(name, 17f, ink, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            body.addView(text(preview, 14f, muted).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
            })
            addView(body, LinearLayout.LayoutParams(0, -2, 1f))
            val msgRight = vertical().apply {
                gravity = Gravity.END
                addView(text(time, 12f, Color.rgb(199, 199, 204)))
            }
            addView(msgRight, LinearLayout.LayoutParams(-2, -1).apply { gravity = Gravity.TOP; topMargin = dp(2) })
            setOnClickListener { click() }
            setOnLongClickListener { longPress(this); true }
        }

    private fun modernShareCard(title: String, subtitle: String, mode: String) = vertical().apply {
        background = rounded(Color.WHITE, 22)
        setPadding(dp(24), dp(24), dp(24), dp(24))
        contentDescription = title
        val head = horizontal(Gravity.CENTER_VERTICAL)
        head.addView(text(if (mode == "code") "📡" else "🔗", 22f, ink).apply {
            gravity = Gravity.CENTER
            background = rounded(if (mode == "code") Color.rgb(230, 242, 255) else Color.rgb(235, 249, 238), 16)
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        val labels = vertical().apply { setPadding(dp(14), 0, 0, 0) }
        labels.addView(text(title, 20f, ink, true))
        labels.addView(text(subtitle, 13f, muted).apply { setPadding(0, dp(3), 0, 0) })
        head.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        addView(head)
        val buttons = horizontal(Gravity.CENTER_VERTICAL).apply { setPadding(0, dp(18), 0, 0) }
        buttons.addView(modernButton(if (mode == "code") "发送文件" else "生成直链", true) { selectSource(mode) },
            LinearLayout.LayoutParams(0, dp(50), 1f))
        if (mode == "code") buttons.addView(modernButton("发送文件夹", false) { selectSource(mode, folder = true) },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginStart = dp(12) })
        addView(buttons)
        setOnClickListener { showShareSheet(mode) }
    }

    private fun showShareSheet(mode: String) {
        shareSheet?.dismiss()
        sheetMode = mode
        hideKeyboard()
        val dialog = Dialog(this)
        val card = vertical().apply {
            background = rounded(Color.WHITE, 28)
            setPadding(dp(24), dp(20), dp(24), dp(18))
        }
        card.addView(View(this).apply { background = rounded(Color.rgb(209, 209, 214), 3) },
            LinearLayout.LayoutParams(dp(40), dp(5)).apply { gravity = Gravity.CENTER; bottomMargin = dp(14) })
        val triggers = horizontal(Gravity.CENTER_VERTICAL)
        sheetFiles = shareTrigger("📁 分享内容  0  ﹀") { showSharePopover(it, "files") }
        sheetDevices = shareTrigger("🖥 接收设备  0  ﹀") { showSharePopover(it, "devices") }
        triggers.addView(sheetFiles, LinearLayout.LayoutParams(0, dp(42), 1f))
        triggers.addView(sheetDevices, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(10) })
        card.addView(triggers)
        if (mode == "code") {
            val wrap = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
            sheetRipple = ShareCodeRippleView(this).also { wrap.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            val core = vertical().apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; gradientType = GradientDrawable.RADIAL_GRADIENT
                    colors = intArrayOf(Color.rgb(232, 250, 240), Color.rgb(212, 241, 222), Color.rgb(200, 236, 214))
                    gradientRadius = dp(155).toFloat(); setGradientCenter(.35f, .35f)
                }
                elevation = dp(6).toFloat()
                addView(text("分享码", 11f, Color.rgb(122, 158, 139)).apply { gravity = Gravity.CENTER })
                sheetValue = text("—", 42f, Color.rgb(26, 26, 26), true).apply {
                    gravity = Gravity.CENTER; letterSpacing = .18f; includeFontPadding = false
                    setPadding(dp(8), 0, 0, 0)
                    contentDescription = "分享码"
                }.also { addView(it) }
            }
            wrap.addView(core, FrameLayout.LayoutParams(dp(155), dp(155), Gravity.CENTER))
            card.addView(wrap, LinearLayout.LayoutParams(-1, dp(210)).apply { topMargin = dp(8); bottomMargin = dp(12) })
        } else {
            sheetQr = ImageView(this).apply {
                contentDescription = "直链下载二维码"
                background = rounded(Color.WHITE, 14, border)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }.also { card.addView(it, LinearLayout.LayoutParams(dp(150), dp(150)).apply {
                gravity = Gravity.CENTER; topMargin = dp(12); bottomMargin = dp(10)
            }) }
            card.addView(text("扫描二维码快速下载", 12f, muted).apply { gravity = Gravity.CENTER })
            sheetValue = text("", 10f, blue).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true); setPadding(dp(14), dp(10), dp(14), dp(10))
                background = rounded(paleBlue, 12)
            }.also { card.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(12) }) }
        }
        sheetStatus = text("", 12f, muted).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12))
        }.also { card.addView(it) }
        val buttons = horizontal(Gravity.CENTER_VERTICAL)
        if (mode == "direct") {
            sheetCopy = modernButton("📋 复制链接", false) {
                val value = sheetValue?.text?.toString().orEmpty()
                if (value.isNotEmpty()) {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ZTDrop", value))
                    toast("链接已复制")
                }
            }.also { buttons.addView(it, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(10) }) }
        }
        sheetStop = modernButton("⏹ 停止分享", true) {
            val token = sheetToken
            if (token.isNotEmpty()) runAction { engine.revokeShare(token) }
            dialog.dismiss()
        }.apply { background = rounded(Color.rgb(255, 59, 48), 14) }
        buttons.addView(sheetStop, LinearLayout.LayoutParams(0, dp(50), 1f))
        card.addView(buttons)
        sheetStats = text("", 11f, Color.rgb(174, 174, 178)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
        }.also { card.addView(it) }
        val scroll = ScrollView(this).apply { addView(card) }
        dialog.setContentView(scroll)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener {
            sharePopover?.dismiss()
            sheetHandler.removeCallbacks(sheetTick)
            if (shareSheet === dialog) {
                shareSheet = null; sheetValue = null; sheetStatus = null; sheetRipple = null
                sheetStop = null; sheetCopy = null; sheetFiles = null; sheetDevices = null
                sheetQr = null; sheetStats = null; sheetToken = ""; sheetQrUrl = ""
            }
        }
        shareSheet = dialog
        UiMotion.apply(card)
        dialog.show()
        dialog.window?.apply {
            setWindowAnimations(R.style.BottomSheetAnimation)
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            val maxHeight = (resources.displayMetrics.heightPixels * .85f).toInt()
            scroll.measure(View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels - dp(24), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
            setLayout(resources.displayMetrics.widthPixels - dp(24), minOf(scroll.measuredHeight, maxHeight))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = .35f }
        }
        updateShareSheet(engine.state(peerId))
        sheetHandler.postDelayed(sheetTick, 1000)
    }

    private fun shareTrigger(label: String, click: (View) -> Unit) = text(label, 13f, ink, true).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        background = rounded(Color.rgb(247, 248, 250), 12, border)
        setPadding(dp(6), dp(8), dp(6), dp(8))
        setOnClickListener { click(this) }
    }

    private fun shareReceivers(state: JSONObject, token: String): List<JSONObject> {
        val transfers = state.optJSONArray("transfers") ?: JSONArray()
        return (0 until transfers.length()).mapNotNull { transfers.optJSONObject(it) }
            .filter { it.optString("id") == token && it.optString("receiver_ip").isNotEmpty() }
            .groupBy { it.optString("receiver_ip") }.values.map { requests ->
                requests.filter { it.optString("status") == "sending" }.maxByOrNull { it.optLong("updated_at") }
                    ?: requests.maxByOrNull { it.optLong("updated_at") }!!
            }.sortedBy { it.optString("receiver_ip") }
    }

    private fun updateShareSheet(state: JSONObject) {
        if (shareSheet?.isShowing != true) return
        val shares = state.optJSONArray("shares") ?: JSONArray()
        val share = (0 until shares.length()).mapNotNull { shares.optJSONObject(it) }
            .lastOrNull { it.optString("mode") == sheetMode && (sheetToken.isEmpty() || it.optString("token") == sheetToken) }
        val token = share?.optString("token").orEmpty()
        if (sheetToken.isEmpty()) sheetToken = token
        if (sheetMode == "code") latestCodeToken = token else latestDirectToken = token
        val url = if (token.isEmpty() || state.optString("ip").isEmpty()) "" else "http://${state.optString("ip")}:52112/s/$token"
        sheetValue?.text = if (sheetMode == "code") share?.optString("code")?.ifBlank { "—" } ?: "—" else url
        sheetRipple?.active = token.isNotEmpty()
        sheetFiles?.text = "📁 分享内容  ${share?.optInt("file_count") ?: 0}  ﹀"
        val devices = shareReceivers(state, token)
        sheetDevices?.text = "🖥 接收设备  ${devices.size}  ﹀"
        val enabled = token.isNotEmpty()
        sheetStop?.isEnabled = enabled; sheetStop?.alpha = if (enabled) 1f else .4f
        sheetCopy?.isEnabled = url.isNotEmpty(); sheetCopy?.alpha = if (url.isNotEmpty()) 1f else .4f
        val seconds = ((share?.optLong("expires_at") ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0) / 1000
        sheetStatus?.text = if (share == null) "暂无有效分享，请先选择文件" else
            "⏱ 剩余有效时间 %02d:%02d".format(seconds / 60, seconds % 60)
        sheetStats?.text = "访问 ${devices.size} 台 · 下载中 ${devices.count { it.optString("status") == "sending" }} 个 · 手动结束"
        if (sheetMode == "direct" && url != sheetQrUrl) {
            sheetQrUrl = url
            if (url.isEmpty()) sheetQr?.setImageDrawable(null) else {
                val matrix = ShareQr.matrix(url, dp(150))
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) Color.BLACK else Color.WHITE
                }
                sheetQr?.setImageBitmap(Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888))
            }
        }
        if (sharePopover != null) renderSharePopover(state, share, devices)
    }

    private fun showSharePopover(anchor: View, kind: String) {
        val same = sharePopover?.isShowing == true && popoverKind == kind
        sharePopover?.dismiss()
        if (same) return
        popoverKind = kind; popoverSignature = ""
        val box = vertical().apply { background = rounded(Color.WHITE, 16, border); setPadding(0, dp(4), 0, dp(8)) }
        popoverContent = box
        val popup = PopupWindow(box, minOf(dp(270), resources.displayMetrics.widthPixels - dp(48)), -2, true).apply {
            elevation = dp(12).toFloat(); isOutsideTouchable = true
            animationStyle = R.style.ProgressiveWindowAnimation
            setBackgroundDrawable(rounded(Color.WHITE, 16, border))
            setOnDismissListener { sharePopover = null; popoverContent = null; popoverSignature = "" }
        }
        sharePopover = popup
        updateShareSheet(engine.state(peerId))
        UiMotion.apply(box)
        popup.showAsDropDown(anchor, if (kind == "devices") anchor.width - popup.width else 0, dp(8))
        updateShareSheet(engine.state(peerId))
    }

    private fun renderSharePopover(state: JSONObject, share: JSONObject?, receivers: List<JSONObject>) {
        val box = popoverContent ?: return
        val signature = if (popoverKind == "files") share?.toString().orEmpty() else
            JSONArray(receivers).toString() + state.optJSONArray("peers")?.toString().orEmpty()
        if (signature == popoverSignature) return
        popoverSignature = signature
        box.removeAllViews()
        box.addView(text(if (popoverKind == "files") "分享内容" else "接收设备", 13f, ink, true).apply {
            setPadding(dp(16), dp(8), dp(16), dp(10))
        })
        val content = vertical()
        val scroll = ScrollView(this).apply { addView(content) }
        val count = if (popoverKind == "files") share?.optJSONArray("files")?.length() ?: 0 else receivers.size
        box.addView(scroll, LinearLayout.LayoutParams(-1, dp(minOf(220, maxOf(48, count * if (popoverKind == "files") 42 else 76)))))
        if (popoverKind == "files") {
            val files = share?.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                content.addView(horizontal(Gravity.CENTER_VERTICAL).apply {
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    addView(text("📄", 13f, ink))
                    addView(text(file.optString("name"), 13f, ink).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(text(formatBytes(file.optLong("size")), 11f, muted))
                })
            }
            if (files.length() == 0) content.addView(text("尚未选择分享内容", 13f, muted).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
            box.addView(text(if ((share?.optInt("file_count") ?: 0) > files.length()) "显示前 ${files.length()} 项"
                else if (sheetMode == "direct") "单文件分享" else "文件 / 文件夹分享", 11f, muted).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
            })
        } else {
            val peers = state.optJSONArray("peers") ?: JSONArray()
            receivers.forEach { receiver ->
                val ip = receiver.optString("receiver_ip")
                val peer = (0 until peers.length()).mapNotNull { peers.optJSONObject(it) }.firstOrNull { it.optString("ip") == ip }
                val done = receiver.optLong("transferred"); val total = receiver.optLong("total")
                val status = receiver.optString("status")
                val percent = if (status == "completed") 100 else if (total > 0) (done * 100 / total).coerceIn(0, 100).toInt() else 0
                content.addView(vertical().apply {
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                    val head = horizontal(Gravity.CENTER_VERTICAL)
                    head.addView(text(peer?.optString("device_name") ?: ip, 12f, ink, true).apply {
                        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    head.addView(text(if (status == "completed") "✓ 完成" else if (status == "sending")
                        "${formatBytes(receiver.optLong("bytes_per_second"))}/s" else "", 11f,
                        if (status == "completed") Color.rgb(52, 199, 89) else blue, true))
                    addView(head)
                    addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100; progress = percent
                        progressTintList = ColorStateList.valueOf(if (status == "completed") Color.rgb(52, 199, 89) else blue)
                    }, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(8) })
                    addView(text(when (status) {
                        "completed" -> "已完成 · 100%"
                        "sending" -> "下载中 · $percent%"
                        "error" -> receiver.optString("error", "下载中断")
                        else -> "已访问 · 等待下载"
                    }, 10f, muted).apply { setPadding(0, dp(3), 0, 0) })
                })
            }
            if (receivers.isEmpty()) content.addView(text("尚无设备访问", 13f, muted).apply { setPadding(dp(16), dp(12), dp(16), dp(12)) })
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }


    private fun renderModernSettings(state: JSONObject) {
        val content = settingsContent ?: return
        val scroll = content.parent as ScrollView
        val y = scroll.scrollY
        content.removeAllViews()
        content.setPadding(0, 0, 0, dp(130))
        val name = state.optString("device_name", "Android 设备")
        content.setPadding(dp(20), dp(10), dp(20), dp(130))
        modernTitle(content, "我的", "设备与个人设置")
        content.addView(deviceProfileCard(state), modernMargin())
        val body = vertical().apply { setPadding(0, dp(8), 0, 0) }
        body.addView(section("设备设置", ""))
        body.addView(settingsGroup(
            settingRow("设备昵称", name) { editDeviceName() },
            settingRow("后台保活", keepAliveStatus()) { showKeepAliveSettings() },
            settingRow("默认下载路径", if(configuredDownloadTree()==null) "点击选择文件夹" else uiPrefs.getString("default_download_name","已设置").orEmpty()) { chooseDownloadDirectory() },
            settingRow("下载线程", "${uiPrefs.getInt("download_threads",8)} 线程 · 4MB 分段") {
                val counts = intArrayOf(1,2,4,8)
                AlertDialog.Builder(this).setTitle("下载线程（下次任务生效）")
                    .setSingleChoiceItems(arrayOf("1 线程", "2 线程", "4 线程", "8 线程"), counts.indexOf(uiPrefs.getInt("download_threads",8))) { dialog, index ->
                        uiPrefs.edit().putInt("download_threads",counts[index]).apply(); dialog.dismiss(); lastSnapshot=""; refresh()
                    }.setNegativeButton("返回",null).show()
            }
        ), modernMargin())
        body.addView(section("网络与数据", ""))
        body.addView(settingsGroup(settingRow("通讯协议", "v2 · UDP 52110"), settingRow("权限与文件访问", "查看说明") { showPermissionInfo() }, settingRow("聊天记录", "仅保存在本机")), modernMargin())
        body.addView(section("关于", ""))
        body.addView(settingsGroup(
            settingRow("版本", "1.0.0"),
            settingRow("官网", "GitHub 项目主页") {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/zhuroucheng1111-ux/ZTdrop")))
                } catch (_: android.content.ActivityNotFoundException) {
                    toast("未找到可以打开链接的浏览器")
                }
            }
        ), modernMargin())
        content.addView(body)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun deviceProfileCard(state: JSONObject): LinearLayout {
        val name = state.optString("device_name", "Android 设备")
        return vertical().apply {
            contentDescription = "我的资料"
            gravity = Gravity.CENTER
            minimumHeight = (resources.displayMetrics.heightPixels * .36f).toInt()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 51, 117), Color.rgb(69, 116, 184))).apply {
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(22), dp(25), dp(22), dp(25))
            addView(text(name.firstOrNull()?.uppercase() ?: "我", 34f, blue, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.WHITE, 28)
            }, LinearLayout.LayoutParams(dp(86), dp(86)))
            addView(text(name, 23f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                setPadding(0, dp(19), 0, 0)
            })
            addView(text("IP  ${state.optString("ip").ifBlank { "未连接 Wi-Fi" }}",
                14f, Color.WHITE).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(15), 0, 0)
            })
            addView(text("设备 ID  ${state.optString("device_id")}", 11f,
                Color.rgb(221, 233, 251)).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(text("修改设备昵称  ›", 12f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = rounded(Color.argb(50, 255, 255, 255), 12)
                setPadding(dp(13), dp(7), dp(13), dp(7))
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
            setOnClickListener { editDeviceName() }
        }
    }

    private fun makeHome() = makeModernHome()
    private fun renderHome(state: JSONObject) = renderModernHome(state)

    private fun displayName(friend: JSONObject): String = friend.optString("remark").ifBlank {
        friend.optString("device_name", "局域网设备")
    }

    private fun showConversationMenu(anchor: View, id: String, pinned: Boolean) {
        showAnchoredMenu(anchor, "会话快捷菜单", listOf(
            (if (pinned) "取消置顶" else "置顶聊天") to
                { runAction { engine.setConversationPinned(id, !pinned) } },
            "修改设备备注" to { editFriendRemark(id) },
            "删除设备好友" to { confirmRemoveFriend(id) }
        ))
    }

    private fun showDeviceMenu(anchor: View, id: String, status: String?) {
        val options: List<Pair<String, () -> Unit>> = when (status) {
            "accepted" -> listOf("进入会话" to { showPage(Page.CHAT, id) })
            "pending_out" -> listOf("取消申请" to { runAction { engine.cancelFriendRequest(id) } })
            "pending_in" -> listOf("接受申请" to { runAction { engine.answerFriend(id, true) } },
                "拒绝申请" to { runAction { engine.answerFriend(id, false) } })
            else -> listOf("申请添加好友" to { runAction { engine.requestFriend(id) } })
        }
        showAnchoredMenu(anchor, "设备快捷菜单", options)
    }

    private fun showMessageMenu(anchor: View, message: JSONObject, id: String) {
        val messageId = message.getString("message_id")
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (message.optString("kind") == "text") {
            val content = message.optString("content")
            options.add("复制" to {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ZTDrop", content))
            })
        }
        options.add("删除" to { runAction { engine.deleteMessage(id, messageId) } })
        showAnchoredMenu(anchor, "消息操作", options)
    }

    private fun showAnchoredMenu(anchor: View, description: String, options: List<Pair<String, () -> Unit>>) {
        conversationPopup?.dismiss()
        val menu = vertical().apply {
            contentDescription = description
            background = rounded(Color.WHITE, 12, border)
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val popup = PopupWindow(menu, dp(176), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            animationStyle = R.style.ProgressiveWindowAnimation
            isOutsideTouchable = true
            elevation = dp(9).toFloat()
            setBackgroundDrawable(rounded(Color.WHITE, 12, border))
            setOnDismissListener { if (conversationPopup === this) conversationPopup = null }
        }
        fun option(label: String, action: () -> Unit) {
            menu.addView(text(label, 15f, ink).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), 0, dp(12), 0)
                minimumHeight = dp(48)
                setOnClickListener { popup.dismiss(); action() }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        options.forEach { (label, action) -> option(label, action) }
        conversationPopup = popup
        UiMotion.apply(menu)
        popup.showAsDropDown(anchor, anchor.width - dp(190), -dp(4))
    }

    private fun makeDetailPage(title: String, backPage: Page): LinearLayout {
        val header = FrameLayout(this).apply { setBackgroundColor(headerSurface) }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "返回"
            setOnClickListener { showPage(backPage, peerId) }
        }
        header.addView(back, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(16)
        })
        header.addView(text(title, 18f, ink, true).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(-1, -1).apply { marginStart = dp(56); marginEnd = dp(56) })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        val scroll = ScrollView(this).apply { setBackgroundColor(surface); isFillViewport = true }
        val content = vertical().apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return content
    }

    private fun renderContact(state: JSONObject) {
        val content = contactContent ?: return
        val friends = state.optJSONArray("friends") ?: JSONArray()
        val friend = (0 until friends.length()).mapNotNull { friends.optJSONObject(it) }
            .firstOrNull { it.optString("device_id") == peerId } ?: return
        val peers = state.optJSONArray("peers") ?: JSONArray()
        val peer = (0 until peers.length()).mapNotNull { peers.optJSONObject(it) }
            .firstOrNull { it.optString("node_id") == peerId }
        val name = displayName(friend)
        content.removeAllViews()
        val hero = vertical().apply {
            contentDescription = "设备资料卡"
            gravity = Gravity.CENTER
            minimumHeight = (resources.displayMetrics.heightPixels * .44f).toInt()
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(10, 51, 117), Color.rgb(69, 116, 184))).apply {
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(22), dp(25), dp(22), dp(25))
        }
        hero.addView(text(name.firstOrNull()?.uppercase() ?: "?", 34f, blue, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, 28)
        }, LinearLayout.LayoutParams(dp(86), dp(86)))
        hero.addView(text(name, 23f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER; maxLines = 2
            setPadding(0, dp(21), 0, 0)
        })
        if (friend.optString("remark").isNotBlank()) hero.addView(text(
            "设备名称：${friend.optString("device_name")}", 13f, Color.rgb(221, 233, 251)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0)
        })
        hero.addView(text("IP  ${peer?.optString("ip")?.ifBlank { "离线" } ?: "离线"}",
            14f, Color.WHITE).apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        hero.addView(text("设备 ID  $peerId", 11f, Color.rgb(221, 233, 251)).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0); maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        content.addView(hero, rowMargin())
        content.addView(section("功能设置", ""))
        content.addView(settingsGroup(
            settingRow("查看聊天记录", "") { showPage(Page.HISTORY, peerId) },
            settingRow("清空记录", "") { confirmClearHistory(peerId) },
            settingRow("修改设备备注", friend.optString("remark")) { editFriendRemark(peerId) },
            settingRow("删除设备好友", "") { confirmRemoveFriend(peerId) }
        ), rowMargin())
    }

    private fun renderHistory(state: JSONObject) {
        val content = historyContent ?: return
        content.removeAllViews()
        val messages = state.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            content.addView(empty("暂无聊天记录"))
            return
        }
        val friends = state.optJSONArray("friends") ?: JSONArray()
        val friend = (0 until friends.length()).mapNotNull { friends.optJSONObject(it) }
            .firstOrNull { it.optString("device_id") == peerId }
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val own = message.optString("sender_id") == state.optString("device_id")
            val label = if (own) "我" else if (friend != null) displayName(friend) else "局域网设备"
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                .format(Date(message.optLong("created_at") * 1000L))
            val value = if (message.optString("kind") == "text") message.optString("content") else "文件"
            content.addView(vertical().apply {
                background = rounded(Color.WHITE, 14, border)
                setPadding(dp(16), dp(13), dp(16), dp(13))
                addView(text("$label  ·  $time", 12f, muted))
                addView(text(value, 15f, ink).apply { setPadding(0, dp(8), 0, 0) })
            }, rowMargin())
        }
    }

    private fun editFriendRemark(id: String) {
        val friends = engine.state("").optJSONArray("friends") ?: JSONArray()
        val friend = (0 until friends.length()).mapNotNull { friends.optJSONObject(it) }
            .firstOrNull { it.optString("device_id") == id } ?: return
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "输入设备备注"
            setText(friend.optString("remark"))
            setSelection(text.length)
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), 0, dp(20), 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this).setTitle("修改设备备注").setView(box)
            .setNegativeButton("取消", null)
            .setNeutralButton("清除备注") { _, _ -> runAction { engine.setFriendRemark(id, "") } }
            .setPositiveButton("保存") { _, _ -> runAction { engine.setFriendRemark(id, input.text.toString()) } }
            .show()
    }

    private fun confirmClearHistory(id: String) {
        AlertDialog.Builder(this).setTitle("清空记录")
            .setMessage("清空与该设备的本机聊天记录？")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ -> runAction { engine.clearHistory(id) } }
            .show()
    }

    private fun confirmRemoveFriend(id: String) {
        AlertDialog.Builder(this).setTitle("删除设备好友")
            .setMessage("删除后，该设备将从会话列表移除。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                try {
                    engine.removeFriend(id)
                    showPage(Page.HOME)
                } catch (error: Exception) { toast(error.message ?: "删除失败") }
            }.show()
    }

    private fun updateFeatureProgress(state: JSONObject) {
        updateShareSheet(state)
        val shares=state.optJSONArray("shares") ?: JSONArray()
        val active=(0 until shares.length()).mapNotNull { shares.optJSONObject(it) }
        latestCodeToken=active.lastOrNull { it.optString("mode")=="code" }?.optString("token").orEmpty()
        latestDirectToken=active.lastOrNull { it.optString("mode")=="direct" }?.optString("token").orEmpty()
    }

    private fun submitShareCode(value: String) {
        if (engine.isReceiving(receiveTaskId)) { showReceiveSheet(); return }
        showReceiveSheet(); receiveTaskId=""; receiveRetry=null; receiveEpoch=0L
        receiveAction?.visibility=View.GONE; receiveStop?.visibility=View.GONE; receiveAmount?.text=""; receivePath?.text=""
        receiveName?.text="分享码 ${value.trim()}"
        // A new query replaces only the old presentation, never an active download.
        featureDrafts[0] = value.trim()
        if (!featureDrafts[0].matches(Regex("[0-9]{4}"))) {
            receiveStatus?.text="请输入 4 位分享码"; receiveProgress?.isIndeterminate=false
            return
        }
        receiveStatus?.text="正在查找分享…"; receiveProgress?.isIndeterminate=true
        engine.queryCode(featureDrafts[0]) { offer -> runOnUiThread {
            if(receiveSheet?.isShowing!=true) return@runOnUiThread
            if (offer == null) { receiveStatus?.text="未找到有效分享码"; receiveProgress?.isIndeterminate=false }
            else selectDestination(offer)
        } }
    }
    private fun makeChat() {
        val header = FrameLayout(this).apply { setBackgroundColor(headerSurface) }
        val back = ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "返回会话"
            setOnClickListener { showPage(Page.HOME) }
        }
        header.addView(back, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.START or Gravity.CENTER_VERTICAL).apply {
            marginStart = dp(16)
        })
        chatName = text("局域网设备", 18f, ink, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        run {
            val titleGroup = horizontal(Gravity.CENTER).apply {
                chatPresence = presenceDot(false)
                addView(chatPresence, LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                    gravity = Gravity.CENTER_VERTICAL; marginEnd = dp(6)
                })
                addView(chatName, LinearLayout.LayoutParams(-2, -2))
            }
            header.addView(titleGroup, FrameLayout.LayoutParams(-1, -1).apply {
                marginStart = dp(56); marginEnd = dp(56)
            })
        }
        val more = ImageView(this).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "会话详情"
            setOnClickListener { showPage(Page.CONTACT, peerId) }
        }
        header.addView(more, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(16)
        })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(52)))
        root.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        val adapter = MessageAdapter()
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = null
            setSelector(ColorDrawable(Color.TRANSPARENT))
            itemsCanFocus = true
            isStackFromBottom = true
            setBackgroundColor(chatSurface)
            clipToPadding = false
            setPadding(dp(16), dp(16), dp(16), dp(8))
            setOnTouchListener { _, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) pinChatOnIme = false
                false
            }
        }
        chatAdapter = adapter
        chatList = list
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        val bar = horizontal(Gravity.CENTER_VERTICAL).apply {
            setBackgroundColor(Color.rgb(246, 246, 246))
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val input = EditText(this).apply {
            hint = ""
            setTextColor(ink)
            setHintTextColor(muted)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 1
            maxLines = 4
            minHeight = dp(42)
            filters = arrayOf(InputFilter.LengthFilter(2000))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rounded(Color.WHITE, 12)
            setText(drafts[peerId].orEmpty())
            setSelection(text.length)
            setOnFocusChangeListener { _, focused ->
                if (focused) pinChatOnIme = chatList?.let {
                    it.count == 0 || it.lastVisiblePosition >= it.count - 2
                } ?: false
                if (focused && composerPanel != ComposerPanel.NONE) setComposerPanel(ComposerPanel.NONE)
            }
            setOnClickListener {
                pinChatOnIme = chatList?.let {
                    it.count == 0 || it.lastVisiblePosition >= it.count - 2
                } ?: false
                setComposerPanel(ComposerPanel.NONE)
                requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        chatInput = input
        bar.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val emoji = ImageView(this).apply {
            setImageResource(R.drawable.ic_emoji)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "表情"
            setOnClickListener {
                if (composerPanel == ComposerPanel.EMOJI) {
                    setComposerPanel(ComposerPanel.NONE)
                    input.requestFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                } else setComposerPanel(ComposerPanel.EMOJI)
            }
        }
        chatEmoji = emoji
        bar.addView(emoji, LinearLayout.LayoutParams(dp(32), dp(32)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        val actionSlot = FrameLayout(this)
        val plus = ImageView(this).apply {
            setImageResource(R.drawable.ic_chat_plus)
            imageTintList = ColorStateList.valueOf(ink)
            contentDescription = "添加附件"
            setOnClickListener {
                setComposerPanel(if (composerPanel == ComposerPanel.EXTRAS) ComposerPanel.NONE
                    else ComposerPanel.EXTRAS)
            }
        }
        chatPlus = plus
        actionSlot.addView(plus, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        val button = text("发送", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setSingleLine(true)
            background = rounded(Color.rgb(7, 193, 96), 5)
            contentDescription = "发送"
            setOnClickListener { onComposerAction() }
        }
        chatAction = button
        actionSlot.addView(button, FrameLayout.LayoutParams(dp(48), dp(36), Gravity.CENTER))
        bar.addView(actionSlot, LinearLayout.LayoutParams(dp(50), dp(42)))
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateComposerAction()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateComposerAction()
        root.addView(bar)
        composerDrawer = vertical().apply {
            contentDescription = "功能抽屉"
            setBackgroundColor(Color.rgb(246, 246, 246))
            visibility = View.GONE
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, dp(260))) }
        chatBottomSpacer = View(this).apply {
            setBackgroundColor(Color.rgb(246, 246, 246))
        }.also { root.addView(it, LinearLayout.LayoutParams(-1, 0)) }
        root.requestFocus()
    }
    private fun renderChat(state: JSONObject) {
        val friends = state.optJSONArray("friends") ?: JSONArray()
        val friend = (0 until friends.length()).mapNotNull { friends.optJSONObject(it) }
            .firstOrNull { it.optString("device_id") == peerId }
        chatName?.text = if (friend != null) displayName(friend) else "局域网设备"
        chatAppearance = peerAppearance(peerId, friend?.let { displayName(it) } ?: "局域网设备", friend?.optBoolean("online") == true)
        selfAppearance = peerAppearance(state.optString("device_id"), state.optString("device_name", "我"), true)
        chatPresence?.background = presenceShape(chatAppearance.online)
        chatPresence?.contentDescription = if (chatAppearance.online) "在线" else "离线"
        val array = state.optJSONArray("messages") ?: JSONArray()
        val adapter = chatAdapter ?: return
        val list = chatList ?: return
        val signature = array.toString() + chatAppearance.toString() + selfAppearance.toString()
        if (signature != adapter.signature) {
            val atBottom = adapter.count == 0 || list.lastVisiblePosition >= adapter.count - 2
            adapter.replace((0 until array.length()).mapNotNull { array.optJSONObject(it) }, signature, state.optString("device_id"))
            if (atBottom && adapter.count > 0) list.post { list.setSelection(adapter.count - 1) }
        }
    }

    private fun updateComposerAction() {
        val hasText = !chatInput?.text.isNullOrBlank()
        chatAction?.visibility = if (hasText) View.VISIBLE else View.GONE
        chatPlus?.visibility = if (hasText) View.GONE else View.VISIBLE
    }

    private fun onComposerAction() {
        val input = chatInput ?: return
        val value = input.text.toString().trim()
        if (value.isNotEmpty()) runAction {
            engine.sendText(peerId, value)
            input.text.clear()
        }
    }

    private fun setComposerPanel(next: ComposerPanel) {
        val drawer = composerDrawer ?: return
        composerPanel = next
        drawer.animate().cancel()
        if (next == ComposerPanel.NONE) {
            if(ValueAnimator.areAnimatorsEnabled() && drawer.visibility==View.VISIBLE) {
                drawer.animate().translationY(dp(36).toFloat()).alpha(0f).setDuration(180).withEndAction {
                    drawer.visibility=View.GONE; drawer.translationY=0f; drawer.alpha=1f
                }.start()
            } else drawer.visibility = View.GONE
            chatEmoji?.setImageResource(R.drawable.ic_emoji)
            chatEmoji?.contentDescription = "表情"
            return
        }
        hideKeyboard()
        drawer.removeAllViews()
        drawer.contentDescription = if (next == ComposerPanel.EMOJI) "表情抽屉" else "功能抽屉"
        if (next == ComposerPanel.EMOJI) renderEmojiDrawer(drawer) else renderExtrasDrawer(drawer)
        drawer.visibility = View.VISIBLE
        UiMotion.apply(drawer)
        UiMotion.enter(drawer)
        if(ValueAnimator.areAnimatorsEnabled()) {
            drawer.translationY=dp(48).toFloat()
            drawer.animate().translationY(0f).alpha(1f).setDuration(280).start()
        }
        chatEmoji?.setImageResource(if (next == ComposerPanel.EMOJI) R.drawable.ic_chat_keyboard
            else R.drawable.ic_emoji)
        chatEmoji?.contentDescription = if (next == ComposerPanel.EMOJI) "切换键盘" else "表情"
        chatList?.post { chatList?.setSelection((chatAdapter?.count ?: 1) - 1) }
    }

    private fun renderExtrasDrawer(drawer: LinearLayout) {
        val row = horizontal(Gravity.TOP).apply { setPadding(dp(14), dp(28), dp(14), 0) }
        listOf("文件" to 0, "文件夹" to R.drawable.ic_folder_outline,
            "相册" to R.drawable.ic_album).forEach { (label, resource) ->
            val tile = vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                val icon = if (label == "文件") text("▤", 27f, ink).apply {
                    gravity = Gravity.CENTER
                    background = rounded(Color.WHITE, 16)
                } else ImageView(this@MainActivity).apply {
                    setImageResource(resource)
                    background = rounded(Color.WHITE, 16)
                    setPadding(dp(18), dp(18), dp(18), dp(18))
                }
                addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
                addView(text(label, 12f, muted).apply { gravity = Gravity.CENTER },
                    LinearLayout.LayoutParams(dp(64), dp(26)))
                setOnClickListener { selectSource("chat", folder = label == "文件夹", image = label == "相册", peer = peerId) }
                contentDescription = label
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(76), -2).apply { marginEnd = dp(14) })
        }
        drawer.addView(row)
        UiMotion.apply(drawer)
    }

    private fun renderEmojiDrawer(drawer: LinearLayout) {
        val tabs = horizontal(Gravity.CENTER_VERTICAL).apply { setPadding(dp(16), dp(9), 0, dp(9)) }
        val stickerTab = FrameLayout(this).apply { background = rounded(Color.WHITE, 5) }
        stickerTab.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_emoji)
            imageTintList = ColorStateList.valueOf(ink)
        }, FrameLayout.LayoutParams(dp(25), dp(25), Gravity.CENTER))
        tabs.addView(stickerTab, LinearLayout.LayoutParams(dp(36), dp(36)))
        drawer.addView(tabs)
        drawer.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
        val choices = listOf("😀", "😂", "😊", "😍", "🥰", "😘", "😎", "😭", "👍", "🙏", "🎉", "❤️", "😅", "🤔", "👏", "🔥", "👋", "😇")
        choices.chunked(6).forEach { group ->
            val row = horizontal(Gravity.CENTER).apply { setPadding(dp(9), dp(8), dp(9), 0) }
            group.forEach { symbol ->
                row.addView(text(symbol, 25f, ink).apply {
                    gravity = Gravity.CENTER
                    setOnClickListener {
                        chatInput?.text?.insert(chatInput?.selectionStart?.coerceAtLeast(0) ?: 0, symbol)
                    }
                }, LinearLayout.LayoutParams(0, dp(48), 1f))
            }
            drawer.addView(row)
        }
    }

    private fun renderSettings(state: JSONObject) = renderModernSettings(state)

    private fun editDeviceName() {
        val input = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(engine.deviceName)
            setSelection(text.length)
            setPadding(dp(15), dp(12), dp(15), dp(12))
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(16), 0, dp(16), 0); addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(this).setTitle("修改设备昵称").setMessage("附近的 ZTDrop 设备会看到这个名称。")
            .setView(box).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ -> runAction { engine.setDeviceName(input.text.toString()) } }
            .create()
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.requestFocus()
        input.postDelayed({
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private val requestKeepAliveNotification = 4105

    override fun onResume() {
        super.onResume()
        lastSnapshot = ""
        refresh()
    }

    private fun batteryExempt(): Boolean =
        (getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun notificationsAllowed(): Boolean =
        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).areNotificationsEnabled()

    private fun keepAliveStatus(): String = when {
        LanRuntime.isServiceRunning() -> "已运行 · " + if (batteryExempt()) "电池已授权" else "待电池授权"
        uiPrefs.getBoolean("keep_alive", false) -> "已启用 · 服务启动中"
        else -> "未启用"
    }

    private fun showKeepAliveSettings() {
        val enabled = uiPrefs.getBoolean("keep_alive", false)
        val notification = if (notificationsAllowed()) "已允许" else "未允许"
        val battery = if (batteryExempt()) "已忽略优化" else "未授权"
        val error = uiPrefs.getString("keep_alive_error", "").orEmpty()
        AlertDialog.Builder(this).setTitle("后台保活")
            .setMessage("服务：${keepAliveStatus()}\n通知：$notification\n电池：$battery\n\n启用后显示后台运行通知，保持局域网连接和消息接收；锁屏时会增加耗电。系统强行停止及厂商省电策略仍可能终止进程，可在系统应用设置中允许后台运行。" + if (error.isBlank()) "" else "\n\n启动信息：$error")
            .setPositiveButton(if (enabled) "关闭保活" else "启用并申请") { _, _ ->
                if (enabled) {
                    uiPrefs.edit().putBoolean("keep_alive", false).apply()
                    LanRuntime.requestKeepAlive(false)
                    stopService(Intent(this, KeepAliveService::class.java))
                    lastSnapshot = ""; refresh()
                } else if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), requestKeepAliveNotification)
                } else if (startKeepAlive()) requestBatteryExemption()
            }
            .setNeutralButton("电池优化授权") { _, _ -> requestBatteryExemption(true) }
            .setNegativeButton("通知设置") { _, _ ->
                openSystemPage(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }.show()
    }

    private fun startKeepAlive(): Boolean {
        uiPrefs.edit().putBoolean("keep_alive", true).remove("keep_alive_error").apply()
        LanRuntime.requestKeepAlive(true)
        return try {
            startForegroundService(Intent(this, KeepAliveService::class.java))
            lastSnapshot = ""; refresh()
            true
        } catch (error: Exception) {
            uiPrefs.edit().putBoolean("keep_alive", false).putString("keep_alive_error", error.message).apply()
            LanRuntime.requestKeepAlive(false)
            AlertDialog.Builder(this).setTitle("后台服务未启动").setMessage(error.message)
                .setPositiveButton("知道了", null).show()
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestKeepAliveNotification && !isDestroyed) {
            // Notification denial doesn't prohibit FGS; show the real status rather than pretend it was granted.
            if (startKeepAlive()) requestBatteryExemption()
        }
    }

    private fun requestBatteryExemption(openIfGranted: Boolean = false) {
        if (batteryExempt() && !openIfGranted) return
        val intent = if (batteryExempt()) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            openSystemPage(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun openSystemPage(intent: Intent) {
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) {
            AlertDialog.Builder(this).setTitle("系统设置").setMessage("请在系统应用信息中调整通知和电池后台运行选项。")
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun showPermissionInfo() {
        AlertDialog.Builder(this).setTitle("权限与文件访问")
            .setMessage("局域网通信声明了联网、网络状态、Wi-Fi 状态和组播普通权限，安装时由系统授予，不弹出运行时授权。\n\n文件和文件夹只访问你在系统选择器中选中的内容；相册通过系统照片选择器访问所选图片，不读取整个相册，也不申请全盘存储、位置、相机或麦克风权限。\n\n后台保活额外声明前台服务、通知、电池优化申请和唤醒锁权限；通知与电池授权仅在设置中主动申请，允许状态由系统决定。")
            .setPositiveButton("知道了", null)
            .setNeutralButton("系统应用信息") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }.show()
    }

    private fun runAction(action: () -> Unit) {
        try { action(); refresh() } catch (error: Exception) { toast(error.message ?: "操作失败") }
    }

    private fun section(title: String, count: String) = horizontal(Gravity.CENTER_VERTICAL).apply {
        setPadding(dp(2), dp(24), dp(2), dp(10))
        addView(text(title, 16f, ink, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (count.isNotEmpty()) addView(text(count, 12f, muted))
    }

    private fun settingRow(label: String, value: String, click: (() -> Unit)? = null) = horizontal(Gravity.CENTER_VERTICAL).apply {
        background = rounded(Color.WHITE, 14, border); minimumHeight = dp(57)
        setPadding(dp(15), dp(12), dp(15), dp(12))
        addView(text(label, 14f, ink), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(value, 12f, muted).apply { maxLines = 1; maxWidth = dp(190) })
        if (click != null) { addView(text("  ›", 20f, muted)); setOnClickListener { click() } }
    }

    private fun settingsGroup(vararg rows: View) = vertical().apply {
        background = rounded(Color.WHITE, 18, border)
        rows.forEachIndexed { index, row ->
            if (index > 0) addView(View(this@MainActivity).apply { setBackgroundColor(border) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { marginStart = dp(15) })
            row.background = null
            addView(row, LinearLayout.LayoutParams(-1, -2))
        }
    }

    private fun empty(value: String) = text(value, 12f, muted).apply {
        gravity = Gravity.CENTER; setPadding(dp(15), dp(22), dp(15), dp(22)); background = rounded(Color.WHITE, 14, border)
    }

    private fun action(label: String, primary: Boolean, click: () -> Unit) = text(label, 13f, if (primary) Color.WHITE else blue, true).apply {
        gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
        background = rounded(if (primary) blue else paleBlue, 11)
        setOnClickListener { click() }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun horizontal(gravity: Int) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
    private fun rowMargin() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    private fun chatSp(value: Float) = value * when (uiPrefs.getInt("font_scale", 1)) { 0 -> .88f; 2 -> 1.14f; else -> 1f }
    private fun rounded(fill: Int, radius: Int, outline: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat()
        if (outline != null) setStroke(dp(1), outline)
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_SHORT).show()

    private inner class MessageAdapter : BaseAdapter() {
        private val rows = ArrayList<JSONObject>()
        private val clock = SimpleDateFormat("HH:mm", Locale.CHINA)
        var signature = ""
            private set
        private var selfId = ""

        fun replace(next: List<JSONObject>, encoded: String, ownId: String) {
            rows.clear()
            rows.addAll(next)
            signature = encoded
            selfId = ownId
            notifyDataSetChanged()
        }

        override fun getCount() = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: MessageHolder
            val cell: LinearLayout
            if (convertView is LinearLayout && convertView.tag is MessageHolder) {
                cell = convertView
                holder = convertView.tag as MessageHolder
            } else {
                cell = vertical().apply { setPadding(0, 0, 0, dp(6)) }
                val time = text("", 11f, muted).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(10), 0, dp(12))
                }
                val row = horizontal(Gravity.TOP)
                val avatar = text("", 17f, Color.WHITE, true).apply { gravity = Gravity.CENTER }
                val bubble = text("", 16f, ink).apply {
                    maxWidth = ((resources.displayMetrics.widthPixels - dp(32)) * (.62f)).toInt()
                    setPadding(dp(16), dp(12),
                        dp(16), dp(12))
                    includeFontPadding = false; setLineSpacing(0f, 1.5f)
                }
                val bubbleContainer = FrameLayout(this@MainActivity).apply {
                    isClickable = false; isFocusable = false
                }
                bubbleContainer.addView(bubble, FrameLayout.LayoutParams(-2, -2))
                val image = ImageView(this@MainActivity).apply {
                    adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                    visibility = View.GONE; contentDescription = "聊天图片，点击预览"
                }
                bubbleContainer.addView(image, FrameLayout.LayoutParams(dp(200), -2))
                val status = text("", 10f, muted).apply {
                    gravity = Gravity.END
                    setPadding(0, dp(3), dp(49), 0)
                }
                cell.addView(time)
                cell.addView(row)
                val avatarColumn = vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
                avatarColumn.addView(avatar, LinearLayout.LayoutParams(dp(42), dp(42)))
                avatarColumn.addView(status, LinearLayout.LayoutParams(-1, -2))
                holder = MessageHolder(time, row, avatar, bubbleContainer, bubble, status, avatarColumn, image)
                cell.tag = holder
            }
            val message = rows[position]
            val own = message.optString("sender_id") == selfId
            val timestamp = message.optLong("created_at")
            val previous = if (position > 0) rows[position - 1].optLong("created_at") else 0L
            holder.time.visibility = if (position == 0 || timestamp - previous >= 300L) View.VISIBLE else View.GONE
            holder.time.text = clock.format(Date(timestamp * 1000L))
            holder.avatar.background = rounded(if (own) selfAppearance.color else chatAppearance.color, 10)
            run {
                val appearance = if (own) selfAppearance else chatAppearance
                holder.avatar.text = appearance.name.firstOrNull()?.uppercase() ?: "?"
            }
            val isText = message.optString("kind") == "text"
            val mediaUri = message.optString("local_media_uri")
            val hasImage = !isText && mediaUri.isNotEmpty()
            // 长按统一打开应用菜单，避免系统选字菜单与复制/删除菜单重叠。
            holder.bubble.setTextIsSelectable(false)
            val conversationId = peerId
            val messageLongPress = View.OnLongClickListener { anchor ->
                showMessageMenu(anchor, message, conversationId)
                true
            }
            holder.bubble.setOnLongClickListener(messageLongPress)
            holder.image.setOnLongClickListener(messageLongPress)
            holder.bubble.visibility = if (hasImage) View.GONE else View.VISIBLE
            holder.image.visibility = if (hasImage) View.VISIBLE else View.GONE
            holder.image.setOnClickListener(null)
            if (hasImage) {
                ChatImages.loadInto(this@MainActivity, mediaUri, holder.image) {
                    holder.image.visibility = View.GONE; holder.bubble.visibility = View.VISIBLE
                }
                holder.image.setOnClickListener {
                    val drawable = holder.image.drawable as? android.graphics.drawable.BitmapDrawable
                    if (drawable != null) {
                        val zoom = ZoomImageView(this@MainActivity).apply { setImageBitmap(drawable.bitmap); layoutParams = LinearLayout.LayoutParams(-1, dp(360)) }
                        AlertDialog.Builder(this@MainActivity).setTitle("图片预览").setView(zoom).setPositiveButton("关闭", null).show()
                    }
                }
            } else { holder.image.tag = null; holder.image.setImageDrawable(null) }
            holder.bubble.text = if (isText) message.optString("content")
                else "${if (ChatImages.isImage(message.optString("file_name"))) "▧ 图片" else "▤"} ${message.optString("file_name", "文件")}${if (own) "" else when (message.optString("download_status")) {
                    "completed" -> "\n已保存"
                    "failed" -> "\n接收失败，点击重试"
                    else -> "\n点击接收"
                }}"
            holder.bubble.setOnClickListener(if (!own && message.optString("kind") != "text") View.OnClickListener {
                try { selectDestination(engine.offerForMessage(message)) }
                catch (error: Exception) { showReceiveSheet(); receiveStatus?.text=error.message ?: "接收失败"; receiveProgress?.isIndeterminate=false }
            } else null)
            holder.bubble.textSize = chatSp(16f)
            holder.bubbleContainer.background = rounded(if (own) outgoingBubble else Color.WHITE, 10).apply {
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(),
                    dp(16).toFloat(), dp(16).toFloat(), dp(if (own) 4 else 16).toFloat(),
                    dp(if (own) 4 else 16).toFloat(), dp(if (own) 16 else 4).toFloat(), dp(if (own) 16 else 4).toFloat())
            }
            holder.bubble.background = null
            holder.bubble.isClickable = isText || (!own && message.optString("kind") != "text")
            if (!isText && holder.bubble.isClickable) UiMotion.install(holder.bubble, holder.bubbleContainer.background)
            else holder.bubble.foreground = null
            holder.row.removeAllViews()
            holder.row.gravity = if (own) Gravity.END else Gravity.START
            val avatarParams = LinearLayout.LayoutParams(dp(42), dp(42))
            val bubbleParams = LinearLayout.LayoutParams(-2, -2)
            if (own) {
                bubbleParams.marginEnd = dp(10)
                holder.row.addView(holder.bubbleContainer, bubbleParams)
                holder.row.addView(holder.avatarColumn, LinearLayout.LayoutParams(dp(42), -2).apply {
                    marginEnd = avatarParams.marginEnd
                })
            } else {
                avatarParams.marginEnd = dp(10)
                holder.row.addView(holder.avatarColumn, LinearLayout.LayoutParams(dp(42), -2).apply {
                    marginEnd = avatarParams.marginEnd
                })
                holder.row.addView(holder.bubbleContainer, bubbleParams)
            }
            run {
                holder.status.gravity = Gravity.CENTER
                holder.status.textSize = 10f
                holder.status.includeFontPadding = false
                holder.status.maxLines = 1
                holder.status.setPadding(0, dp(2), 0, 0)
            }
            holder.status.visibility = if (own) View.VISIBLE else View.GONE
            holder.status.text = if (message.optString("delivery_status") == "delivered") "已送达" else "待送达"
            return cell
        }
    }

    private data class MessageHolder(
        val time: TextView,
        val row: LinearLayout,
        val avatar: TextView,
        val bubbleContainer: FrameLayout,
        val bubble: TextView,
        val status: TextView,
        val avatarColumn: LinearLayout,
        val image: ImageView
    )
}
