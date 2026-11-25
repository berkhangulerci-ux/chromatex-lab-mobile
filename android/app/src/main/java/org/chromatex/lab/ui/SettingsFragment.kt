package org.chromatex.lab.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import org.chromatex.lab.R
import org.chromatex.lab.data.CalibrationStatus
import org.chromatex.lab.data.Catalog
import org.chromatex.lab.data.DeviceId
import org.chromatex.lab.data.SessionPrefs
import org.chromatex.lab.data.UpdateClient
import org.chromatex.lab.data.UploadClient
import org.chromatex.lab.databinding.FragmentSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private var _b: FragmentSettingsBinding? = null
    private val b get() = _b!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentSettingsBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.versionText.text =
            "v${Catalog.APP_VERSION} (${Catalog.APP_VERSION_CODE})"
        b.deviceIdText.text = "Cihaz ID: ${DeviceId.get(requireContext())}"
        b.inputServer.setText(SessionPrefs.serverUrl(requireContext()))
        b.inputOperator.setText(SessionPrefs.operator(requireContext()))
        b.inputBatch.setText(SessionPrefs.batchId(requireContext()))
        refreshSpectro()

        b.btnSaveSettings.setOnClickListener {
            save()
            registerNow()
            refreshSpectro()
            Toast.makeText(requireContext(), "Kaydedildi", Toast.LENGTH_SHORT).show()
            refreshWifi()
        }
        b.btnPing.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val url = b.inputServer.text?.toString().orEmpty()
                val ok = withContext(Dispatchers.IO) { UploadClient.ping(url) }
                b.wifiStatus.text = if (ok) "Sunucu OK: $url" else "Sunucuya ulaşılamadı: $url"
                Toast.makeText(requireContext(), b.wifiStatus.text, Toast.LENGTH_SHORT).show()
            }
        }
        b.btnPrivacyPolicy.setOnClickListener {
            showPrivacyPolicyDialog()
        }
        if (org.chromatex.lab.BuildConfig.IS_PLAY_STORE) {
            b.btnCheckUpdate.visibility = View.GONE
        } else {
            b.btnCheckUpdate.setOnClickListener { checkUpdate() }
        }
        refreshWifi()
        registerNow()
    }

    private fun save() {
        SessionPrefs.setServerUrl(requireContext(), b.inputServer.text?.toString().orEmpty())
        SessionPrefs.setOperator(requireContext(), b.inputOperator.text?.toString().orEmpty())
        SessionPrefs.setBatchId(requireContext(), b.inputBatch.text?.toString().orEmpty())
    }

    private fun registerNow() {
        val url = SessionPrefs.serverUrl(requireContext())
        if (url.isBlank()) return
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val synced = withContext(Dispatchers.IO) {
                UploadClient.syncFromHub(appCtx, url)
            }
            val ok = withContext(Dispatchers.IO) {
                UpdateClient.registerDevice(url, DeviceId.devicePayload(appCtx))
            }
            if (_b == null) return@launch
            if (synced) {
                b.inputBatch.setText(SessionPrefs.batchId(appCtx))
            }
            if (ok) b.wifiStatus.append(" · cihaz kayıtlı")
        }
    }

    private fun checkUpdate() {
        save()
        val url = SessionPrefs.serverUrl(requireContext())
        b.wifiStatus.text = "Sürüm kontrol ediliyor…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    UpdateClient.check(url, Catalog.APP_VERSION_CODE)
                }
                if (info == null) {
                    b.wifiStatus.text = "Sürüm bilgisi alınamadı"
                    return@launch
                }
                if (!info.hasUpdate) {
                    b.wifiStatus.text = "Güncel: ${info.versionName} (${info.versionCode})"
                    Toast.makeText(requireContext(), "Zaten güncel", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                b.wifiStatus.text = "Yeni sürüm ${info.versionName} indiriliyor…"
                val apk = withContext(Dispatchers.IO) {
                    UpdateClient.downloadApk(requireContext(), info.downloadUrl)
                }
                UpdateClient.installApk(requireContext(), apk)
                Toast.makeText(requireContext(), "Kurulumu onaylayın", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                b.wifiStatus.text = "Güncelleme hatası: ${e.message}"
            }
        }
    }

    private fun refreshSpectro() {
        val info = CalibrationStatus.load(requireContext())
        val batch = SessionPrefs.batchId(requireContext()).ifBlank { Catalog.CALIB_BATCH_EXPECTED }
        val ok = info.present
        b.spectroStatus.text = if (ok) {
            "Spectro batch: ${info.batchId ?: batch} · ${info.patchCount} yama (arka plan referansı)"
        } else {
            "Spectro dosyası yok — çekim yine yapılır; Lab referansı sonra eklenebilir · batch=$batch"
        }
        b.spectroStatus.setBackgroundResource(
            if (ok) R.drawable.bg_status_ok else R.drawable.bg_status_warn,
        )
        b.spectroStatus.setTextColor(
            ContextCompat.getColor(requireContext(), if (ok) R.color.ok else R.color.warn),
        )
    }

    private fun refreshWifi() {
        val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        b.wifiStatus.text = buildString {
            append(if (wifi) "WiFi bağlı" else if (cellular) "Mobil veri" else "Ağ yok")
            append(" · Sunucu: ${SessionPrefs.serverUrl(requireContext())}")
        }
    }

    private fun showPrivacyPolicyDialog() {
        val ctx = requireContext()
        val body = """
            Gizlilik Politikası — ChromaTex Lab
            Son güncelleme: 2026-11-25 · Mimari: Kendi Sunucunu Getir (BYOS)

            1. Genel İlkeler (TR)
            ChromaTex Lab, tekstil numune görüntüleme ve renk/doku kalite kontrolü için geliştirilmiş ücretsiz bir mobil istemcidir. Uygulamada reklam, üçüncü taraf analiz/izleme (tracking) SDK’sı veya zorunlu bir geliştirici bulut sunucusu yoktur.

            2. İşlenen Veriler ve Amaç
            • Kamera Görüntüleri: Çektiğiniz kumaş numunesi/hedef kart fotoğrafları ve pozlama/hizalama metrikleri cihazda saklanır; Galeri’den paylaşılabilir veya yalnızca Ayarlar’da girdiğiniz kendi DataHub sunucunuza yüklenir.
            • Oturum Bilgileri: Opsiyonel operatör etiketi ve kumaş/sipariş/parti kodları yalnızca numune kaydına eklenir.
            • Cihaz Tanımlayıcısı: Uygulamaya özel cihaz kimliği (ANDROID_ID / UUID) yalnızca kendi DataHub sunucunuzda cihaz kaydı için kullanılır; reklam veya uygulamalar arası izleme için kullanılmaz.

            3. Veri Saklama ve Silme
            Verileriniz geliştiriciye gönderilmez ve üçüncü taraflara satılmaz. Cihazdaki tüm çekimleri İstatistik → "Tableti boşalt" adımıyla veya uygulamayı kaldırarak silebilirsiniz. Kendi DataHub sunucunuza aktardığınız verilerin yönetimi kurumunuzun kontrolündedir.

            ────────────────────────────────────

            Privacy Policy Summary (EN)
            ChromaTex Lab uses a Bring Your Own Server (BYOS) architecture. There are no ads, no third-party analytics/tracking SDKs, and no developer-operated cloud backend receiving your captures. Photos, capture diagnostics, optional session labels, and an app-scoped device identifier (ANDROID_ID / UUID) remain on your device unless you explicitly upload them to the self-hosted DataHub URL configured in Settings. You can delete all local captures anytime from the Stats tab.
        """.trimIndent()

        val tv = TextView(ctx).apply {
            text = body
            textSize = 13.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            setLineSpacing(0f, 1.2f)
            setPadding(48, 32, 48, 24)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(ctx).apply { addView(tv) }
        AlertDialog.Builder(ctx)
            .setTitle("Gizlilik Politikası")
            .setView(scroll)
            .setPositiveButton("Kapat", null)
            .setNeutralButton("Web'de aç") { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://berkhangulerci-ux.github.io/chromatex-lab-mobile/privacy-policy.html"),
                        ),
                    )
                } catch (_: Exception) {
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        refreshSpectro()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
