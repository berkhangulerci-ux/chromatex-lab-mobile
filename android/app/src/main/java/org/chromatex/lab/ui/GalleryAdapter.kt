package org.chromatex.lab.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import org.chromatex.lab.R
import org.chromatex.lab.data.GalleryItem
import org.chromatex.lab.databinding.ItemGalleryBinding
import java.io.File
import java.util.concurrent.Executors

class GalleryAdapter(
    private val onEdit: (GalleryItem) -> Unit,
    private val onDelete: (GalleryItem) -> Unit,
    private val onOpen: (GalleryItem) -> Unit,
) : RecyclerView.Adapter<GalleryAdapter.VH>() {

    private val items = mutableListOf<GalleryItem>()
    private val decodeExecutor = Executors.newFixedThreadPool(2)

    fun shutdown() {
        decodeExecutor.shutdownNow()
    }

    fun submit(list: List<GalleryItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemGalleryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun onViewRecycled(holder: VH) {
        holder.clearThumb()
        super.onViewRecycled(holder)
    }

    inner class VH(private val b: ItemGalleryBinding) : RecyclerView.ViewHolder(b.root) {
        private var bindToken = 0L

        fun clearThumb() {
            bindToken++
            b.thumb.setImageDrawable(null)
            b.thumb.tag = null
        }

        fun bind(item: GalleryItem) {
            val purpose = item.json.optJSONObject("capture")?.optString("purpose") ?: "?"
            val label = item.json.optString("label").ifBlank { "—" }
            val state = item.json.optString("fabric_state").ifBlank { "—" }
            val type = item.json.optString("fabric_type").ifBlank { "—" }
            val color = item.json.optString("fabric_color").ifBlank { "—" }
            b.title.text = "$purpose · $label"
            b.subtitle.text = "$state | $type | $color\n${item.json.optString("created_at")}"
            b.syncChip.text = if (item.synced) "AKTARILDI" else "BEKLİYOR"
            b.syncChip.setBackgroundColor(
                ContextCompat.getColor(
                    b.root.context,
                    if (item.synced) R.color.chip_synced else R.color.chip_pending,
                ),
            )
            b.root.setOnClickListener { onOpen(item) }
            b.btnEdit.setOnClickListener { onEdit(item) }
            b.btnDelete.setOnClickListener { onDelete(item) }

            val token = ++bindToken
            val path = item.imageFile?.absolutePath
            b.thumb.setImageDrawable(null)
            b.thumb.tag = path
            if (path == null || decodeExecutor.isShutdown) return

            decodeExecutor.execute {
                val bmp = decodeThumb(File(path), maxSidePx = 192)
                b.thumb.post {
                    if (bindToken != token || b.thumb.tag != path) {
                        bmp?.recycle()
                        return@post
                    }
                    if (bmp != null) b.thumb.setImageBitmap(bmp) else b.thumb.setImageDrawable(null)
                }
            }
        }
    }

    companion object {
        fun decodeThumb(file: File, maxSidePx: Int): Bitmap? {
            if (!file.exists()) return null
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                var sample = 1
                val w = bounds.outWidth.coerceAtLeast(1)
                val h = bounds.outHeight.coerceAtLeast(1)
                while (w / sample > maxSidePx || h / sample > maxSidePx) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample.coerceAtLeast(1)
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                BitmapFactory.decodeFile(file.absolutePath, opts)
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
    }
}
