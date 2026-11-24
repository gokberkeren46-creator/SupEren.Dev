package com.gokberkeren.birlikteizle

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.random.Random

/**
 * Lightweight programmatic overlay attached to the active player screen.
 * Provides a tiny top-right emoji trigger (touch + TV D-Pad friendly) and
 * animates incoming/outgoing emoji reactions floating upward over the video.
 */
class EmojiOverlay(private val roomManager: RoomManager) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var hostParent: ViewGroup? = null
    private var overlayRoot: FrameLayout? = null
    private var pickerBar: LinearLayout? = null
    private var triggerBtn: TextView? = null
    private var hidePickerRunnable: Runnable? = null

    fun attach(container: ViewGroup) {
        if (hostParent === container && overlayRoot != null) {
            updateVisibility()
            return
        }
        detach()
        val ctx = container.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }

        // Top-right controls row: [ picker bar ] [ 😊 trigger ]
        val controlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(18)
                marginEnd = dp(18)
            }
        }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = GradientDrawable().apply {
                setColor(0xCC121622.toInt())
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), 0x666366F1)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(8)
            }
        }

        QUICK_EMOJIS.forEach { emoji ->
            val btn = TextView(ctx).apply {
                text = emoji
                textSize = 20f
                gravity = Gravity.CENTER
                isFocusable = true
                isClickable = true
                background = focusableCircleBg(ctx, 0x00000000, 0x666366F1)
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
                setOnClickListener {
                    roomManager.sendReaction(emoji)
                    hidePicker()
                }
            }
            bar.addView(btn)
        }

        val trigger = TextView(ctx).apply {
            text = "😊"
            textSize = 19f
            gravity = Gravity.CENTER
            alpha = 0.72f
            isFocusable = true
            isClickable = true
            background = focusableCircleBg(ctx, 0x99121622.toInt(), 0xCC6366F1.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnFocusChangeListener { _, hasFocus ->
                alpha = if (hasFocus) 1.0f else 0.72f
            }
            setOnClickListener {
                if (bar.visibility == View.VISIBLE) {
                    hidePicker()
                } else {
                    showPicker()
                }
            }
        }

        controlsRow.addView(bar)
        controlsRow.addView(trigger)
        root.addView(controlsRow)

        container.addView(root)
        hostParent = container
        overlayRoot = root
        pickerBar = bar
        triggerBtn = trigger
        updateVisibility()
    }

    fun updateVisibility() {
        val visible = if (roomManager.isInRoom()) View.VISIBLE else View.GONE
        overlayRoot?.visibility = visible
        if (visible != View.VISIBLE) {
            hidePicker()
        }
    }

    fun detach() {
        hidePickerRunnable?.let { mainHandler.removeCallbacks(it) }
        hidePickerRunnable = null
        val root = overlayRoot
        val parent = hostParent
        if (root != null && parent != null) {
            try {
                parent.removeView(root)
            } catch (_: Throwable) {
            }
        }
        overlayRoot = null
        hostParent = null
        pickerBar = null
        triggerBtn = null
    }

    fun showFloatingReaction(reaction: EmojiReaction) {
        val root = overlayRoot ?: return
        if (!roomManager.isInRoom()) return
        val ctx = root.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val bubble = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = false
            isFocusable = false
        }

        val emojiView = TextView(ctx).apply {
            text = reaction.emoji
            textSize = 36f
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        }
        val nameView = TextView(ctx).apply {
            text = reaction.senderName.take(14)
            textSize = 11f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(2), dp(8), dp(2))
            background = GradientDrawable().apply {
                setColor(0x99121622.toInt())
                cornerRadius = dp(10).toFloat()
            }
        }
        bubble.addView(emojiView)
        bubble.addView(nameView)

        val jitterEnd = dp(24 + Random.nextInt(0, 96))
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dp(64)
            marginEnd = jitterEnd
        }
        root.addView(bubble, lp)

        val risePx = -dp(240 + Random.nextInt(0, 70)).toFloat()
        val driftX = dp(Random.nextInt(-28, 28)).toFloat()

        val moveY = ObjectAnimator.ofFloat(bubble, View.TRANSLATION_Y, 0f, risePx).apply {
            duration = 2400L
            interpolator = DecelerateInterpolator()
        }
        val moveX = ObjectAnimator.ofFloat(bubble, View.TRANSLATION_X, 0f, driftX).apply {
            duration = 2400L
        }
        val scaleX = ObjectAnimator.ofFloat(bubble, View.SCALE_X, 0.6f, 1.15f, 1.0f).apply {
            duration = 450L
        }
        val scaleY = ObjectAnimator.ofFloat(bubble, View.SCALE_Y, 0.6f, 1.15f, 1.0f).apply {
            duration = 450L
        }
        val fadeOut = ObjectAnimator.ofFloat(bubble, View.ALPHA, 1f, 1f, 0f).apply {
            duration = 2400L
        }

        AnimatorSet().apply {
            playTogether(moveY, moveX, scaleX, scaleY, fadeOut)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        root.removeView(bubble)
                    } catch (_: Throwable) {
                    }
                }
            })
            start()
        }
    }

    private fun showPicker() {
        val bar = pickerBar ?: return
        bar.visibility = View.VISIBLE
        bar.getChildAt(0)?.requestFocus()
        hidePickerRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable { hidePicker() }
        hidePickerRunnable = r
        mainHandler.postDelayed(r, 6_000L)
    }

    private fun hidePicker() {
        hidePickerRunnable?.let { mainHandler.removeCallbacks(it) }
        hidePickerRunnable = null
        pickerBar?.visibility = View.GONE
    }

    private fun focusableCircleBg(ctx: Context, normalColor: Int, focusedColor: Int): StateListDrawable {
        val d = ctx.resources.displayMetrics.density
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(normalColor)
            setStroke((1 * d).toInt(), 0x55FFFFFF)
        }
        val focused = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(focusedColor)
            setStroke((2 * d).toInt(), Color.WHITE)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(android.R.attr.state_pressed), focused)
            addState(intArrayOf(), normal)
        }
    }

    companion object {
        private val QUICK_EMOJIS = listOf("😂", "❤️", "🔥", "💀", "😱", "😭", "🤬", "🤯")
    }
}
