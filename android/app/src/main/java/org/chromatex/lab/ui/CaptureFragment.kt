package org.chromatex.lab.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.chromatex.lab.CaptureActivity
import org.chromatex.lab.R
import org.chromatex.lab.data.Catalog
import org.chromatex.lab.data.SceneRecordStore
import org.chromatex.lab.data.SessionPrefs
import org.chromatex.lab.data.UploadClient
import org.chromatex.lab.databinding.FragmentCaptureBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CaptureFragment : Fragment() {
    private var _b: FragmentCaptureBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentCaptureBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        bindChoiceSpinner(b.spinnerFabricState, Catalog.fabricStateChoices, SessionPrefs.fabricState(requireContext()))
        bindChoiceSpinner(b.spinnerLabel, Catalog.labelChoices, SessionPrefs.label(requireContext()))
        bindChoiceSpinner(b.spinnerFabricType, Catalog.fabricTypeChoices, SessionPrefs.fabricType(requireContext()))
        bindChoiceSpinner(b.spinnerFabricColor, Catalog.fabricColorChoices, SessionPrefs.fabricColor(requireContext()))

        b.btnCapture.setOnClickListener { confirmAndOpenCamera() }
        b.btnUpload.setOnClickListener { upload() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bindChoiceSpinner(spinner: Spinner, choices: List<Catalog.Choice>, selectedCode: String) {
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            choices.map { it.title },
        )
        spinner.setSelection(Catalog.indexOfCode(choices, selectedCode))
    }

    private fun selectedCode(spinner: Spinner, choices: List<Catalog.Choice>): String =
        Catalog.codeAt(choices, spinner.selectedItemPosition)

    private fun refresh() {
        val s = SceneRecordStore.stats(requireContext())
        b.captureStatus.text = "Bekleyen: ${s.pending}  ·  Toplam çekim: ${s.total}"
        val pending = s.pending > 0
        b.captureStatus.setBackgroundResource(
            if (pending) R.drawable.bg_status_warn else R.drawable.bg_status_ok,
        )
        b.captureStatus.setTextColor(
            ContextCompat.getColor(requireContext(), if (pending) R.color.warn else R.color.ok),
        )
    }

    private fun confirmAndOpenCamera() {
        val state = selectedCode(b.spinnerFabricState, Catalog.fabricStateChoices)
        val label = selectedCode(b.spinnerLabel, Catalog.labelChoices)
        val type = selectedCode(b.spinnerFabricType, Catalog.fabricTypeChoices)
        val color = selectedCode(b.spinnerFabricColor, Catalog.fabricColorChoices)
        val orgu = b.inputOrguKodu.text?.toString()?.trim().orEmpty()
        val sip = b.inputSipNo.text?.toString()?.trim().orEmpty()
        val isemri = b.inputIsemri.text?.toString()?.trim().orEmpty()
        val makina = b.inputMakina.text?.toString()?.trim().orEmpty()
        val partiRaw = b.inputParti.text?.toString()?.trim().orEmpty()
        val partiErr = Catalog.validatePartiNo(partiRaw)
        if (partiErr != null) {
            b.inputParti.error = partiErr
            Toast.makeText(requireContext(), partiErr, Toast.LENGTH_LONG).show()
            return
        }
        val parti = Catalog.normalizePartiNo(partiRaw)
        if (parti != partiRaw) b.inputParti.setText(parti)

        SessionPrefs.setFabricState(requireContext(), state)
        SessionPrefs.setLabel(requireContext(), label)
        SessionPrefs.setFabricType(requireContext(), type)
        SessionPrefs.setFabricColor(requireContext(), color)

        val summary = buildString {
            appendLine("Durum: ${Catalog.titleOf(Catalog.fabricStateChoices, state)}")
            appendLine("Etiket: ${Catalog.titleOf(Catalog.labelChoices, label)}  ($label)")
            appendLine("Cins: ${Catalog.titleOf(Catalog.fabricTypeChoices, type)}")
            appendLine("Renk: ${Catalog.titleOf(Catalog.fabricColorChoices, color)}")
            if (orgu.isNotBlank()) appendLine("Örgü: $orgu")
            if (sip.isNotBlank()) appendLine("Sipariş: $sip")
            if (isemri.isNotBlank()) appendLine("İş emri: $isemri")
            if (makina.isNotBlank()) appendLine("Makina: $makina")
            if (parti.isNotBlank()) appendLine("Parti: $parti")
            appendLine()
            append("Bu meta çekim kaydına yazılacak. Devam?")
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Meta onay")
            .setMessage(summary)
            .setPositiveButton("Kamerayı aç") { _, _ ->
                openCamera(state, label, type, color, orgu, sip, isemri, makina, parti)
            }
            .setNegativeButton("Düzelt", null)
            .show()
    }

    private fun openCamera(
        state: String,
        label: String,
        type: String,
        color: String,
        orgu: String,
        sip: String,
        isemri: String,
        makina: String,
        parti: String,
    ) {
        val intent = Intent(requireContext(), CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_OPERATOR, SessionPrefs.operator(requireContext()))
            putExtra(CaptureActivity.EXTRA_BATCH, SessionPrefs.batchId(requireContext()))
            putExtra(CaptureActivity.EXTRA_FABRIC_STATE, state)
            putExtra(CaptureActivity.EXTRA_LABEL, label)
            putExtra(CaptureActivity.EXTRA_FABRIC_TYPE, type)
            putExtra(CaptureActivity.EXTRA_FABRIC_COLOR, color)
            putExtra(CaptureActivity.EXTRA_ORGU, orgu)
            putExtra(CaptureActivity.EXTRA_SIP, sip)
            putExtra(CaptureActivity.EXTRA_ISEMRI, isemri)
            putExtra(CaptureActivity.EXTRA_MAKINA, makina)
            putExtra(CaptureActivity.EXTRA_PARTI, parti)
        }
        startActivity(intent)
    }

    private fun upload() {
        val server = SessionPrefs.serverUrl(requireContext())
        if (server.isBlank()) {
            Toast.makeText(requireContext(), "Ayarlar’dan sunucu adresini girin", Toast.LENGTH_LONG).show()
            return
        }
        b.captureStatus.text = "Gönderiliyor…"
        viewLifecycleOwner.lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) { UploadClient.uploadPending(requireContext(), server) }
            Toast.makeText(requireContext(), "OK=${r.ok} FAIL=${r.fail} ${r.message}", Toast.LENGTH_LONG).show()
            refresh()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
