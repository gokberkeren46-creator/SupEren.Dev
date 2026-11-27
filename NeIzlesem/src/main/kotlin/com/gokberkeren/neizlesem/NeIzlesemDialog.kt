package com.gokberkeren.neizlesem

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.TvType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * Two-stage dark modal for 'Ne İzlesem':
 *  Stage 1: Choose Format (Film/Dizi), Category, Min IMDb Score, Source Scope.
 *  Stage 2: 3 side-by-side surprise poster cards; tap/OK opens directly in ResultFragment,
 *           or roll 3 brand-new picks with one click.
 */
object NeIzlesemDialog {

    private const val COLOR_BG = 0xFF121622.toInt()
    private const val COLOR_CARD = 0xFF1B2234.toInt()
    private const val COLOR_STROKE = 0xFF2A344D.toInt()
    private const val COLOR_PRIMARY = 0xFF6366F1.toInt()
    private const val COLOR_PRIMARY_FOCUS = 0xFF818CF8.toInt()
    private const val COLOR_GOLD = 0xFFF59E0B.toInt()
    private const val COLOR_MUTED = 0xFF252E44.toInt()
    private const val COLOR_MUTED_FOCUS = 0xFF364261.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFFF8FAFC.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFF94A3B8.toInt()

    private val posterClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val posterCache = LruCache<String, Bitmap>(24)

    fun show(activity: FragmentActivity, engine: PoolEngine) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var drawJob: Job? = null
        var filter = FilterState()

        val dm = activity.resources.displayMetrics
        val d = dm.density
        fun dp(v: Int): Int = (v * d).toInt()

        fun cardBg(bg: Int = COLOR_CARD, stroke: Int = COLOR_STROKE, radius: Int = 12): GradientDrawable {
            return GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(radius).toFloat()
                setStroke(dp(1), stroke)
            }
        }

        fun focusBtn(normalColor: Int, focusedColor: Int, selectedBorder: Boolean = false): StateListDrawable {
            val normal = GradientDrawable().apply {
                setColor(normalColor)
                cornerRadius = dp(10).toFloat()
                if (selectedBorder) setStroke(dp(2), Color.WHITE)
            }
            val focused = GradientDrawable().apply {
                setColor(focusedColor)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
        }

        fun chip(label: String): Button {
            return Button(activity).apply {
                text = label
                isAllCaps = false
                textSize = 11f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(6), dp(5), dp(6), dp(5))
                minHeight = dp(32)
                minimumHeight = dp(32)
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                }
            }
        }

        fun sectionTitle(textStr: String): TextView {
            return TextView(activity).apply {
                text = textStr
                textSize = 10f
                setTextColor(COLOR_TEXT_SECONDARY)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(5))
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(COLOR_BG, COLOR_STROKE, 16)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val headerTitle = TextView(activity).apply {
            text = "🎲 Ne İzlesem?"
            textSize = 18f
            setTextColor(COLOR_TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        val headerSub = TextView(activity).apply {
            text = "SupEren.Dev • Filtreni seç, 3 sürpriz içerikten birine direkt git"
            textSize = 10.5f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, 0, 0, dp(8))
        }

        // ---- STAGE 1: FILTERS ----
        val filterBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Format row
        val formatCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(10), dp(6), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
        formatCard.addView(sectionTitle("TÜR"))
        val formatRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val formatBtns = MediaFormat.values().map { it to chip(it.label) }
        fun refreshFormat() {
            formatBtns.forEach { (fmt, b) ->
                val sel = filter.format == fmt
                b.background = if (sel) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true)
                else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        formatBtns.forEach { (fmt, b) ->
            b.setOnClickListener {
                filter = filter.copy(format = fmt)
                refreshFormat()
            }
            formatRow.addView(b)
        }
        refreshFormat()
        formatCard.addView(formatRow)

        // Category grid (3 rows x 4 cols = 12 categories)
        val catCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(10), dp(6), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
        catCard.addView(sectionTitle("KATEGORİ"))
        val catBtns = mutableListOf<Pair<String, Button>>()
        fun refreshCats() {
            catBtns.forEach { (cat, b) ->
                val sel = filter.category == cat
                b.background = if (sel) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true)
                else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        FilterState.CATEGORIES.chunked(4).forEachIndexed { idx, rowCats ->
            val r = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (idx > 0) topMargin = dp(4) }
            }
            rowCats.forEach { cat ->
                val b = chip(cat)
                b.setOnClickListener {
                    filter = filter.copy(category = cat)
                    refreshCats()
                }
                catBtns.add(cat to b)
                r.addView(b)
            }
            catCard.addView(r)
        }
        refreshCats()

        // IMDb row
        val imdbCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(10), dp(6), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(7) }
        }
        imdbCard.addView(sectionTitle("MİNİMUM IMDb PUANI"))
        val imdbRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val imdbBtns = FilterState.IMDB_OPTIONS.map { (lbl, v) -> v to chip(lbl) }
        fun refreshImdb() {
            imdbBtns.forEach { (v, b) ->
                val sel = filter.minImdb == v
                b.background = if (sel) focusBtn(COLOR_GOLD, 0xFFFBBF24.toInt(), true)
                else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        imdbBtns.forEach { (v, b) ->
            b.setOnClickListener {
                filter = filter.copy(minImdb = v)
                refreshImdb()
            }
            imdbRow.addView(b)
        }
        refreshImdb()
        imdbCard.addView(imdbRow)

        // Source scope row
        val scopeCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            setPadding(dp(10), dp(6), dp(10), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        scopeCard.addView(sectionTitle("KAYNAK HAVUZU"))
        val scopeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val scopeBtns = SourceScope.values().map { it to chip(it.label) }
        fun refreshScope() {
            scopeBtns.forEach { (sc, b) ->
                val sel = filter.scope == sc
                b.background = if (sel) focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS, true)
                else focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            }
        }
        scopeBtns.forEach { (sc, b) ->
            b.setOnClickListener {
                filter = filter.copy(scope = sc)
                refreshScope()
            }
            scopeRow.addView(b)
        }
        refreshScope()
        scopeCard.addView(scopeRow)

        val drawBtn = Button(activity).apply {
            text = "🎲 3 Sürpriz Öneri Getir"
            isAllCaps = false
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS)
            minHeight = dp(42)
            minimumHeight = dp(42)
            isFocusable = true
        }

        filterBox.addView(formatCard)
        filterBox.addView(catCard)
        filterBox.addView(imdbCard)
        filterBox.addView(scopeCard)
        filterBox.addView(drawBtn)

        // ---- STAGE 2: 3 SURPRISE CARDS ----
        val resultBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        val loadingBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(28))
            visibility = View.GONE
        }
        val spinner = ProgressBar(activity)
        val loadingText = TextView(activity).apply {
            text = "Yüklü sitelerden 3 sürpriz öneri seçiliyor..."
            textSize = 12f
            setTextColor(COLOR_TEXT_SECONDARY)
            setPadding(0, dp(10), 0, 0)
        }
        loadingBox.addView(spinner)
        loadingBox.addView(loadingText)

        val cardsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val emptyNotice = TextView(activity).apply {
            text = "Bu filtreye uygun içerik bulunamadı. IMDb veya kategoriyi esnetip tekrar dene."
            textSize = 12.5f
            setTextColor(COLOR_TEXT_SECONDARY)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(22), dp(12), dp(22))
            visibility = View.GONE
        }

        val resultActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val rerollBtn = Button(activity).apply {
            text = "🎲 Üçünü Yenile"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_PRIMARY, COLOR_PRIMARY_FOCUS)
            minHeight = dp(38)
            minimumHeight = dp(38)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.3f).apply {
                marginEnd = dp(5)
            }
        }
        val backToFilterBtn = Button(activity).apply {
            text = "⚙️ Filtreler"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            minHeight = dp(38)
            minimumHeight = dp(38)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(5)
            }
        }
        val closeResultBtn = Button(activity).apply {
            text = "Kapat"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            minHeight = dp(38)
            minimumHeight = dp(38)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
        }
        resultActions.addView(rerollBtn)
        resultActions.addView(backToFilterBtn)
        resultActions.addView(closeResultBtn)

        resultBox.addView(loadingBox)
        resultBox.addView(cardsRow)
        resultBox.addView(emptyNotice)
        resultBox.addView(resultActions)

        val closeFilterBtn = Button(activity).apply {
            text = "Kapat"
            isAllCaps = false
            textSize = 12.5f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = focusBtn(COLOR_MUTED, COLOR_MUTED_FOCUS)
            minHeight = dp(34)
            minimumHeight = dp(34)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        }
        filterBox.addView(closeFilterBtn)

        root.addView(headerTitle)
        root.addView(headerSub)
        root.addView(filterBox)
        root.addView(resultBox)

        val scroll = ScrollView(activity).apply { addView(root) }
        val dialog = AlertDialog.Builder(activity).setView(scroll).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setOnDismissListener {
            drawJob?.cancel()
            scope.cancel()
        }

        fun runDraw() {
            filterBox.visibility = View.GONE
            resultBox.visibility = View.VISIBLE
            cardsRow.removeAllViews()
            cardsRow.visibility = View.GONE
            emptyNotice.visibility = View.GONE
            loadingBox.visibility = View.VISIBLE

            drawJob?.cancel()
            drawJob = scope.launch {
                val picks = engine.drawThree(filter, null)
                loadingBox.visibility = View.GONE
                if (picks.isEmpty()) {
                    emptyNotice.visibility = View.VISIBLE
                    backToFilterBtn.requestFocus()
                    return@launch
                }

                cardsRow.removeAllViews()
                cardsRow.visibility = View.VISIBLE
                var firstCard: View? = null

                picks.forEach { pick ->
                    val cardNormal = GradientDrawable().apply {
                        setColor(COLOR_CARD)
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), COLOR_STROKE)
                    }
                    val cardFocused = GradientDrawable().apply {
                        setColor(0xFF25304A.toInt())
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(2), Color.WHITE)
                    }

                    val card = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        isClickable = true
                        isFocusable = true
                        setPadding(dp(6), dp(6), dp(6), dp(8))
                        background = StateListDrawable().apply {
                            addState(intArrayOf(android.R.attr.state_focused), cardFocused)
                            addState(intArrayOf(android.R.attr.state_pressed), cardFocused)
                            addState(intArrayOf(), cardNormal)
                        }
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            marginStart = dp(4)
                            marginEnd = dp(4)
                        }
                    }

                    val posterHolder = FrameLayout(activity).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(175)
                        )
                        background = GradientDrawable().apply {
                            setColor(0xFF0E1320.toInt())
                            cornerRadius = dp(9).toFloat()
                        }
                    }

                    val posterImg = ImageView(activity).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    posterHolder.addView(posterImg)

                    // IMDb badge top-right
                    if (pick.score != null) {
                        val scoreBadge = TextView(activity).apply {
                            text = String.format(Locale.US, "⭐ %.1f", pick.score)
                            textSize = 10f
                            setTextColor(Color.WHITE)
                            setTypeface(typeface, Typeface.BOLD)
                            setPadding(dp(5), dp(2), dp(5), dp(2))
                            background = GradientDrawable().apply {
                                setColor(0xCC121622.toInt())
                                cornerRadius = dp(6).toFloat()
                                setStroke(dp(1), COLOR_GOLD)
                            }
                        }
                        posterHolder.addView(
                            scoreBadge,
                            FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                Gravity.TOP or Gravity.END
                            ).apply {
                                topMargin = dp(5)
                                marginEnd = dp(5)
                            }
                        )
                    }

                    val nameView = TextView(activity).apply {
                        text = pick.response.name
                        textSize = 12f
                        setTextColor(COLOR_TEXT_PRIMARY)
                        setTypeface(typeface, Typeface.BOLD)
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(2), dp(6), dp(2), dp(2))
                    }

                    val typeLabel = when (pick.response.type) {
                        TvType.TvSeries, TvType.Anime, TvType.AsianDrama -> "Dizi"
                        else -> "Film"
                    }
                    val metaView = TextView(activity).apply {
                        text = "$typeLabel • ${pick.apiName}"
                        textSize = 10f
                        setTextColor(COLOR_TEXT_SECONDARY)
                        setSingleLine(true)
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(dp(2), 0, dp(2), 0)
                    }

                    card.addView(posterHolder)
                    card.addView(nameView)
                    card.addView(metaView)

                    card.setOnClickListener {
                        dialog.dismiss()
                        engine.openCandidate(activity, pick)
                    }

                    cardsRow.addView(card)
                    if (firstCard == null) firstCard = card

                    val posterUrl = pick.response.posterUrl
                    if (!posterUrl.isNullOrBlank()) {
                        scope.launch {
                            val bmp = loadPosterBitmap(posterUrl, pick.response.posterHeaders)
                            if (bmp != null) posterImg.setImageBitmap(bmp)
                        }
                    }
                }

                firstCard?.requestFocus()
            }
        }

        drawBtn.setOnClickListener { runDraw() }
        rerollBtn.setOnClickListener { runDraw() }
        backToFilterBtn.setOnClickListener {
            drawJob?.cancel()
            resultBox.visibility = View.GONE
            filterBox.visibility = View.VISIBLE
            drawBtn.requestFocus()
        }
        closeFilterBtn.setOnClickListener { dialog.dismiss() }
        closeResultBtn.setOnClickListener { dialog.dismiss() }

        dialog.show()
        val targetWidth = min(dp(560), (dm.widthPixels * 0.90f).toInt())
        dialog.window?.setLayout(targetWidth, WindowManager.LayoutParams.WRAP_CONTENT)
        drawBtn.requestFocus()
    }

    private suspend fun loadPosterBitmap(url: String, headers: Map<String, String>?): Bitmap? =
        withContext(Dispatchers.IO) {
            posterCache.get(url)?.let { return@withContext it }
            try {
                val reqBuilder = Request.Builder().url(url).get()
                headers?.forEach { (k, v) ->
                    if (k.isNotBlank() && v.isNotBlank()) reqBuilder.header(k, v)
                }
                posterClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val bytes = resp.body?.bytes() ?: return@withContext null
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                    if (bmp != null) posterCache.put(url, bmp)
                    bmp
                }
            } catch (_: Throwable) {
                null
            }
        }
}
