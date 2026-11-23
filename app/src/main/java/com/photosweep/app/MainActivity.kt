package com.photosweep.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var deleteButton: Button
    private lateinit var status: TextView
    private val selected = linkedSetOf<Uri>()
    private var groups: List<List<Photo>> = emptyList()
    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        selected.clear()
        if (result.resultCode == Activity.RESULT_OK) Toast.makeText(this, "حذف انجام شد", Toast.LENGTH_SHORT).show()
        scan()
    }
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) scan() else showMessage("برای بررسی گالری، اجازهٔ دسترسی به عکس‌ها لازم است.")
    }

    data class Photo(val uri: Uri, val name: String, val dateSeconds: Long, val hash: Long)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 18, 20, 12); setBackgroundColor(0xFFF3F6F4.toInt()) }
        val title = text("پاک‌عکس", 28, true).apply { setTextColor(0xFF183A32.toInt()) }
        page.addView(title)
        page.addView(text("پیدا کردن عکس‌های تکراری و شات‌های مشابه روی گوشی", 15, false).apply { setTextColor(0xFF50645E.toInt()); setPadding(0, 4, 0, 14) })
        val info = text("پردازش روی خود گوشی انجام می‌شود. هیچ عکسی آپلود نمی‌شود. عکس‌ها را خودتان برای حذف انتخاب می‌کنید.", 14, false)
        info.setTextColor(0xFF31584E.toInt()); info.setPadding(14, 12, 14, 12); info.setBackgroundColor(0xFFE2EEE9.toInt()); page.addView(info)
        scanButton = Button(this).apply { text = "اسکن گالری"; setOnClickListener { ensurePermission() } }
        page.addView(scanButton, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12 })
        status = text("برای شروع، گالری را اسکن کنید.", 14, false).apply { setPadding(2, 10, 2, 8); setTextColor(0xFF50645E.toInt()) }
        page.addView(status)
        deleteButton = Button(this).apply { text = "حذف انتخاب‌شده‌ها (۰)"; isEnabled = false; setOnClickListener { requestDelete() } }
        page.addView(deleteButton, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8 })
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(page)
        ensurePermission()
    }

    private fun text(value: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        text = value; textSize = size; gravity = Gravity.RIGHT
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun ensurePermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (permissions.any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) scan() else permissionLauncher.launch(permissions)
    }

    private fun scan() {
        selected.clear()
        updateDeleteLabel()
        scanButton.isEnabled = false; deleteButton.isEnabled = false
        body.removeAllViews(); body.addView(ProgressBar(this).apply { isIndeterminate = true })
        status.text = "در حال بررسی عکس‌ها…"
        worker.execute {
            val photos = readPhotos()
            val found = findGroups(photos)
            runOnUiThread {
                scanButton.isEnabled = true
                groups = found
                val total = found.sumOf { it.size }
                status.text = if (found.isEmpty()) "عکس مشابهی پیدا نشد. (${photos.size} عکس بررسی شد)" else "${found.size} گروه پیدا شد؛ ${total} عکس در این گروه‌ها. موردهای موردنظر را انتخاب کنید."
                renderGroups()
            }
        }
    }

    private fun readPhotos(): List<Photo> {
        val result = mutableListOf<Photo>()
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val columns = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.DATE_ADDED)
        contentResolver.query(collection, columns, null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                val hash = runCatching { differenceHash(uri) }.getOrNull() ?: continue
                result.add(Photo(uri, cursor.getString(nameCol) ?: "عکس", cursor.getLong(dateCol), hash))
            }
        }
        return result
    }

    private fun differenceHash(uri: Uri): Long {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val source = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: error("decode")
        val tiny = Bitmap.createScaledBitmap(source, 9, 8, true)
        if (tiny !== source) source.recycle()
        var hash = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val a = tiny.getPixel(x, y); val b = tiny.getPixel(x + 1, y)
            val lumA = ((a shr 16 and 255) * 299 + (a shr 8 and 255) * 587 + (a and 255) * 114) / 1000
            val lumB = ((b shr 16 and 255) * 299 + (b shr 8 and 255) * 587 + (b and 255) * 114) / 1000
            hash = (hash shl 1) or if (lumA > lumB) 1L else 0L
        }
        tiny.recycle()
        return hash
    }

    private fun findGroups(photos: List<Photo>): List<List<Photo>> {
        if (photos.size < 2) return emptyList()
        val parent = IntArray(photos.size) { it }
        fun root(i: Int): Int { var n = i; while (parent[n] != n) { parent[n] = parent[parent[n]]; n = parent[n] }; return n }
        fun join(a: Int, b: Int) { val ra = root(a); val rb = root(b); if (ra != rb) parent[rb] = ra }
        val exact = HashMap<Long, Int>()
        photos.forEachIndexed { i, p -> exact.putIfAbsent(p.hash, i)?.let { join(i, it) } }
        val byTime = photos.indices.sortedBy { photos[it].dateSeconds }
        for (pos in byTime.indices) {
            val i = byTime[pos]; var next = pos + 1
            while (next < byTime.size) {
                val j = byTime[next]
                val gap = photos[j].dateSeconds - photos[i].dateSeconds
                if (gap > 15 * 60) break
                if (java.lang.Long.bitCount(photos[i].hash xor photos[j].hash) <= 7) join(i, j)
                next++
            }
        }
        return photos.indices.groupBy { root(it) }.values.filter { it.size > 1 }.map { ids -> ids.map { photos[it] } }
            .sortedByDescending { it.size }
    }

    private fun renderGroups() {
        body.removeAllViews()
        groups.forEachIndexed { index, group ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 10, 12, 10); setBackgroundColor(-1) }
            val h = text("گروه ${index + 1} • ${group.size} عکس", 16, true).apply { setTextColor(0xFF183A32.toInt()); setPadding(0, 2, 0, 8) }
            card.addView(h)
            group.forEach { photo -> card.addView(photoRow(photo)) }
            body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12 })
        }
    }

    private fun photoRow(photo: Photo): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 5, 0, 5) }
        val image = ImageView(this).apply { setBackgroundColor(0xFFE8ECE9.toInt()); scaleType = ImageView.ScaleType.CENTER_CROP }
        row.addView(image, LinearLayout.LayoutParams(76, 76))
        worker.execute {
            val bmp = runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                contentResolver.openInputStream(photo.uri).use { BitmapFactory.decodeStream(it, null, opts) }
            }.getOrNull()
            if (bmp != null) runOnUiThread { image.setImageBitmap(bmp) }
        }
        val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(10, 0, 0, 0) }
        details.addView(text(photo.name, 14, true).apply { maxLines = 2; setTextColor(0xFF243A34.toInt()) })
        details.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(photo.dateSeconds * 1000)), 12, false).apply { setTextColor(0xFF687A74.toInt()); setPadding(0, 4, 0, 0) })
        row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val check = CheckBox(this).apply {
            isChecked = selected.contains(photo.uri)
            setOnCheckedChangeListener { _, checked -> if (checked) selected.add(photo.uri) else selected.remove(photo.uri); updateDeleteLabel() }
        }
        row.addView(check)
        return row
    }

    private fun updateDeleteLabel() {
        deleteButton.text = "حذف انتخاب‌شده‌ها (${selected.size})"
        deleteButton.isEnabled = selected.isNotEmpty()
    }

    private fun requestDelete() {
        if (selected.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 30) {
            val request = MediaStore.createDeleteRequest(contentResolver, selected.toList())
            deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } else Toast.makeText(this, "این نسخهٔ اندروید پشتیبانی نمی‌شود", Toast.LENGTH_LONG).show()
    }

    private fun showMessage(message: String) {
        status.text = message
        body.removeAllViews()
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdown() }
}



