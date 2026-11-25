package org.chromatex.lab.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import org.chromatex.lab.data.Catalog
import org.chromatex.lab.data.GalleryItem
import org.chromatex.lab.data.SceneRecordStore
import org.chromatex.lab.data.SessionPrefs
import org.chromatex.lab.data.UploadClient
import org.chromatex.lab.databinding.FragmentGalleryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GalleryFragment : Fragment() {
    private var _b: FragmentGalleryBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: GalleryAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentGalleryBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = GalleryAdapter(
            onEdit = { editItem(it) },
            onDelete = { deleteItem(it) },
            onOpen = { openItem(it) },
        )
        b.recyclerGallery.layoutManager = LinearLayoutManager(requireContext())
        b.recyclerGallery.setHasFixedSize(true)
        b.recyclerGallery.adapter = adapter
        b.btnRefreshGallery.setOnClickListener { refresh() }
        b.btnUploadGallery.setOnClickListener { upload() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (_b != null) refresh()
    }

    private fun refresh() {
        val binding = _b ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                SceneRecordStore.listGallery(requireContext())
            }
            if (_b == null) return@launch
            adapter.submit(list)
            val pending = list.count { !it.synced }
            binding.gallerySummary.text = "${list.size} kayıt · $pending aktarılmadı"
        }
    }

    private fun upload() {
        val server = SessionPrefs.serverUrl(requireContext())
        if (server.isBlank()) {
            Toast.makeText(requireContext(), "Ayarlar’dan sunucu adresini girin", Toast.LENGTH_LONG).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { UploadClient.uploadPending(requireContext(), server) }
            Toast.makeText(requireContext(), "OK=${r.ok} FAIL=${r.fail} ${r.message}", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    private fun deleteItem(item: GalleryItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Sil?")
            .setMessage(item.json.optString("record_id"))
            .setPositiveButton("Sil") { _, _ ->
                SceneRecordStore.deleteRecord(requireContext(), item)
                refresh()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    private fun openItem(item: GalleryItem) {
        val img = item.imageFile
        if (img == null || !img.exists()) {
            Toast.makeText(requireContext(), "Görüntü dosyası yok", Toast.LENGTH_SHORT).show()
            return
        }
        // Lenovo vb. cihazlarda harici görüntüleyici yok — uygulama içi önizleme
        viewLifecycleOwner.lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                GalleryAdapter.decodeThumb(img, maxSidePx = 1600)
            }
            if (!isAdded || _b == null) {
                bmp?.recycle()
                return@launch
            }
            if (bmp == null) {
                Toast.makeText(requireContext(), "Görüntü açılamadı", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val iv = ImageView(requireContext()).apply {
                setImageBitmap(bmp)
                adjustViewBounds = true
                setPadding(16, 16, 16, 16)
            }
            AlertDialog.Builder(requireContext())
                .setTitle(img.name)
                .setView(iv)
                .setPositiveButton("Kapat", null)
                .setNegativeButton("Paylaş") { _, _ -> shareImage(img) }
                .setNeutralButton("Harici aç") { _, _ -> openExternal(img) }
                .setOnDismissListener {
                    iv.setImageDrawable(null)
                    if (!bmp.isRecycled) bmp.recycle()
                }
                .show()
        }
    }

    private fun shareImage(img: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                img,
            )
            val intent = Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Görüntüyü paylaş"))
        } catch (e: Exception) {
            Log.w(TAG, "shareImage failed", e)
            Toast.makeText(requireContext(), "Paylaşılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openExternal(img: java.io.File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                img,
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Görüntüyü aç"))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "Görüntüleyici uygulaması yok", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.w(TAG, "openExternal failed", e)
            Toast.makeText(requireContext(), "Açılamadı: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "GalleryFragment"
    }

    private fun editItem(item: GalleryItem) {
        val ctx = requireContext()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        fun spinChoices(choices: List<Catalog.Choice>, selectedCode: String): Spinner {
            val s = Spinner(ctx)
            s.adapter = ArrayAdapter(
                ctx,
                android.R.layout.simple_spinner_dropdown_item,
                choices.map { it.title },
            )
            s.setSelection(Catalog.indexOfCode(choices, selectedCode))
            layout.addView(s)
            return s
        }
        val state = spinChoices(Catalog.fabricStateChoices, item.json.optString("fabric_state"))
        val label = spinChoices(Catalog.labelChoices, item.json.optString("label"))
        val type = spinChoices(Catalog.fabricTypeChoices, item.json.optString("fabric_type"))
        val color = spinChoices(Catalog.fabricColorChoices, item.json.optString("fabric_color"))
        val existingParti = item.json.optJSONObject("fabric_codes")?.optString("parti_no").orEmpty()
        val partiInput = EditText(ctx).apply {
            hint = "Parti No (örn. P1234567-01)"
            setText(existingParti)
        }
        layout.addView(partiInput)
        val note = EditText(ctx).apply {
            hint = "Not"
            setText(item.json.optString("label_note"))
        }
        layout.addView(note)

        AlertDialog.Builder(ctx)
            .setTitle("Bilgi düzelt")
            .setView(layout)
            .setPositiveButton("Kaydet") { _, _ ->
                val partiRaw = partiInput.text.toString().trim()
                val partiErr = Catalog.validatePartiNo(partiRaw)
                if (partiErr != null) {
                    Toast.makeText(ctx, partiErr, Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val partiClean = Catalog.normalizePartiNo(partiRaw)
                SceneRecordStore.updateRecordJson(ctx, item) { json ->
                    json.put(
                        "fabric_state",
                        Catalog.codeAt(Catalog.fabricStateChoices, state.selectedItemPosition),
                    )
                    json.put(
                        "label",
                        Catalog.codeAt(Catalog.labelChoices, label.selectedItemPosition),
                    )
                    json.put(
                        "fabric_type",
                        Catalog.codeAt(Catalog.fabricTypeChoices, type.selectedItemPosition),
                    )
                    json.put(
                        "fabric_color",
                        Catalog.codeAt(Catalog.fabricColorChoices, color.selectedItemPosition),
                    )
                    val codes = json.optJSONObject("fabric_codes") ?: org.json.JSONObject()
                    codes.put("parti_no", partiClean)
                    json.put("fabric_codes", codes)
                    json.put("label_note", note.text.toString())
                    json.put("uploaded", false) // düzeltilince yeniden aktar
                }
                // pending kuyruğuna güncel JSON'u yaz (zaten varsa da güncelle)
                val pending = java.io.File(SceneRecordStore.pendingDir(ctx), item.recordFile.name)
                val p = org.json.JSONObject(item.recordFile.readText())
                p.put("local_image_path", item.imageFile?.absolutePath)
                p.put("uploaded", false)
                pending.writeText(p.toString(2))
                refresh()
            }
            .setNegativeButton("İptal", null)
            .show()
    }

    override fun onDestroyView() {
        if (::adapter.isInitialized) {
            adapter.shutdown()
        }
        super.onDestroyView()
        _b = null
    }
}
