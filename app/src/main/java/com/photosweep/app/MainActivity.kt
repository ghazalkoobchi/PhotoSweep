package com.photosweep.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
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
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private val ink = 0xFF173831.toInt()
    private val supporting = 0xFF52645E.toInt()
    private val brandGreen = 0xFF167A63.toInt()
    private val pageColor = 0xFFF4F6F5.toInt()
    data class Photo(
        val uri: Uri,
        val name: String,
        val dateSeconds: Long,
        val modifiedSeconds: Long,
        val bytes: Long,
        val width: Int,
        val height: Int,
        val sha256: String,
        val hash: Long,
        val averageHash: Long,
        val pHash: Long,
        val quality: Double
    )
    data class PhotoGroup(val photos: List<Photo>)
    data class Results(val duplicates: List<PhotoGroup>, val similar: List<PhotoGroup>)
    private data class Meta(val uri: Uri, val name: String, val date: Long, val modified: Long, val bytes: Long, val width: Int, val height: Int)
    private sealed class Row {
        data class Heading(val label: String, val section: Boolean) : Row()
        data class Cell(val photo: Photo, val recommended: Boolean) : Row()
    }

    private val scanControl = Executors.newSingleThreadExecutor()
    private val scanPool = Executors.newFixedThreadPool(4)
    private val imagePool = Executors.newFixedThreadPool(3)
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
    private var pendingManualRefresh = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        continueAfterPermission(pendingManualRefresh)
        pendingManualRefresh = false
    }
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val removed = pendingDelete.toSet()
            photos = photos.filterNot { removed.contains(it.uri) }
            selected.removeAll(removed)
            showStatus("حذف انجام شد؛ در حال بررسی دوبارهٔ گالری…")
            scanGallery()
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
            setPadding(dp(16), dp(14), dp(16), dp(10))
            setBackgroundColor(pageColor)
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(16) + bars.left, dp(14) + bars.top, dp(16) + bars.right, dp(10) + bars.bottom)
            insets
        }
        page.addView(text("Duplio", 28, true).apply { setTextColor(ink); setPadding(0, 0, 0, dp(2)) })
        page.addView(text("عکس‌های تکراری و شات‌های مشابه را پیدا کن و خودت انتخاب کن چه چیزی حذف شود.", 14, false).apply {
            setTextColor(supporting); setPadding(0, dp(3), 0, dp(10)); setLineSpacing(dp(2).toFloat(), 1f)
        })
        status = text("برای شروع، دسترسی عکس‌ها را بده و گالری را بررسی کن.", 13, false).apply {
            setTextColor(supporting); setPadding(dp(14), dp(11), dp(14), dp(11)); setLineSpacing(dp(2).toFloat(), 1f)
            background = rounded(0xFFE2EEEA.toInt(), 12, 0)
        }
        page.addView(status)
        scanButton = Button(this).apply {
            text = "بررسی / به‌روزرسانی گالری"
            styleButton(this, 0xFFD6D8D7.toInt(), ink)
            setOnClickListener { ensurePermission(manual = true) }
        }
        page.addView(scanButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12); bottomMargin = dp(10) })
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        duplicateTab = Button(this).apply { styleButton(this, 0xFFD6D8D7.toInt(), ink); setOnClickListener { showingDuplicates = true; render() } }
        similarTab = Button(this).apply { styleButton(this, 0xFFD6D8D7.toInt(), ink); setOnClickListener { showingDuplicates = false; render() } }
        tabs.addView(duplicateTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(6) })
        tabs.addView(similarTab, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(6) })
        page.addView(tabs, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })

        list = RecyclerView(this).apply { setBackgroundColor(pageColor); itemAnimator = null; clipToPadding = false; setPadding(0, dp(2), 0, dp(4)) }
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
            setPadding(dp(4), dp(8), dp(4), dp(4))
            setBackgroundColor(pageColor)
        }
        selectionLabel = text("عکسی انتخاب نشده؛ برای حذف، عکس‌ها را علامت بزن.", 13, true).apply {
            setTextColor(supporting)
            setPadding(dp(4), 0, dp(4), dp(6))
        }
        footer.addView(selectionLabel)
        val deleteColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFFB3261D.toInt(), 0xFFD6D8D7.toInt())
        )
        val deleteTextColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0xFFFFFFFF.toInt(), 0xFF68736F.toInt())
        )
        deleteButton = Button(this).apply {
            text = "حذف انتخاب‌شده‌ها (۰)"
            isEnabled = false
            minHeight = dp(56)
            isAllCaps = false
            backgroundTintList = deleteColors
            setTextColor(deleteTextColors)
            val icon = this@MainActivity.getDrawable(R.drawable.ic_delete)?.mutate()?.apply { setTintList(deleteTextColors) }
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, null, null, null)
            compoundDrawablePadding = dp(8)
            background = rounded(0xFFB3261D.toInt(), 14, 0)
            backgroundTintList = deleteColors
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { requestDelete() }
        }
        footer.addView(deleteButton, LinearLayout.LayoutParams(-1, -2))
        page.addView(footer)
        setContentView(page)
        render()
        ensurePermission()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun styleButton(button: Button, backgroundColor: Int, textColor: Int) {
        button.isAllCaps = false
        button.textSize = 14f
        button.setTypeface(button.typeface, android.graphics.Typeface.BOLD)
        button.setTextColor(textColor)
        button.background = rounded(backgroundColor, 13, 0)
        button.setPadding(dp(12), dp(7), dp(12), dp(7))
        button.minHeight = dp(48)
        button.stateListAnimator = null
    }

    private fun text(value: String, size: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value; textSize = size.toFloat(); gravity = Gravity.RIGHT
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun ensurePermission(manual: Boolean = false) {
        pendingManualRefresh = manual
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val hasImages = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        val hasSelectedOnly = Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        if (hasImages) {
            scanGallery(manual)
            pendingManualRefresh = false
        } else if (hasSelectedOnly) {
            showFullGalleryAccessMessage()
        } else permissionLauncher.launch(permissions)
    }

    private fun continueAfterPermission(manual: Boolean) {
        if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED) {
            scanGallery(manual)
        } else if (Build.VERSION.SDK_INT >= 34 &&
            checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED) {
            showFullGalleryAccessMessage()
        } else {
            status.text = "برای بررسی همهٔ عکس‌ها، دسترسی کامل به تصاویر را فعال کن."
        }
    }

    private fun showFullGalleryAccessMessage() {
        AlertDialog.Builder(this)
            .setTitle("دسترسی کامل به عکس‌ها")
            .setMessage("در حال حاضر فقط عکس‌هایی که جداگانه انتخاب کرده‌ای در دسترس‌اند. برای پیدا کردن همهٔ عکس‌ها و اسکرین‌شات‌ها، در تنظیمات مجوز Duplio گزینهٔ «اجازهٔ دسترسی به همهٔ عکس‌ها» را انتخاب کن.")
            .setPositiveButton("رفتن به تنظیمات") { _, _ ->
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
            .setNegativeButton("بعداً", null)
            .show()
    }

    private fun scanGallery(manual: Boolean = false) {
        if (!scanButton.isEnabled) return
        val run = ++scanRun
        selected.clear()
        updateDeleteButton()
        scanButton.isEnabled = false
        status.text = "در حال فهرست‌کردن عکس‌های گالری…"
        scanControl.execute {
            try {
                val metadata = queryMetadata()
                runOnUiThread { if (run == scanRun) status.text = "در حال بررسی ${metadata.size} عکس از گالری…" }
                val completion = ExecutorCompletionService<Photo?>(scanPool)
                metadata.forEach { meta -> completion.submit(Callable { fingerprint(meta) }) }
                var done = 0
                val scanned = ArrayList<Photo>(metadata.size)
                repeat(metadata.size) {
                    val photo = completion.take().get()
                    if (photo != null) scanned.add(photo)
                    done++
                    if (done % 40 == 0 || done == metadata.size) {
                        val current = done
                        runOnUiThread { if (run == scanRun) status.text = "در حال بررسی عکس‌های گالری: $current از ${metadata.size}" }
                    }
                }
                val fresh = scanned.sortedByDescending { it.dateSeconds }
                val grouped = groupPhotos(fresh)
                runOnUiThread {
                    if (run != scanRun) return@runOnUiThread
                    photos = fresh
                    results = grouped
                    scanButton.isEnabled = true
                    val groupedPhotoCount = (grouped.duplicates + grouped.similar).flatMap { it.photos }.map { it.uri }.toSet().size
                    val ungroupedCount = (fresh.size - groupedPhotoCount).coerceAtLeast(0)
                    val unreadCount = (metadata.size - fresh.size).coerceAtLeast(0)
                    val summary = "بررسی تمام شد: ${fresh.size} از ${metadata.size} عکس گالری خوانده شد. ${grouped.duplicates.size} گروه تکراری و ${grouped.similar.size} گروه مشابه پیدا شد."
                    showStatus(summary)
                    render()
                    if (manual) {
                        val notes = buildList {
                            if (ungroupedCount > 0) add("$ungroupedCount عکس با مورد دیگری گروه نشد و در تب‌ها نمایش داده نمی‌شود.")
                            if (unreadCount > 0) add("خواندن $unreadCount عکس ممکن نشد؛ مجوز گالری را بررسی کن.")
                        }
                        val extra = if (notes.isEmpty()) "" else "\n\n${notes.joinToString("\n")}"
                        AlertDialog.Builder(this)
                            .setTitle("به‌روزرسانی تمام شد")
                            .setMessage(summary + extra)
                            .setPositiveButton("باشه", null)
                            .show()
                    }
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
        val columns = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED, MediaStore.Images.Media.SIZE, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT
        )
        val volumes = MediaStore.getExternalVolumeNames(this).ifEmpty { setOf(MediaStore.VOLUME_EXTERNAL_PRIMARY) }
        for (volume in volumes) {
        val collection = MediaStore.Images.Media.getContentUri(volume)
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
        val (hash, averageHash, pHash, quality) = calculateMetrics(bitmap, meta.width, meta.height)
        bitmap.recycle()
        val sha = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(meta.uri).use { input ->
            requireNotNull(input) { "فایل عکس قابل خواندن نیست" }
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                sha.update(buffer, 0, count)
            }
        }
        val sha256 = sha.digest().joinToString("") { "%02x".format(it) }
        Photo(meta.uri, meta.name, meta.date, meta.modified, meta.bytes, meta.width, meta.height, sha256, hash, averageHash, pHash, quality)
    }.getOrNull()

    private fun calculateMetrics(bitmap: Bitmap, width: Int, height: Int): Metrics {
        val hashBitmap = Bitmap.createScaledBitmap(bitmap, 9, 8, true)
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            if (luma(hashBitmap.getPixel(x, y)) > luma(hashBitmap.getPixel(x + 1, y))) hash = (hash shl 1) or 1L else hash = hash shl 1
        }
        hashBitmap.recycle()
        val averageBitmap = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val averagePixels = IntArray(64)
        var averageSum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val index = y * 8 + x
            val value = luma(averageBitmap.getPixel(x, y))
            averagePixels[index] = value
            averageSum += value
        }
        averageBitmap.recycle()
        val averageLevel = (averageSum / 64).toInt()
        var averageHash = 0L
        averagePixels.forEach { value -> averageHash = (averageHash shl 1) or if (value >= averageLevel) 1L else 0L }
        val pHash = calculatePHash(bitmap)
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
        return Metrics(hash, averageHash, pHash, quality)
    }

    private data class Metrics(val dHash: Long, val averageHash: Long, val pHash: Long, val quality: Double)

    private fun calculatePHash(bitmap: Bitmap): Long {
        val size = 32
        val gray = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val values = Array(size) { y -> IntArray(size) { x -> luma(gray.getPixel(x, y)) } }
        gray.recycle()
        val coeff = Array(8) { u -> DoubleArray(8) { v ->
            var sum = 0.0
            for (y in 0 until size) for (x in 0 until size) {
                sum += values[y][x] * kotlin.math.cos((2 * x + 1) * u * Math.PI / (2 * size)) *
                    kotlin.math.cos((2 * y + 1) * v * Math.PI / (2 * size))
            }
            sum
        } }
        val frequencies = DoubleArray(63)
        var position = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (x != 0 || y != 0) frequencies[position++] = coeff[y][x]
        }
        frequencies.sort()
        val median = frequencies[31]
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) hash = (hash shl 1) or if (coeff[y][x] >= median) 1L else 0L
        return hash
    }

    private fun luma(pixel: Int): Int = ((pixel shr 16 and 255) * 299 + (pixel shr 8 and 255) * 587 + (pixel and 255) * 114) / 1000

    private fun groupPhotos(items: List<Photo>): Results {
        val exactBuckets = items.distinctBy { it.uri }.groupBy { it.sha256 }
        val duplicates = exactBuckets.values.filter { it.size > 1 }.map { PhotoGroup(it.sortedByDescending { p -> p.quality }) }
            .sortedByDescending { it.photos.size }
        val duplicateUris = duplicates.flatMap { it.photos }.map { it.uri }.toHashSet()
        val candidates = items.filterNot { duplicateUris.contains(it.uri) }
        if (candidates.size < 2) return Results(duplicates, emptyList())
        val similar = ArrayList<PhotoGroup>()
        val assigned = HashSet<Uri>()
        val ordered = candidates.sortedByDescending { it.quality }
        val pHashBands = Array(8) { HashMap<Int, MutableList<Photo>>() }
        ordered.forEach { photo ->
            for (band in 0 until 8) {
                val key = ((photo.pHash ushr (band * 8)) and 255L).toInt()
                pHashBands[band].getOrPut(key) { ArrayList() }.add(photo)
            }
        }
        for (seed in ordered) {
            if (!assigned.add(seed.uri)) continue
            val cluster = arrayListOf(seed)
            val possible = LinkedHashMap<Uri, Photo>()
            for (band in 0 until 8) {
                val key = ((seed.pHash ushr (band * 8)) and 255L).toInt()
                pHashBands[band][key]?.forEach { possible[it.uri] = it }
            }
            for (candidate in possible.values.sortedByDescending { it.quality }) {
                if (candidate.uri in assigned) continue
                if (cluster.all { areVisuallySimilar(it, candidate) }) {
                    cluster.add(candidate)
                    assigned.add(candidate.uri)
                }
            }
            if (cluster.size > 1) similar.add(PhotoGroup(cluster)) else assigned.remove(seed.uri)
        }
        similar.sortByDescending { it.photos.size }
        return Results(duplicates, similar)
    }

    private fun areVisuallySimilar(a: Photo, b: Photo): Boolean {
        val pDistance = java.lang.Long.bitCount(a.pHash xor b.pHash)
        val dDistance = java.lang.Long.bitCount(a.hash xor b.hash)
        val sameShape = a.width > 0 && a.height > 0 && b.width > 0 && b.height > 0 &&
            abs((a.width.toDouble() / a.height) / (b.width.toDouble() / b.height) - 1.0) <= 0.08
        val closeInTime = abs(a.dateSeconds - b.dateSeconds) <= 15 * 60
        val strictVisualMatch = pDistance <= 6 && dDistance <= 12
        val burstMatch = closeInTime && pDistance <= 3 && dDistance <= 7
        return sameShape && (strictVisualMatch || burstMatch)
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
        val selectedTab = rounded(brandGreen, 13, 0)
        val idleTab = rounded(0xFFD6D8D7.toInt(), 13, 0)
        duplicateTab.background = if (showingDuplicates) selectedTab else idleTab
        duplicateTab.setTextColor(if (showingDuplicates) 0xFFFFFFFF.toInt() else ink)
        similarTab.background = if (!showingDuplicates) selectedTab else idleTab
        similarTab.setTextColor(if (!showingDuplicates) 0xFFFFFFFF.toInt() else ink)
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
                val label = text("", 14, true).apply { setTextColor(ink); setPadding(dp(10), dp(8), dp(10), dp(8)) }
                return HeaderHolder(label)
            }
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(6), dp(6), dp(6), dp(9))
                background = rounded(0xFFFFFFFF.toInt(), 14, 0xFFE1E7E4.toInt())
                layoutParams = RecyclerView.LayoutParams(-1, -2).apply {
                    setMargins(dp(4), dp(5), dp(4), dp(5))
                }
            }
            val frame = FrameLayout(this@MainActivity)
            val image = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(0xFFE8ECE9.toInt())
                background = rounded(0xFFE8ECE9.toInt(), 9, 0)
                clipToOutline = true
            }
            frame.addView(image, FrameLayout.LayoutParams(-1, dp(164)))
            val check = CheckBox(this@MainActivity).apply {
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(0xFF167A63.toInt(), 0xFF59645F.toInt())
                )
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = rounded(0xCCFFFFFF.toInt(), 12, 0)
                scaleX = 0.92f; scaleY = 0.92f
            }
            frame.addView(check, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.START).apply {
                leftMargin = dp(7); topMargin = dp(7)
            })
            val star = TextView(this@MainActivity).apply {
                text = "★ بهترین"; textSize = 11f; setTextColor(0xFF167A63.toInt())
                background = rounded(0xFFDCEFE8.toInt(), 9, 0)
                setPadding(dp(8), dp(5), dp(8), dp(5)); visibility = View.GONE
            }
            frame.addView(star, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
                rightMargin = dp(7); topMargin = dp(7)
            })
            card.addView(frame)
            val name = text("", 12, true).apply { setTextColor(ink); maxLines = 2; setPadding(dp(2), dp(8), dp(2), 0); minHeight = dp(42); gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL }
            card.addView(name)
            val date = text("", 10, false).apply { setTextColor(supporting); setPadding(dp(2), dp(3), dp(2), 0) }
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
                label.background = if (row.section) rounded(0xFFE2EEEA.toInt(), 12, 0) else rounded(pageColor, 10, 0)
                label.setPadding(dp(11), dp(if (row.section) 10 else 12), dp(11), dp(8))
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
                image.setImageDrawable(null)
                star.visibility = if (row.recommended) View.VISIBLE else View.GONE
                name.text = photo.name
                date.text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(photo.dateSeconds * 1000))
                check.setOnCheckedChangeListener(null)
                check.isChecked = selected.contains(photo.uri)
                (itemView as? LinearLayout)?.background = rounded(
                    0xFFFFFFFF.toInt(), 14,
                    if (check.isChecked) 0xFF167A63.toInt() else 0xFFE1E7E4.toInt(),
                    if (check.isChecked) 2 else 1
                )
                check.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(photo.uri) else selected.remove(photo.uri)
                    (itemView as? LinearLayout)?.background = rounded(
                        0xFFFFFFFF.toInt(), 14,
                        if (checked) 0xFF167A63.toInt() else 0xFFE1E7E4.toInt(),
                        if (checked) 2 else 1
                    )
                    updateDeleteButton()
                }
                imagePool.execute {
                    val bitmap = runCatching { contentResolver.loadThumbnail(photo.uri, Size(320, 320), null) }.getOrNull()
                    if (bitmap != null) {
                        runOnUiThread { if (image.tag == photo.uri.toString()) image.setImageBitmap(bitmap) }
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

