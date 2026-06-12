package com.arturo254.opentune.playback

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.palette.graphics.Palette
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import com.arturo254.opentune.R
import kotlinx.coroutines.*
import java.net.URL

class BubbleService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.arturo254.opentune.BUBBLE_UPDATE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_IS_PLAYING = "isPlaying"
        const val EXTRA_ART_URL = "artUrl"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubbleView: View
    private lateinit var expandedView: View
    private var isExpanded = false

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentIsPlaying = false
    private var currentPosition = 0L
    private var currentDuration = 0L
    private var currentArtBitmap: Bitmap? = null
    private var accentColor: Int = 0xFFBB86FC.toInt()
    private var gradientColors: IntArray? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            currentTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
            currentArtist = intent.getStringExtra(EXTRA_ARTIST) ?: ""
            currentIsPlaying = intent.getBooleanExtra(EXTRA_IS_PLAYING, false)
            currentPosition = intent.getLongExtra(EXTRA_POSITION, 0L)
            currentDuration = intent.getLongExtra(EXTRA_DURATION, 0L)
            val artUrl = intent.getStringExtra(EXTRA_ART_URL)

            updateExpandedUI()
            updateBubblePlayState()

            if (artUrl != null && artUrl.isNotBlank()) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val request = ImageRequest.Builder(this@BubbleService)
                            .data(artUrl)
                            .build()
                        
                        val result = imageLoader.execute(request)
                        val bitmap = result.image?.toBitmap() ?: return@launch
                        
                        val palette = Palette.from(bitmap).generate()
                        
                        val dominant = palette.getDominantColor(0xFF121212.toInt())
                        val vibrant = palette.getVibrantColor(dominant)
                        val muted = palette.getMutedColor(dominant)
                        
                        val color1 = adjustAlpha(dominant, 0.8f)
                        val color2 = adjustAlpha(vibrant, 0.6f)
                        val color3 = adjustAlpha(muted, 0.7f)

                        withContext(Dispatchers.Main) {
                            currentArtBitmap = bitmap
                            accentColor = vibrant
                            gradientColors = intArrayOf(color1, color2, color3)
                            updateBubbleArt()
                            updateExpandedUI()
                            updateBubblePlayState()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(android.graphics.Color.alpha(color) * factor)
        val red = android.graphics.Color.red(color)
        val green = android.graphics.Color.green(color)
        val blue = android.graphics.Color.blue(color)
        return android.graphics.Color.argb(alpha, red, green, blue)
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubbleView()
        createExpandedView()

        val filter = IntentFilter(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubbleView() {
        val size = (60 * resources.displayMetrics.density).toInt()

        bubbleView = FrameLayout(this).apply {
            val bg = ImageView(context).apply {
                setImageResource(R.drawable.music_note)
                setBackgroundResource(R.drawable.bubble_bg)
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                val pad = (4 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                tag = "art"
            }
            addView(bg, FrameLayout.LayoutParams(size, size))
            
            val pulseView = View(context).apply {
                setBackgroundResource(R.drawable.bubble_bg)
                alpha = 0f
                tag = "pulse"
            }
            addView(pulseView, FrameLayout.LayoutParams(size, size))
        }

        val params = WindowManager.LayoutParams(
            size, size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var moved = false

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    bubbleView.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (java.lang.Math.abs(dx) > 10 || java.lang.Math.abs(dy) > 10) moved = true
                    params.x = initialX + dx.toInt()
                    params.y = initialY + dy.toInt()
                    windowManager.updateViewLayout(bubbleView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bubbleView.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (!moved) {
                        toggleExpanded()
                    } else {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val finalX = if (params.x + size / 2 < screenWidth / 2) 0 else screenWidth - size
                        params.x = finalX
                        windowManager.updateViewLayout(bubbleView, params)
                    }
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    private fun createExpandedView() {
        val dp = resources.displayMetrics.density
        val padding = (16 * dp).toInt()

        expandedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bubble_expanded_bg)
            setPadding(padding, padding, padding, padding)
            visibility = View.GONE
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val artView = ImageView(this).apply {
            tag = "expandedArt"
            setImageResource(R.drawable.music_note)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            setBackgroundResource(R.drawable.bubble_bg)
            val size = (64 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (12 * dp).toInt()
            }
        }
        topRow.addView(artView)

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleView = TextView(this).apply {
            tag = "title"
            text = currentTitle.ifBlank { "Dezzy" }
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        infoCol.addView(titleView)

        val artistView = TextView(this).apply {
            tag = "artist"
            text = currentArtist.ifBlank { "..." }
            textSize = 14f
            setTextColor(0xCCFFFFFF.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        infoCol.addView(artistView)
        topRow.addView(infoCol)

        val closeBtn = ImageButton(this).apply {
            setImageResource(R.drawable.close)
            setColorFilter(0xAAFFFFFF.toInt())
            setBackgroundResource(android.R.color.transparent)
            val cSize = (32 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(cSize, cSize).apply {
                gravity = Gravity.TOP
            }
            setOnClickListener { toggleExpanded() }
        }
        topRow.addView(closeBtn)

        (expandedView as LinearLayout).addView(topRow)

        val progressContainer = FrameLayout(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (4 * dp).toInt()
            ).apply {
                topMargin = (12 * dp).toInt()
            }
            layoutParams = lp
            setBackgroundColor(0x33FFFFFF)
        }
        
        val progressBar = View(this).apply {
            tag = "progressBar"
            setBackgroundColor(0xFFFFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        progressContainer.addView(progressBar)
        (expandedView as LinearLayout).addView(progressContainer)

        val controlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (16 * dp).toInt()
            layoutParams = lp
        }

        val btnSize = (48 * dp).toInt()
        val btnPadding = (8 * dp).toInt()

        val prevBtn = ImageButton(this).apply {
            setImageResource(R.drawable.skip_previous)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundResource(android.R.color.transparent)
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener { sendPlayerCommand("PREVIOUS") }
        }
        controlsRow.addView(prevBtn)

        val playPauseBtn = ImageButton(this).apply {
            tag = "playPause"
            setImageResource(if (currentIsPlaying) R.drawable.pause else R.drawable.play)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundResource(android.R.color.transparent)
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize).apply {
                marginStart = (16 * dp).toInt()
                marginEnd = (16 * dp).toInt()
            }
            setOnClickListener { sendPlayerCommand("PLAY_PAUSE") }
        }
        controlsRow.addView(playPauseBtn)

        val nextBtn = ImageButton(this).apply {
            setImageResource(R.drawable.skip_next)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundResource(android.R.color.transparent)
            setPadding(btnPadding, btnPadding, btnPadding, btnPadding)
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            setOnClickListener { sendPlayerCommand("NEXT") }
        }
        controlsRow.addView(nextBtn)

        (expandedView as LinearLayout).addView(controlsRow)

        val expandedParams = WindowManager.LayoutParams(
            (300 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.3f
        }

        windowManager.addView(expandedView, expandedParams)
    }

    private fun toggleExpanded() {
        isExpanded = !isExpanded
        if (isExpanded) {
            expandedView.visibility = View.VISIBLE
            expandedView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.OvershootInterpolator())
                .start()
            updateExpandedUI()
        } else {
            expandedView.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(200)
                .withEndAction { expandedView.visibility = View.GONE }
                .start()
        }
    }

    private fun updateExpandedUI() {
        expandedView.findViewWithTag<TextView>("title")?.text =
            currentTitle.ifBlank { "Dezzy" }
        expandedView.findViewWithTag<TextView>("artist")?.text =
            currentArtist.ifBlank { "..." }
        expandedView.findViewWithTag<ImageButton>("playPause")?.setImageResource(
            if (currentIsPlaying) R.drawable.pause else R.drawable.play
        )
        
        val progressView = expandedView.findViewWithTag<View>("progressBar")
        if (progressView != null && currentDuration > 0) {
            val ratio = currentPosition.toFloat() / currentDuration.toFloat()
            val parentWidth = (progressView.parent as View).width
            if (parentWidth > 0) {
                progressView.layoutParams.width = (parentWidth * ratio).toInt()
                progressView.requestLayout()
            }
        }
        
        gradientColors?.let { colors ->
            val gd = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors)
            gd.cornerRadius = 28 * resources.displayMetrics.density
            gd.setStroke((1 * resources.displayMetrics.density).toInt(), 0x33FFFFFF)
            expandedView.background = gd
        }

        currentArtBitmap?.let { bitmap ->
            expandedView.findViewWithTag<ImageView>("expandedArt")?.setImageBitmap(bitmap)
        }
    }

    private fun updateBubblePlayState() {
        val pulse = bubbleView.findViewWithTag<View>("pulse") ?: return
        if (currentIsPlaying) {
            (pulse.background as? GradientDrawable)?.apply {
                setStroke((1.5 * resources.displayMetrics.density).toInt(), accentColor)
            }
            
            pulse.alpha = 0.6f
            pulse.scaleX = 1f
            pulse.scaleY = 1f
            pulse.animate()
                .alpha(0f)
                .scaleX(1.4f)
                .scaleY(1.4f)
                .setDuration(1200)
                .withEndAction { updateBubblePlayState() }
                .start()
        } else {
            pulse.animate().cancel()
            pulse.alpha = 0f
        }
    }

    private fun updateBubbleArt() {
        currentArtBitmap?.let { bitmap ->
            bubbleView.findViewWithTag<ImageView>("art")?.setImageBitmap(bitmap)
            
            (bubbleView.findViewWithTag<View>("art")?.background as? GradientDrawable)?.apply {
                setStroke((1.5 * resources.displayMetrics.density).toInt(), accentColor)
            }
        }
    }

    private fun sendPlayerCommand(command: String) {
        val intent = Intent("com.arturo254.opentune.BUBBLE_COMMAND").apply {
            putExtra("command", command)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        try { windowManager.removeView(bubbleView) } catch (_: Exception) {}
        try { windowManager.removeView(expandedView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
