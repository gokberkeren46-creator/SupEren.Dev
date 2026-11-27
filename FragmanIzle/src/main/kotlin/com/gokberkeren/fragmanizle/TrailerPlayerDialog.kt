package com.gokberkeren.fragmanizle

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object TrailerPlayerDialog {

    private const val COLOR_BG = 0xF7101522.toInt()
    private const val COLOR_PRIMARY = 0xFF6366F1.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF818CF8.toInt()
    private const val COLOR_MUTED = 0xFF27314A.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF394769.toInt()

    fun openExternalYouTube(activity: Activity, videoId: String) {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (_: Throwable) {
            Toast.makeText(activity, "YouTube uygulaması açılamadı", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showInApp(activity: Activity, title: String, result: TrailerResult) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dm = activity.resources.displayMetrics
        val d = dm.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun roundedBg(color: Int, radiusDp: Int = 10, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable {
            return GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radiusDp).toFloat()
                if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
            }
        }

        fun focusBtn(normal: Int, focused: Int): StateListDrawable {
            val nBg = roundedBg(normal, 8)
            val fBg = roundedBg(focused, 8, Color.WHITE, 2)
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), fBg)
                addState(intArrayOf(android.R.attr.state_pressed), fBg)
                addState(intArrayOf(), nBg)
            }
        }

        val dialogWidth = (dm.widthPixels * 0.86f).toInt().coerceAtMost(dp(760))
        val playerHeight = ((dialogWidth - dp(24)) * 9f / 16f).toInt().coerceAtMost((dm.heightPixels * 0.62f).toInt())

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg(COLOR_BG, 14, 0x55FFFFFF, 1)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val header = TextView(activity).apply {
            text = "🎬 $title — Fragman"
            textSize = 14.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(8))
            maxLines = 1
        }
        root.addView(header)

        val playerContainer = FrameLayout(activity).apply {
            background = roundedBg(Color.BLACK, 10)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                playerHeight
            ).apply { bottomMargin = dp(10) }
        }

        var webView: WebView? = null
        try {
            val wv = WebView(activity).apply {
                setBackgroundColor(Color.BLACK)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString =
                        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                }
                webChromeClient = WebChromeClient()
                webViewClient = WebViewClient()
            }
            val embedUrl =
                "https://www.youtube-nocookie.com/embed/${result.videoId}?autoplay=1&rel=0&modestbranding=1&playsinline=1"
            val headers = mapOf("Referer" to "https://www.youtube-nocookie.com")
            wv.loadUrl(embedUrl, headers)
            playerContainer.addView(
                wv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            webView = wv
        } catch (_: Throwable) {
            // If system WebView is missing on a custom TV ROM, fallback directly to external YouTube
            openExternalYouTube(activity, result.videoId)
            return
        }

        root.addView(playerContainer)

        val actionsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val ytBtn = Button(activity).apply {
            text = "📺 YouTube Uygulamasında Aç"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS)
            isFocusable = true
            minHeight = dp(38)
            minimumHeight = dp(38)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                marginEnd = dp(8)
            }
        }

        val closeBtn = Button(activity).apply {
            text = "Kapat"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            isFocusable = true
            minHeight = dp(38)
            minimumHeight = dp(38)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
        }

        actionsRow.addView(ytBtn)
        actionsRow.addView(closeBtn)
        root.addView(actionsRow)

        val dialog = AlertDialog.Builder(activity).setView(root).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        ytBtn.setOnClickListener {
            dialog.dismiss()
            openExternalYouTube(activity, result.videoId)
        }
        closeBtn.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            try {
                webView?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    onPause()
                    removeAllViews()
                    destroy()
                }
            } catch (_: Throwable) {}
            webView = null
        }

        dialog.show()
        dialog.window?.setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        closeBtn.requestFocus()
    }
}
