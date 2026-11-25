package org.chromatex.lab.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import org.chromatex.lab.data.SceneRecordStore
import org.chromatex.lab.databinding.FragmentStatsBinding

class StatsFragment : Fragment() {
    private var _b: FragmentStatsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentStatsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.btnDeleteUploaded.setOnClickListener {
            val n = SceneRecordStore.deleteUploaded(requireContext())
            Toast.makeText(requireContext(), "$n aktarılmış kayıt silindi", Toast.LENGTH_SHORT).show()
            refresh()
        }
        b.btnClearAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Tableti boşalt?")
                .setMessage("Tüm çekimler silinir.")
                .setPositiveButton("Sil") { _, _ ->
                    val n = SceneRecordStore.clearAll(requireContext())
                    Toast.makeText(requireContext(), "$n silindi", Toast.LENGTH_SHORT).show()
                    refresh()
                }
                .setNegativeButton("İptal", null)
                .show()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val s = SceneRecordStore.stats(requireContext())
        b.statsText.text = """
            Toplam çekim: ${s.total}
            Aktarılan: ${s.uploaded}
            Bekleyen: ${s.pending}
            Numune çekimi: ${s.sceneShots}
            Eski kalib. kayıt: ${s.calibShots}
            Boş alan: ${s.freeMb} MB / ${s.totalMb} MB
        """.trimIndent()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
