package com.photosweep.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    data class Photo(
        val uri: Uri,
        val name: String,
        val dateSeconds: Long,
        val modifiedSeconds: Long,
        val bytes: Long,
        val width: Int,
        val height: Int,
        val hash: Long,
        val quality: Double
    )
    data class PhotoGroup(val photos: List<Photo>)
    data class Results(val duplicates: List<PhotoGroup>, val similar: List<PhotoGroup>)
    private data class Meta(val uri: Uri, val name: String, val date: Long, val modified: Long, val bytes: Long, val width: Int, val height: Int)
    private data class Cached(val modified: Long, val bytes: Long, val hash: Long, val quality: Double)
    private data class Fingerprint(val hash: Long, val quality: Double)
    private sealed class Row {
        data class Heading(val label: String, val section: Boolean) : Row()
        data class Cell(val photo: Photo, val recommended: Boolean) : Row()
    }

    private val scanControl = Executors.newSingleThreadExecutor()
    private val scanPool = Executors.newFixedThreadPool(4)
    private val imagePool = Executors.newFixedThreadPool(3)
    private val thumbnailCache = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private lateinit var status: TextView
    private lateinit var scanButton: Button
    private lateinit var deleteButton: Button
    private lateinit var selectionLabel: TextView
    private lateinit var duplicateTab: Button
    private lateinit var similarTab: Button
    private lateinit var list: RecyclerView
    private lateinit var adapter: PhotoAdapter
    private val selected = linkedSetOf<Uri>()
    private var photos: List<Photo> = emptyList()
    private var results = Results(emptyList(), emptyList())
    private var showingDuplicates = true
    private var pendingDelete = emptyList<Uri>()
    private var scanRun = 0

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) scanGallery() else status.text = "برای بررسی عکس‌ها، اجازهٔ دسترسی به گالری لازم است."
    }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val removed = pendingDelete.toSet()
            photos = photos.filterNot { removed.contains(it.uri) }
            selected.removeAll(removed)
            saveCache(photos)
            results = groupPhotos(photos)
            showStatus("حذف انجام شد. برای عکس‌های تازه، «به‌روزرسانی گالری» را بزن.")
            render()
            AlertDialog.Builder(this)
                .setTitle("حذف انجام شد")
                .setMessage("${removed.size} عکس حذف شد و از فهرست کنار رفت. اگر عکس تازه‌ای اضافه کرده‌ای، «به‌روزرسانی گالری» را بزن.")
                .setPositiveButton("متوجه شدم", null)
                .show()
        } else {
            status.text = "حذف لغو شد؛ عکس‌ها هنوز در فهرست هستند."
        }
        pendingDelete = emptyList()
        updateDeleteButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            setBackgroundColor(0xFFF3F6F4.toInt())
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(10) + bars.bottom)
            insets
        }
        page.addView(text("عکس‌یار", 27, true).apply { setTextColor(0xFF183A32.toInt()) })
        page.addView(text("عکس‌های تکراری و شات‌های مشابه را پیدا کن و خودت انتخاب کن چه چیزی حذف شود.", 14, false).apply {
            setTextColor(0xFF50645E.toInt()); setPadding(0, dp(3), 0, dp(8))
        })
        status = text("برای شروع، دسترسی عکس‌ها را بده و گالری را بررسی کن.", 13, false).apply {
            setTextColor(0xFF31584E.toInt()); setPadding(dp(10), dp(8), dp(10), dp(8)); setBackgroundColor(0xFFE2EEE9.toInt())
        }
        page.addView(status)
        scanButton = Button(this).apply { text = "بررسی / به‌روزرسانی گالری"; setOnClickListener { ensurePermission() } }
        page.addView(scanButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        duplicateTab = Button(this).apply { setOnClickListener { showingDuplicates = true; render() } }
        similarTab = Button(this).apply { setOnClickListener { showingDuplicates = false; render() } }
        tabs.addView(duplicateTab, LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(similarTab, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(tabs)

        list = RecyclerView(this).apply { setBackgroundColor(0xFFF3F6F4.toInt()); itemAnimator = null }
        adapter = PhotoAdapter()
        list.adapter = adapter
        val grid = GridLayoutManager(this, 2)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int = if (adapter.rows.getOrNull(position) is Row.Heading) 2 else 1
        }
        list.layoutManager = grid
        page.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(4))
            setBackgroundColor(0xFFFFFFFF.toInt())
            elevation = dp(8).toFloat()
        }
        selectionLabel = text("عکسی انتخاب نشده؛ برای حذف، عکس‌ها را علامت بزن.", 13, true).apply {
            setTextColor(0xFF50645E.toInt())
            setPadding(dp(4), 0, dp(4), dp(3))
        }
        footer.addView(selectionLabel)
        val deleteColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFFB3261E.toInt(), 0xFFD9DEDC.toInt())
        )
        val deleteTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF78827F.toInt())
        )
        deleteButton = Button(this).apply {
            text = "حذف انتخاب‌شده‌ها (۰)"
            isEnabled = false
            minHeight = dp(58)
            backgroundTintList = deleteColors
            setTextColor(deleteTextColors)
            val icon = this@MainActivity.getDrawable(R.drawable.ic_delete)?.mutate()?.apply { setTintList(deleteTextColors) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(8)
            setOnClickListener { requestDelete() }
        }
        footer.addView(deleteButton, LinearLayout.LayoutParams(-1, -2))
        page.addView(footer)
        setContentView(page)
        render()
        ensurePermission()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); gravity = Gravity.RIGHT
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun ensurePermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) scanGallery() else permissionLauncher.launch(permissions)
    }

    private fun scanGallery() {
        if (!scanButton.isEnabled) return
        val run = ++scanRun
        selected.clear()
        updateDeleteButton()
        scanButton.isEnabled = false
        status.text = "در حال فهرست‌کردن عکس‌های گالری…"
        scanControl.execute {
            try {
                val cached = loadCache()
                val metadata = queryMetadata()
                val known = HashMap<String, Photo>(metadata.size)
                val changed = ArrayList<Meta>()
                for (meta in metadata) {
                    val key = meta.uri.toString()
                    val old = cached[key]
                    if (old != null && old.modified == meta.modified && old.bytes == meta.bytes) {
                        known[key] = Photo(meta.uri, meta.name, meta.date, meta.modified, meta.bytes, meta.width, meta.height, old.hash, old.quality)
                    } else changed.add(meta)
                }
                runOnUiThread { if (run == scanRun) status.text = "در حال بررسی عکس‌های جدید یا تغییرکرده: ۰ از ${changed.size} • ${metadata.size} عکس در گالری" }
                val completion = ExecutorCompletionService<Photo?>(scanPool)
                changed.forEach { meta -> completion.submit(Callable { fingerprint(meta) }) }
                var done = 0
                repeat(changed.size) {
                    val photo = completion.take().get()
                    if (photo != null) known[photo.uri.toString()] = photo
                    done++
                    if (done % 40 == 0 || done == changed.size) {
                        val current = done
                        runOnUiThread { if (run == scanRun) status.text = "در حال بررسی عکس‌های جدید یا تغییرکرده: $current از ${changed.size} • ${metadata.size} عکس در گالری" }
                    }
                }
                val fresh = metadata.mapNotNull { known[it.uri.toString()] }.sortedByDescending { it.dateSeconds }
                val grouped = groupPhotos(fresh)
                saveCache(fresh)
                runOnUiThread {
                    if (run != scanRun) return@runOnUiThread
                    photos = fresh
                    results = grouped
                    scanButton.isEnabled = true
                    val cachedCount = metadata.size - changed.size
                    showStatus("بررسی ${metadata.size} عکس تمام شد؛ $cachedCount مورد از حافظهٔ اسکن قبلی استفاده شد. برای عکس‌های تازه، به‌روزرسانی را بزن.")
                    render()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (run == scanRun) {
                        scanButton.isEnabled = true
                        status.text = "بررسی کامل نشد: ${error.localizedMessage ?: "خطای دسترسی به گالری"}"
                    }
                }
            }
        }
    }

    private fun queryMetadata(): List<Meta> {
        val out = ArrayList<Meta>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
        )
        contentResolver.query(collection, columns, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                out.add(Meta(uri, cursor.getString(nameCol) ?: "عکس", cursor.getLong(dateCol), cursor.getLong(modCol), cursor.getLong(sizeCol), cursor.getInt(widthCol), cursor.getInt(heightCol)))
            }
        }
        return out
    }

    private fun fingerprint(meta: Meta): Photo? = runCatching {
        val bitmap = try {
            contentResolver.loadThumbnail(meta.uri, Size(256, 256), null)
        } catch (_: Exception) {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            contentResolver.openInputStream(meta.uri).use { BitmapFactory.decodeStream(it, null, options) }
        } ?: return null
        val (hash, quality) = calculateMetrics(bitmap, meta.width, meta.height)
        bitmap.recycle()
        Photo(meta.uri, meta.name, meta.date, meta.modified, meta.bytes, meta.width, meta.height, hash, quality)
    }.getOrNull()

    private fun calculateMetrics(bitmap: Bitmap, width: Int, height: Int): Pair<Long, Double> {
        val hashBitmap = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luma(hashBitmap.getPixel(x, y)) > luma(hashBitmap.getPixel(x + 1, y))) hash = (hash shl 1) or 1L else hash = hash shl 1
        }
        hashBitmap.recycle()
        val small = Bitmap.createScaledBitmap(bitmap, 64, 64, true)
        var sum = 0.0; var squares = 0.0; var edges = 0.0; var color = 0.0
        val values = IntArray(64 * 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            val pixel = small.getPixel(x, y)
            val lum = luma(pixel)
            val i = y * 64 + x
            values[i] = lum
            sum += lum; squares += lum.toDouble() * lum
            val r = pixel shr 16 and 255; val g = pixel shr 8 and 255; val b = pixel and 255
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            color += if (max == 0) 0.0 else (max - min).toDouble() / max
            if (x > 0) edges += abs(lum - values[i - 1])
            if (y > 0) edges += abs(lum - values[i - 64])
        }
        small.recycle()
        val count = 4096.0
        val mean = sum / count
        val deviation = sqrt((squares / count - mean * mean).coerceAtLeast(0.0))
        val sharpness = (edges / (2 * 64 * 63) / 45.0).coerceIn(0.0, 1.0)
        val exposure = (1.0 - abs(mean - 127.5) / 127.5).coerceIn(0.0, 1.0)
        val contrast = (deviation / 70.0).coerceIn(0.0, 1.0)
        val resolution = (ln((width.toLong().coerceAtLeast(1) * height.toLong().coerceAtLeast(1)).toDouble()) / ln(48_000_000.0)).coerceIn(0.0, 1.0)
        val colorfulness = color / count
        val quality = 0.45 * sharpness + 0.20 * exposure + 0.15 * contrast + 0.15 * resolution + 0.05 * colorfulness
        return hash to quality
    }

    private fun luma(pixel: Int): Int = ((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000

    private fun groupPhotos(items: List<Photo>): Results {
        val exactBuckets = items.groupBy { "${it.hash}:${it.width}x${it.height}:${it.bytes}" }
        val duplicates = exactBuckets.values.filter { it.size > 1 }.map { PhotoGroup(it.sortedByDescending { p -> p.quality }) }
            .sortedByDescending { it.photos.size }
        val duplicateUris = duplicates.flatMap { it.photos }.map { it.uri }.toHashSet()
        val candidates = items.filterNot { duplicateUris.contains(it.uri) }
        if (candidates.size < 2) return Results(duplicates, emptyList())
        val parent = IntArray(candidates.size) { it }
        fun root(index: Int): Int {
            var n = index
            while (parent[n] != n) { parent[n] = parent[parent[n]]; n = parent[n] }
            return n
        }
        fun join(a: Int, b: Int) { val ra = root(a); val rb = root(b); if (ra != rb) parent[rb] = ra }
        val byTime = candidates.indices.sortedBy { candidates[it].dateSeconds }
        val bands = Array(8) { HashMap<Int, MutableList<Int>>() }
        for (position in byTime.indices) {
            val i = byTime[position]
            val photo = candidates[i]
            val possible = HashSet<Int>()
            for (band in 0 until 8) {
                val key = ((photo.hash ushr (band * 8)) and 255L).toInt()
                val prior = bands[band][key] ?: continue
                for (k in prior.indices.reversed()) {
                    val j = prior[k]
                    if (photo.dateSeconds - candidates[j].dateSeconds > 15 * 60) break
                    possible.add(j)
                }
            }
            for (j in possible) {
                val distance = java.lang.Long.bitCount(photo.hash xor candidates[j].hash)
                if (distance in 0..7 && abs(photo.dateSeconds - candidates[j].dateSeconds) <= 15 * 60) join(i, j)
            }
            for (band in 0 until 8) {
                val key = ((photo.hash ushr (band * 8)) and 255L).toInt()
                bands[band].getOrPut(key) { ArrayList() }.add(i)
            }
        }
        val similar = candidates.indices.groupBy { root(it) }.values.filter { it.size > 1 }
            .map { ids -> PhotoGroup(ids.map { candidates[it] }.sortedByDescending { it.quality }) }
            .sortedByDescending { it.photos.size }
        return Results(duplicates, similar)
    }

    private fun loadCache(): Map<String, Cached> {
        val root = runCatching { JSONObject(getSharedPreferences("photo_index", MODE_PRIVATE).getString("items", "{}")) }.getOrElse { JSONObject() }
        val cache = HashMap<String, Cached>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = root.optJSONObject(key) ?: continue
            cache[key] = Cached(value.optLong("modified"), value.optLong("bytes"), value.optLong("hash"), value.optDouble("quality"))
        }
        return cache
    }

    private fun saveCache(items: List<Photo>) {
        val json = JSONObject()
        items.forEach { p ->
            json.put(p.uri.toString(), JSONObject().put("modified", p.modifiedSeconds).put("bytes", p.bytes).put("hash", p.hash).put("quality", p.quality))
        }
        getSharedPreferences("photo_index", MODE_PRIVATE).edit().putString("items", json.toString()).apply()
    }

    private fun render() {
        duplicateTab.text = "تکراری‌ها (${results.duplicates.size})"
        similarTab.text = "مشابه‌ها (${results.similar.size})"
        val groups = if (showingDuplicates) results.duplicates else results.similar
        val rows = ArrayList<Row>()
        val sectionTitle = if (showingDuplicates) "تکراری‌ها • ${groups.size} گروه" else "عکس‌های مشابه • ${groups.size} گروه"
        rows.add(Row.Heading(sectionTitle, true))
        rows.add(Row.Heading(if (showingDuplicates) "در این بخش فایل‌های دارای اندازه، ابعاد و هش تصویری یکسان هستند." else "در این بخش شات‌های نزدیک از نظر ظاهر و زمان ثبت گروه شده‌اند." , true))
        groups.forEachIndexed { index, group ->
            val star = group.photos.maxByOrNull { it.quality }?.uri
            rows.add(Row.Heading("گروه ${index + 1} • ${group.photos.size} عکس", false))
            group.photos.forEach { rows.add(Row.Cell(it, it.uri == star)) }
        }
        if (groups.isEmpty()) rows.add(Row.Heading(if (photos.isEmpty()) "هنوز عکسی بررسی نشده است." else "در این دسته موردی پیدا نشد.", true))
        adapter.setRows(rows)
        duplicateTab.alpha = if (showingDuplicates) 1f else 0.72f
        similarTab.alpha = if (showingDuplicates) 0.72f else 1f
        updateDeleteButton()
    }

    private fun showStatus(value: String) { status.text = value }

    private fun updateDeleteButton() {
        if (!::deleteButton.isInitialized) return
        deleteButton.text = "حذف انتخاب‌شده‌ها (${selected.size})"
        deleteButton.isEnabled = selected.isNotEmpty()
        selectionLabel.text = if (selected.isEmpty()) {
            "عکسی انتخاب نشده؛ برای حذف، عکس‌ها را علامت بزن."
        } else {
            "${selected.size} عکس برای حذف انتخاب شده"
        }
    }

    private fun requestDelete() {
        if (selected.isEmpty()) return
        pendingDelete = selected.toList()
        val request = MediaStore.createDeleteRequest(contentResolver, pendingDelete)
        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
    }

    private inner class PhotoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()
            private set
        fun setRows(value: List<Row>) { rows = value; notifyDataSetChanged() }
        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Heading) 0 else 1
        override fun getItemCount(): Int = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val label = text("", 14, true).apply { setTextColor(0xFF183A32.toInt()); setPadding(dp(10), dp(8), dp(10), dp(8)) }
                return HeaderHolder(label)
            }
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(5), dp(5), dp(5), dp(8)); setBackgroundColor(0xFFFFFFFF.toInt())
            }
            val frame = FrameLayout(this@MainActivity)
            val image = ImageView(this@MainActivity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFFE8ECE9.toInt()) }
            frame.addView(image, FrameLayout.LayoutParams(-1, dp(164)))
            val check = CheckBox(this@MainActivity).apply { buttonTintList = android.content.res.ColorStateList.valueOf(0xFF137C65.toInt()) }
            frame.addView(check, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
            val star = TextView(this@MainActivity).apply {
                text = "★ بهترین"; textSize = 11f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0xCC137C65.toInt()); setPadding(dp(5), dp(3), dp(5), dp(3)); visibility = View.GONE
            }
            frame.addView(star, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))
            card.addView(frame)
            val name = text("", 12, true).apply { setTextColor(0xFF243A34.toInt()); maxLines = 2; setPadding(dp(2), dp(5), dp(2), 0) }
            card.addView(name)
            val date = text("", 10, false).apply { setTextColor(0xFF687A74.toInt()); setPadding(dp(2), dp(2), dp(2), 0) }
            card.addView(date)
            return PhotoHolder(card, image, check, star, name, date)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Heading -> (holder as HeaderHolder).bind(row)
                is Row.Cell -> (holder as PhotoHolder).bind(row)
            }
        }
        inner class HeaderHolder(private val label: TextView) : RecyclerView.ViewHolder(label) {
            fun bind(row: Row.Heading) {
                label.text = row.label
                label.textSize = if (row.section) 14f else 15f
                label.setBackgroundColor(if (row.section) 0xFFE2EEE9.toInt() else 0xFFF3F6F4.toInt())
                label.setPadding(dp(8), dp(if (row.section) 9 else 12), dp(8), dp(7))
            }
        }
        inner class PhotoHolder(
            view: View,
            private val image: ImageView,
            private val check: CheckBox,
            private val star: TextView,
            private val name: TextView,
            private val date: TextView
        ) : RecyclerView.ViewHolder(view) {
            fun bind(row: Row.Cell) {
                val photo = row.photo
                image.tag = photo.uri.toString()
                val cachedBitmap = thumbnailCache.get(photo.uri.toString())
                image.setImageBitmap(cachedBitmap)
                star.visibility = if (row.recommended) View.VISIBLE else View.GONE
                name.text = photo.name
                date.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(photo.dateSeconds * 1000))
                check.setOnCheckedChangeListener(null)
                check.isChecked = selected.contains(photo.uri)
                check.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(photo.uri) else selected.remove(photo.uri)
                    updateDeleteButton()
                }
                if (cachedBitmap == null) {
                    imagePool.execute {
                        val bitmap = runCatching { contentResolver.loadThumbnail(photo.uri, Size(320, 320), null) }.getOrNull()
                        if (bitmap != null) {
                            thumbnailCache.put(photo.uri.toString(), bitmap)
                            runOnUiThread { if (image.tag == photo.uri.toString()) image.setImageBitmap(bitmap) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanControl.shutdownNow()
        scanPool.shutdownNow()
        imagePool.shutdownNow()
    }
}

