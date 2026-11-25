package org.chromatex.lab

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import org.chromatex.lab.data.CameraCapabilities
import org.chromatex.lab.data.CameraOptics
import org.chromatex.lab.data.CaptureMeta
import org.chromatex.lab.data.FabricSession
import org.chromatex.lab.data.SceneRecordStore
import org.chromatex.lab.databinding.ActivityCaptureBinding
import org.chromatex.lab.ui.CaptureCoach
import org.chromatex.lab.ui.CoachKey
import org.chromatex.lab.ui.ExposureDecision
import org.chromatex.lab.ui.ExposurePolicyController
import org.chromatex.lab.ui.ExposurePolicyState
import org.chromatex.lab.ui.FrameGuide
import org.chromatex.lab.ui.FramingGuideAnalyzer
import org.chromatex.lab.ui.PostCaptureVerifier
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var coach: CaptureCoach
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var torchOn: Boolean = false
    private var torchAvailable: Boolean = false
    private var focusArmed: Boolean = false
    private var lastRingSeated: Boolean = false
    /** Hold/çekim sırasında AWB kaymasını önler; abort/fail ile açılır. */
    private var awbLocked: Boolean = false

    private val latestIso = AtomicReference<Int?>(null)
    private val latestExposureNs = AtomicReference<Long?>(null)
    private val latestGuide = AtomicReference(FrameGuide("Hizalanıyor…", false, 0.0))
    private val capturing = AtomicBoolean(false)
    private var captureWatchdog: Job? = null
    private var framingAnalyzer: FramingGuideAnalyzer? = null
    private var optics: CameraOptics? = null
    private var activeWindowFraction: Float = CameraOptics.HOLE_FILL_FRAC
    private var cameraProvider: ProcessCameraProvider? = null
    private var camCaps: CameraCapabilities? = null
    /** AF + ana lens (2.2–6.0mm) seçilmiş arka kamera. */
    private var cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

    /** Ardışık OK + süre; AF hold sırasında TETİKLENMEZ (yumuşak kare bug’ı). */
    /** Log: 1100ms hold minLeft=233 — hard flicker reset yüzünden hiç 0’a inmiyordu */
    private val autoHoldMs = 650L
    private var okSinceElapsed: Long = -1L
    /** Deklanşör karar anı — kayıtta latestGuide ile ezilmesin. */
    private var captureGuideSnap: FrameGuide? = null
    /** İnce süprem tabanı. Asıl keskinlik halka kenarı (ringSharpOk ≥ 8). */
    private val minRawSharpForCapture = 3.8

    private var cameraId: String? = null
    private var focalMm: Float? = null
    private var sensorSize: SizeF? = null
    private var resolutionW: Int? = null
    private var resolutionH: Int? = null

    /**
     * AE kontrolü:
     * - autoPolicy=true (varsayılan): ortam-duyarlı politika ISO/exp veya EV ayarlar
     * - autoPolicy=false: operatör manuel ISO/exp (veya EV)
     */
    private var autoPolicy: Boolean = true
    private var manualAe: Boolean = false
    private var isoPresets: List<Int> = emptyList()
    private var expPresetsNs: List<Long> = emptyList()
    private var isoIndex: Int = 0
    private var expIndex: Int = 0
    private var aeCompIndex: Int = 0
    private var aeCompValues: List<Int> = emptyList()
    private val exposurePolicy = ExposurePolicyController()
    private var latestExposureDecision: ExposureDecision? = null

    private val operator: String by lazy { intent.getStringExtra(EXTRA_OPERATOR).orEmpty() }
    private val batchId: String by lazy {
        intent.getStringExtra(EXTRA_BATCH) ?: "target_v1_batch_2026-11-16"
    }
    private val fabric: FabricSession by lazy {
        FabricSession(
            fabricState = intent.getStringExtra(EXTRA_FABRIC_STATE) ?: "ham",
            label = intent.getStringExtra(EXTRA_LABEL) ?: "normal_kumas",
            fabricType = intent.getStringExtra(EXTRA_FABRIC_TYPE) ?: "Suprem",
            fabricColor = intent.getStringExtra(EXTRA_FABRIC_COLOR) ?: "Ekru",
            orguKodu = intent.getStringExtra(EXTRA_ORGU),
            sipNo = intent.getStringExtra(EXTRA_SIP),
            isemriNo = intent.getStringExtra(EXTRA_ISEMRI),
            makinaNo = intent.getStringExtra(EXTRA_MAKINA),
            partiNo = intent.getStringExtra(EXTRA_PARTI),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyCaptureSystemBarInsets()
        DedicatedMode.apply(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.previewView.scaleType = androidx.camera.view.PreviewView.ScaleType.FIT_CENTER
        cameraExecutor = Executors.newSingleThreadExecutor()
        coach = CaptureCoach(this)

        binding.guideText.text =
            "SARI kare = 100mm delik — siyah+yesil halkayi hizalayin (v2 hedef)"
        applyWindowFraction(CameraOptics.fallbackWindowFraction())
        binding.sessionBanner.text =
            "${fabric.fabricState} · ${fabric.label} · ${fabric.fabricType} · ${fabric.fabricColor}"

        binding.btnTorch.setOnClickListener { toggleTorch() }
        // Manuel çek her zaman açık — kapı kapalıyken de anı kaydedip analiz ederiz
        binding.btnShot.setOnClickListener {
            takePhoto(auto = false, force = true)
        }
        binding.btnCancel.setOnClickListener { abortCapture("btn_cancel") }
        binding.btnAeMode.setOnClickListener { toggleAeMode() }
        binding.btnIso.setOnClickListener { cycleIso() }
        binding.btnExposure.setOnClickListener { cycleExposure() }
        binding.btnAeComp.setOnClickListener { cycleAeComp() }
        binding.btnShot.isEnabled = true
        binding.btnShot.text = getString(R.string.capture_shot)
        binding.previewView.setOnClickListener { focusOnFabricWindow(force = true) }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    abortCapture("system_back")
                }
            },
        )
        startCamera()
    }

    private fun applyCaptureSystemBarInsets() {
        val rootPadL = binding.root.paddingLeft
        val rootPadR = binding.root.paddingRight
        val guidePadTop = binding.guideText.paddingTop
        val bottomPadBottom = binding.bottomBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(rootPadL + bars.left, 0, rootPadR + bars.right, 0)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.guideText) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(v.paddingLeft, guidePadTop + bars.top, v.paddingRight, v.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomBar) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomPadBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    /** Çekimden vazgeç — takılı capturing dahil her zaman çık. */
    private fun abortCapture(reason: String) {
        captureWatchdog?.cancel()
        captureWatchdog = null
        capturing.set(false)
        captureGuideSnap = null
        okSinceElapsed = -1L
        setAwbLock(false)
        try {
            coach.quiet()
        } catch (_: Exception) {
        }
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        finish()
    }

    private fun applyWindowFraction(frac: Float = CameraOptics.HOLE_FILL_FRAC) {
        // Evrensel sabit: 100mm delik / 220mm kart → 0.38 (cihaz FOV tahmini yok)
        activeWindowFraction = CameraOptics.HOLE_FILL_FRAC
        binding.overlay.windowFraction = activeWindowFraction
        framingAnalyzer?.windowFractionRef?.set(activeWindowFraction)
        if (abs(frac - CameraOptics.HOLE_FILL_FRAC) > 0.001f) {
            Log.i(TAG, "windowFraction forced to ${CameraOptics.HOLE_FILL_FRAC} (ignored $frac)")
        }
    }

    /** Preview / Analysis / Capture aynı sensör oranında — sarı kare ↔ analiz örtüşür. */
    private fun sharedAspectRatioStrategy(): AspectRatioStrategy =
        AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

    private fun probeCameraCaps(preferredId: String?): CameraCapabilities? {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (preferredId != null) {
                return CameraCapabilities.from(cm.getCameraCharacteristics(preferredId), preferredId)
            }
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return CameraCapabilities.from(chars, id)
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "probe camera caps failed", e)
            null
        }
    }

    /**
     * Arka kameraları tara: LENS_FACING_BACK + AF (AUTO/CONTINUOUS_PICTURE) +
     * ana lens odak 2.2–6.0 mm. Makro / ultra-geniş elenir.
     */
    private fun selectBestBackCamera(provider: ProcessCameraProvider): Pair<CameraSelector, String?> {
        data class Candidate(val id: String, val focal: Float, val info: CameraInfo)

        val candidates = ArrayList<Candidate>()
        for (info in provider.availableCameraInfos) {
            try {
                val c2 = Camera2CameraInfo.from(info)
                val facing = c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING)
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue
                val chars = try {
                    val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    cm.getCameraCharacteristics(c2.cameraId)
                } catch (_: Exception) {
                    null
                } ?: continue
                if (!CameraCapabilities.hasUsableAutofocus(chars)) continue
                val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?: continue
                val focal = focals.firstOrNull() ?: continue
                if (!CameraCapabilities.isMainBackFocal(focal)) continue
                candidates.add(Candidate(c2.cameraId, focal, info))
            } catch (e: Exception) {
                Log.w(TAG, "skip camera candidate", e)
            }
        }

        val best = candidates.minByOrNull { abs(it.focal - 3.6f) }
        if (best == null) {
            Log.w(TAG, "no AF main-back lens — DEFAULT_BACK_CAMERA")
            return CameraSelector.DEFAULT_BACK_CAMERA to null
        }
        Log.i(TAG, "selected back camera id=${best.id} f=${best.focal}mm")
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .addCameraFilter { infos ->
                val match = infos.filter {
                    try {
                        Camera2CameraInfo.from(it).cameraId == best.id
                    } catch (_: Exception) {
                        false
                    }
                }
                match.ifEmpty { infos }
            }
            .build()
        return selector to best.id
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val (selector, preferredId) = selectBestBackCamera(provider)
            cameraSelector = selector
            camCaps = probeCameraCaps(preferredId)
            setupAeUiFromCaps()

            val aspect = sharedAspectRatioStrategy()
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also { it.surfaceProvider = binding.previewView.surfaceProvider }

            // Önizlemede de doğrudan max 4:3 JPEG iste (Xiaomi/modern cihazlarda rebind gerekmez,
            // böylece çekim anında AF/AWB kilidi korunur ve 2 sn deklanşör gecikmesi sıfırlanır).
            imageCapture = buildImageCapture(preferMax = true)

            val analyzer = FramingGuideAnalyzer { guide ->
                latestGuide.set(guide)
                runOnUiThread { onGuideUi(guide) }
            }
            framingAnalyzer = analyzer
            analyzer.windowFractionRef.set(activeWindowFraction)

            val analysisSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(aspect)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(640, 480),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                    ),
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(analysisSelector)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            // ViewPort layout sonrası hazır — UseCaseGroup ile FOV senkronu
            binding.previewView.post {
                bindUseCasesWithViewPort(
                    provider = provider,
                    preview = preview,
                    analysis = analysis,
                    capture = imageCapture,
                    includeAnalysis = true,
                )
                applyTorch()
                applyAeSettings()
                focusOnFabricWindow(force = true)
                refreshMetaText()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Preview (FIT_CENTER) + Analysis (640x480 4:3) + Capture (max 4:3) aynı tam sensör FOV’sunda.
     * Dar ekran oranına (20:9 / 16:10) ViewPort kırpması uygulanmaz; böylece kenardaki
     * R-G-B-Y ve C-M-O-Fb renk yamaları JPEG’de kesilmez ve analiz ile JPEG 1:1 eşleşir.
     */
    private fun bindUseCasesWithViewPort(
        provider: ProcessCameraProvider,
        preview: Preview,
        analysis: ImageAnalysis? = null,
        capture: ImageCapture?,
        includeAnalysis: Boolean,
    ) {
        provider.unbindAll()
        camera = try {
            when {
                includeAnalysis && analysis != null && capture != null ->
                    provider.bindToLifecycle(
                        this, cameraSelector, preview, capture, analysis,
                    )
                capture != null ->
                    provider.bindToLifecycle(this, cameraSelector, preview, capture)
                else ->
                    provider.bindToLifecycle(this, cameraSelector, preview)
            }
        } catch (e: Exception) {
            Log.e(TAG, "bindUseCasesWithViewPort failed", e)
            null
        }

        camera?.let { cam ->
            val info = Camera2CameraInfo.from(cam.cameraInfo)
            cameraId = info.cameraId
            try {
                val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                camCaps = CameraCapabilities.from(
                    cm.getCameraCharacteristics(info.cameraId),
                    info.cameraId,
                )
                setupAeUiFromCaps()
            } catch (_: Exception) {
            }
            sensorSize = info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE,
            )
            focalMm = info.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
            )?.firstOrNull()
            resolutionW = imageCapture?.resolutionInfo?.resolution?.width
            resolutionH = imageCapture?.resolutionInfo?.resolution?.height
            torchAvailable = cam.cameraInfo.hasFlashUnit()

            optics = CameraOptics.from(focalMm, sensorSize, resolutionW, resolutionH)
            applyWindowFraction(CameraOptics.HOLE_FILL_FRAC)
            val frac = activeWindowFraction
            Log.i(TAG, optics?.summary(frac) ?: "optics fallback frac=$frac")
            Log.i(TAG, camCaps?.summary() ?: "caps none")
            Log.i(TAG, "previewScale=FIT_CENTER uncropped4x3 res=${resolutionW}x${resolutionH}")
            val maxMp = camCaps?.maxMegapixels ?: 99.0
            if (maxMp in 0.1..4.9) {
                Toast.makeText(
                    this,
                    "Uyarı: Bu cihazın kamerası %.1f MP (<5 MP) — eğitim çekimleri için ana tablet/telefon kullanın".format(Locale.US, maxMp),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /** Stok kamera max JPEG — sensörün en büyük çıktısı; 4:3 öncelikli. */
    private fun buildImageCapture(preferMax: Boolean): ImageCapture {
        val target = camCaps?.preferredCaptureSize
        val strategy = when {
            preferMax -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            target != null ->
                ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            else -> ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
        }
        val selector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(sharedAspectRatioStrategy())
            .setAllowedResolutionMode(
                ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE,
            )
            .setResolutionStrategy(strategy)
            .setResolutionFilter { supported, _ ->
                supported.sortedByDescending { it.width.toLong() * it.height }
            }
            .build()
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(98)
            .setResolutionSelector(selector)
        Camera2Interop.Extender(captureBuilder).setSessionCaptureCallback(
            object : android.hardware.camera2.CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: android.hardware.camera2.CameraCaptureSession,
                    request: android.hardware.camera2.CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    latestIso.set(result.get(CaptureResult.SENSOR_SENSITIVITY))
                    latestExposureNs.set(result.get(CaptureResult.SENSOR_EXPOSURE_TIME))
                    runOnUiThread { refreshMetaText() }
                }
            },
        )
        return captureBuilder.build()
    }

    /**
     * Mevcut bağlı ImageCapture zaten tercih edilen sensör çözünürlüğünün >= %85'indeyse
     * kamerayı kapatıp açmak (rebind) gereksizdir; AF kilidini bozar ve ~2 sn gecikme üretir.
     */
    private fun needsRebindForMaxStill(): Boolean {
        val cap = imageCapture ?: return true
        val cur = cap.resolutionInfo?.resolution ?: return true
        val curPx = cur.width.toLong() * cur.height
        if (curPx <= 0L) return true
        val pref = camCaps?.preferredCaptureSize ?: return false
        val prefPx = pref.width.toLong() * pref.height
        return prefPx > 0L && curPx < (prefPx * 85L) / 100L
    }

    /**
     * Yalnız eski/LIMITED cihazlarda analiz yüzünden JPEG çözünürlüğü ciddi düştüyse çağrılır.
     */
    private fun rebindForMaxStill(): Boolean {
        val provider = cameraProvider ?: return false
        return try {
            val previewSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(sharedAspectRatioStrategy())
                .build()
            val preview = Preview.Builder()
                .setResolutionSelector(previewSelector)
                .build()
                .also { it.surfaceProvider = binding.previewView.surfaceProvider }
            imageCapture = buildImageCapture(preferMax = true)
            bindUseCasesWithViewPort(
                provider = provider,
                preview = preview,
                analysis = null,
                capture = imageCapture,
                includeAnalysis = false,
            )
            if (camera == null) return false
            resolutionW = imageCapture?.resolutionInfo?.resolution?.width
            resolutionH = imageCapture?.resolutionInfo?.resolution?.height
            optics = CameraOptics.from(focalMm, sensorSize, resolutionW, resolutionH) ?: optics
            applyTorch()
            applyAeSettings()
            true
        } catch (e: Exception) {
            Log.e(TAG, "rebindForMaxStill failed", e)
            false
        }
    }

    private fun setupAeUiFromCaps() {
        val caps = camCaps
        binding.txtCamCaps.text = caps?.summary() ?: "Kamera?"
        exposurePolicy.updateLimits(caps)
        isoPresets = caps?.isoPresets().orEmpty()
        expPresetsNs = caps?.exposurePresetsNs().orEmpty()
        // Varsayılan: ISO prefer (~160) + ~20ms (cihaz aralığında en yakın)
        val lim = exposurePolicy.limits()
        if (isoPresets.isNotEmpty()) {
            isoIndex = isoPresets.indices.minByOrNull {
                abs(isoPresets[it] - lim.isoPrefer)
            } ?: 0
        }
        if (expPresetsNs.isNotEmpty()) {
            expIndex = expPresetsNs.indices.minByOrNull {
                abs(expPresetsNs[it] - lim.expPreferNs)
            } ?: 0
        }
        aeCompValues = caps?.aeCompensationRange?.let { r ->
            (r.lower..r.upper).toList()
        }.orEmpty()
        aeCompIndex = aeCompValues.indexOf(0).let { if (it >= 0) it else aeCompValues.size / 2 }

        val canManual = caps?.allowManualIsoExp == true
        val canEv = caps?.supportsAeCompensation == true
        // AUTO_POLICY: FULL/LEVEL_3 manuel; LIMITED/LEGACY → EV
        autoPolicy = true
        manualAe = false
        binding.btnAeMode.isEnabled = canManual || canEv
        syncAeModeUi()
        refreshAeButtons()
    }

    private fun syncAeModeUi() {
        val caps = camCaps
        val canManual = caps?.allowManualIsoExp == true
        val canEv = caps?.supportsAeCompensation == true
        binding.btnAeMode.text = getString(
            if (autoPolicy) R.string.ae_auto else R.string.ae_manual,
        )
        // Politika aktifken ISO/exp butonları kilitli; manuelde aç
        binding.btnIso.isEnabled = !autoPolicy && manualAe && isoPresets.isNotEmpty()
        binding.btnExposure.isEnabled = !autoPolicy && manualAe && expPresetsNs.isNotEmpty()
        binding.btnAeComp.isEnabled =
            !autoPolicy && !manualAe && canEv && aeCompValues.isNotEmpty()
        if (!canManual && !canEv) {
            binding.btnAeMode.isEnabled = false
        }
    }

    private fun toggleAeMode() {
        val caps = camCaps ?: return
        if (autoPolicy) {
            // AUTO_POLICY → Manuel
            if (caps.allowManualIsoExp) {
                autoPolicy = false
                manualAe = true
            } else if (caps.supportsAeCompensation) {
                autoPolicy = false
                manualAe = false
                Toast.makeText(
                    this,
                    "Politika kapali — EV ile ayarlayin (manuel ISO yok)",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                Toast.makeText(this, "AE kontrolü desteklenmiyor", Toast.LENGTH_SHORT).show()
                return
            }
        } else {
            // Manuel → AUTO_POLICY
            autoPolicy = true
            manualAe = false
        }
        syncAeModeUi()
        applyAeSettings()
        refreshAeButtons()
    }

    private fun cycleIso() {
        if (autoPolicy || !manualAe || isoPresets.isEmpty()) return
        isoIndex = (isoIndex + 1) % isoPresets.size
        applyAeSettings()
        refreshAeButtons()
    }

    private fun cycleExposure() {
        if (autoPolicy || !manualAe || expPresetsNs.isEmpty()) return
        expIndex = (expIndex + 1) % expPresetsNs.size
        applyAeSettings()
        refreshAeButtons()
    }

    private fun cycleAeComp() {
        if (autoPolicy || manualAe || aeCompValues.isEmpty()) return
        aeCompIndex = (aeCompIndex + 1) % aeCompValues.size
        applyAeSettings()
        refreshAeButtons()
    }

    private fun refreshAeButtons() {
        val caps = camCaps
        binding.btnIso.text = if (isoPresets.isNotEmpty()) {
            "ISO ${isoPresets.getOrElse(isoIndex) { isoPresets.first() }}"
        } else {
            "ISO —"
        }
        binding.btnExposure.text = if (expPresetsNs.isNotEmpty() && caps != null) {
            caps.formatExposure(expPresetsNs.getOrElse(expIndex) { expPresetsNs.first() })
        } else {
            "Exp —"
        }
        binding.btnAeComp.text = if (aeCompValues.isNotEmpty()) {
            val v = aeCompValues.getOrElse(aeCompIndex) { 0 }
            val step = caps?.aeCompensationStep ?: 0.5f
            "EV %+.1f".format(v * step)
        } else {
            "EV —"
        }
    }

    /**
     * Hold/çekim kilidi: AWB + AE (destekleniyorsa).
     * FULL/LEVEL_3: EDGE_OFF + NR_MINIMAL. LIMITED/LEGACY: yalnız kilit / EV.
     */
    private fun setAwbLock(locked: Boolean) {
        if (awbLocked == locked) return
        awbLocked = locked
        applyAeSettings()
    }

    private fun <T : Any> CaptureRequestOptions.Builder.safeSet(
        key: CaptureRequest.Key<T>,
        value: T,
        label: String,
    ): CaptureRequestOptions.Builder {
        try {
            setCaptureRequestOption(key, value)
        } catch (e: Exception) {
            Log.w(TAG, "skip CaptureRequest $label", e)
        }
        return this
    }

    private fun CaptureRequestOptions.Builder.applyStillLocksAndIsp(): CaptureRequestOptions.Builder {
        val caps = camCaps
        if (caps?.supportsAwbLock == true) {
            safeSet(CaptureRequest.CONTROL_AWB_LOCK, awbLocked, "AWB_LOCK")
        }
        if (caps?.supportsAeLock == true) {
            safeSet(CaptureRequest.CONTROL_AE_LOCK, awbLocked, "AE_LOCK")
        }
        if (caps?.allowStillIspTuning == true) {
            if (caps.supportsEdgeModeOff) {
                safeSet(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF, "EDGE_OFF")
            }
            if (caps.supportsNoiseReductionMinimal) {
                safeSet(
                    CaptureRequest.NOISE_REDUCTION_MODE,
                    CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
                    "NR_MINIMAL",
                )
            }
        }
        return this
    }

    private fun applyAeSettings() {
        val cam = camera ?: return
        try {
            val c2 = Camera2CameraControl.from(cam.cameraControl)
            val caps = camCaps
            val useManualSensor =
                caps?.allowManualIsoExp == true && (manualAe || autoPolicy)
            if (useManualSensor) {
                val iso = isoPresets.getOrNull(isoIndex)
                val exp = expPresetsNs.getOrNull(expIndex)
                val builder = CaptureRequestOptions.Builder()
                if (iso != null && exp != null) {
                    builder
                        .safeSet(
                            CaptureRequest.CONTROL_AE_MODE,
                            CaptureRequest.CONTROL_AE_MODE_OFF,
                            "AE_OFF",
                        )
                        .safeSet(CaptureRequest.SENSOR_SENSITIVITY, iso, "ISO")
                        .safeSet(CaptureRequest.SENSOR_EXPOSURE_TIME, exp, "EXP")
                }
                builder.applyStillLocksAndIsp()
                try {
                    c2.setCaptureRequestOptions(builder.build())
                } catch (e: Exception) {
                    Log.w(TAG, "setCaptureRequestOptions manual failed", e)
                }
            } else {
                try {
                    c2.clearCaptureRequestOptions()
                } catch (e: Exception) {
                    Log.w(TAG, "clearCaptureRequestOptions failed", e)
                }
                try {
                    c2.setCaptureRequestOptions(
                        CaptureRequestOptions.Builder()
                            .applyStillLocksAndIsp()
                            .build(),
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "setCaptureRequestOptions locks failed", e)
                }
                if (caps?.supportsAeCompensation == true && aeCompValues.isNotEmpty()) {
                    try {
                        val idx = aeCompValues.getOrElse(aeCompIndex) { 0 }
                        cam.cameraControl.setExposureCompensationIndex(idx)
                    } catch (e: Exception) {
                        Log.w(TAG, "EV compensation failed", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "applyAeSettings failed", e)
        }
        refreshMetaText()
    }

    /** Politika ISO/exp veya EV istediğinde uygula (AUTO_POLICY iken). */
    private fun applyPolicyDecision(d: ExposureDecision) {
        if (!autoPolicy) return
        val supportsManual = camCaps?.allowManualIsoExp == true
        var changed = false
        if (supportsManual) {
            d.wantIso?.let { want ->
                val idx = isoPresets.indexOf(want).takeIf { it >= 0 }
                    ?: isoPresets.indices.minByOrNull { abs(isoPresets[it] - want) }
                if (idx != null && idx != isoIndex) {
                    isoIndex = idx
                    changed = true
                }
            }
            d.wantExpNs?.let { want ->
                val idx = expPresetsNs.indexOf(want).takeIf { it >= 0 }
                    ?: expPresetsNs.indices.minByOrNull { abs(expPresetsNs[it] - want) }
                if (idx != null && idx != expIndex) {
                    expIndex = idx
                    changed = true
                }
            }
            if (changed) {
                applyAeSettings()
                refreshAeButtons()
            }
        } else if (d.evDelta != 0 && aeCompValues.isNotEmpty()) {
            val next = (aeCompIndex + d.evDelta).coerceIn(0, aeCompValues.lastIndex)
            if (next != aeCompIndex) {
                aeCompIndex = next
                applyAeSettings()
                refreshAeButtons()
            }
        }
    }

    /**
     * Auto-shoot pozlama kapısı — master adayı üretmek için.
     * AUTO_POLICY: EXPOSURE_OK şart.
     * Manuel: ISO/exp hard-fail yine engeller (gürültü/blur → train-grade değil).
     */
    private fun isExposureOkForShoot(): Boolean {
        val d = latestExposureDecision ?: return !autoPolicy
        if (!d.exposureOk) return false
        return if (autoPolicy) {
            d.state == ExposurePolicyState.EXPOSURE_OK
        } else {
            true
        }
    }

    private fun tickExposurePolicy(guide: FrameGuide): ExposureDecision {
        val now = SystemClock.elapsedRealtime()
        val supportsManual = camCaps?.allowManualIsoExp == true
        val d = exposurePolicy.evaluate(
            luma = guide.luma,
            iso = latestIso.get(),
            expNs = latestExposureNs.get(),
            isoPresets = isoPresets,
            expPresetsNs = expPresetsNs,
            supportsManual = supportsManual,
            nowElapsed = now,
            apply = autoPolicy,
            whiteRingLuma = guide.whiteRingLuma,
            whiteRingClipRatio = guide.whiteRingClipRatio,
        )
        latestExposureDecision = d
        if (autoPolicy) applyPolicyDecision(d)
        return d
    }

    private fun onGuideUi(guide: FrameGuide) {
        if (capturing.get()) return

        val expDec = tickExposurePolicy(guide)
        val exposureBlocks = !isExposureOkForShoot()

        // Sadece LIGHT_* UI’yı kilitle — ADJUSTING iken kadraj/egim koçu görünsün
        // (eski davranış: 2 dk “Poz ayarlaniyor” + sonra tüm uyarılar peş peşe)
        if (exposureBlocks &&
            (
                expDec.state == ExposurePolicyState.LIGHT_NEED ||
                    expDec.state == ExposurePolicyState.LIGHT_TOO_MUCH
                )
        ) {
            okSinceElapsed = -1L
            setAwbLock(false)
            binding.overlay.bigArrow = "!"
            binding.overlay.guideHint = expDec.guidance
            binding.overlay.okReady = false
            binding.overlay.flashMode = 1
            binding.guideText.setBackgroundColor(
                ContextCompat.getColor(this, R.color.guide_bad_bg),
            )
            binding.guideText.text = "!  ${expDec.guidance}"
            binding.btnShot.isEnabled = true
            binding.btnShot.alpha = 0.9f
            if (expDec.coachKey != CoachKey.NONE) {
                coach.onGuide(expDec.coachKey)
            }
            refreshMetaText()
            return
        }

        val justSeated = guide.cornersSeated && !lastRingSeated
        lastRingSeated = guide.cornersSeated
        if (okSinceElapsed < 0L && (
                justSeated ||
                    (guide.cornersSeated && (guide.textSharpness < 11.0 || guide.ringEdgeStep < 42.0))
                )
        ) {
            // Kart sarı hedefe yeni oturduğunda veya halka kenarı henüz yumuşaksa gerçek kart mesafesine AF kilitle
            focusOnFabricWindow(force = justSeated)
        }

        val settling = guide.qualityOk && !guide.ok
        val hardOk = isHardQualityOk(guide)
        // Yeşil yalnız analyzer+hard+exposure birlikte
        val showGreen = ((guide.ok && hardOk) || (settling && hardOk)) && !exposureBlocks
        binding.overlay.bigArrow = guide.arrow
        binding.overlay.guideHint = guide.message
        binding.overlay.okReady = guide.ok && hardOk && !exposureBlocks
        binding.overlay.flashMode = when {
            guide.ok && hardOk && !exposureBlocks -> 2
            settling -> 0
            guide.coachKey == CoachKey.SKEW ||
                guide.coachKey == CoachKey.SKEW_UP ||
                guide.coachKey == CoachKey.SKEW_DOWN ||
                guide.coachKey == CoachKey.SKEW_LEFT ||
                guide.coachKey == CoachKey.SKEW_RIGHT ||
                guide.coachKey == CoachKey.DARK -> 1
            else -> 0
        }

        binding.guideText.setBackgroundColor(
            ContextCompat.getColor(
                this,
                when {
                    showGreen -> R.color.guide_ok_bg
                    else -> R.color.guide_bad_bg
                },
            ),
        )
        // Manuel çek her zaman aktif; görsel ipucu kaliteye göre
        binding.btnShot.isEnabled = true
        binding.btnShot.alpha = if (guide.ok && hardOk && !exposureBlocks) 1f else 0.9f
        binding.btnShot.text = getString(R.string.capture_shot)

        if (!guide.ok) {
            okSinceElapsed = -1L
            setAwbLock(false)
            val pozHint =
                if (exposureBlocks &&
                    expDec.state == ExposurePolicyState.EXPOSURE_ADJUSTING
                ) {
                    " · poz…"
                } else {
                    ""
                }
            binding.guideText.text = "${guide.arrow}  ${guide.message}$pozHint"
            coach.onGuide(guide.coachKey)
            // BLUR'da sürekli AF tetikleme = odak avı (log: sharp p50≈4.5). Sadece tap/bind.
            refreshMetaText()
            return
        }

        // Geometry OK ama poz henüz settle olmadı — hold başlatma; tek satır bilgi
        if (exposureBlocks) {
            okSinceElapsed = -1L
            setAwbLock(false)
            binding.guideText.text = "◎  ${expDec.guidance} — sabit tutun"
            if (expDec.coachKey != CoachKey.NONE) {
                coach.onGuide(expDec.coachKey)
            }
            refreshMetaText()
            return
        }

        // Ham netlik/yamuk — analyzer EMA ile OK demiş olabilir; hard fail'de yeşil/"Tamam" yok
        if (!hardOk) {
            // Hold sırasında tek kare flicker sayacı sıfırlama — log: hold hiç 0’a inmedi
            if (okSinceElapsed < 0L) {
                binding.guideText.text = "!  KALITE DUSUK — sabit tutun (manuel cekebilirsiniz)"
                coach.onGuide(CoachKey.BLUR)
                refreshMetaText()
                return
            }
            // hold devam: kısa sapmaya izin
        }

        val now = SystemClock.elapsedRealtime()
        if (okSinceElapsed < 0L) {
            okSinceElapsed = now
            setAwbLock(true)
            // Bilinçli: burada AF YOK — hold sırasında odak aramak soft JPEG üretiyordu
        }
        val held = now - okSinceElapsed
        val left = (autoHoldMs - held).coerceAtLeast(0L)
        coach.onGuide(CoachKey.OK_HOLD, holdLeftMs = left)
        if (left > 0L) {
            val sec = (left / 100.0).toInt() / 10.0
            binding.guideText.text =
                "●  SABIT  ${"%.1f".format(sec)}s  net=${"%.0f".format(guide.rawSharpness)} egim=${"%.0f".format(guide.rawSkewPx)}"
            refreshMetaText()
            return
        }

        // Hold bitti — çapraz/eğik kare force ile kaçmasın (WiFi: skew 36–67 + auto OK)
        if (!hardOk || !isHardQualityOk(guide) || !isExposureOkForShoot()) {
            okSinceElapsed = -1L
            setAwbLock(false)
            binding.guideText.text = if (!isExposureOkForShoot()) {
                "!  ${expDec.guidance}"
            } else {
                "!  EGIK/KALITE — duz tutun (manuel force ayri)"
            }
            return
        }
        binding.guideText.text = "●  CEKILIYOR…"
        takePhoto(auto = true, force = false)
    }

    private fun skewLimitPx(g: FrameGuide): Double {
        if (g.analysisShortPx <= 0) return 0.0
        // 0.08@480≈38 — log p50≈22 geçer; çapraz ~45+ reddeder
        return g.analysisShortPx * 0.08
    }

    /** Köşe/delik oturumu + netlik; yumuşak/eğik ham kareyi reddet. */
    private fun isHardQualityOk(g: FrameGuide): Boolean {
        if (!g.cornersSeated) return false
        // fill yalnızca ölçülmüşse ve aşırı kötüyse engelle (sıkı v2 bandı sürekli red ediyordu)
        if (g.fillRatio > 0.0 && (g.fillRatio < 0.65 || g.fillRatio > 1.40)) return false
        // Asıl optik odak kapısı: halka kenar keskinliği + 2-px uçurum kontrastı
        if (g.textSharpness > 0.0 && g.textSharpness < 8.0) return false
        if (g.ringEdgeStep > 0.0 && g.ringEdgeStep < 32.0) return false
        val effMinFabSharp = if (g.luma < 75.0) 0.45 else 1.8
        if (g.rawSharpness > 0.0 && g.rawSharpness < effMinFabSharp) return false
        if (!g.cornersSeated && g.borderScore > 0.0 && g.borderScore < 12.0) return false
        if (g.rawSkewPx > 0.0 && g.analysisShortPx > 0) {
            val lim = skewLimitPx(g)
            if (g.rawSkewPx > lim && g.skewPx > lim * 0.85) return false
        }
        return true
    }

    /**
     * AF: PreviewView createPoint piksel ister — (0.5,0.5) sol üste kilitleniyordu.
     * Odak = yüksek kontrastlı halka sınırı; düz/siyah kumaş ortasında AF takılmasın.
     * Auto-cancel 2.5s tutulur ki operatör mesafeyi değiştirdiğinde kamera eski mesafede kilitli kalmasın.
     */
    private fun focusOnFabricWindow(force: Boolean) {
        val cam = camera ?: return
        if (!force && focusArmed) return
        val vw = binding.previewView.width
        val vh = binding.previewView.height
        if (vw < 16 || vh < 16) {
            binding.previewView.post { focusOnFabricWindow(force) }
            return
        }
        val factory = binding.previewView.meteringPointFactory
        val cx = vw * 0.5f
        val cy = vh * 0.5f
        val fittedW: Float
        val fittedH: Float
        if (vw * 4 <= vh * 3) {
            fittedW = vw.toFloat()
            fittedH = vw * (4f / 3f)
        } else {
            fittedH = vh.toFloat()
            fittedW = vh * (3f / 4f)
        }
        val hole = minOf(fittedW, fittedH) * activeWindowFraction
        // Siyah-beyaz-yeşil halka sınırı — yüksek kontrast odak hedefi
        val inset = (hole * 0.56f).coerceAtLeast(24f)
        val n = factory.createPoint(cx, (cy - inset).coerceIn(8f, vh - 8f))
        val s = factory.createPoint(cx, (cy + inset).coerceIn(8f, vh - 8f))
        val e = factory.createPoint((cx + inset).coerceIn(8f, vw - 8f), cy)
        val wPt = factory.createPoint((cx - inset).coerceIn(8f, vw - 8f), cy)
        val center = factory.createPoint(cx, cy)
        // Birincil AF noktası = halka kenarı (düz siyah kumaş merkezinde kontrast 0 olduğu için merkez ikincil)
        val action = FocusMeteringAction.Builder(
            n,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            .addPoint(s, FocusMeteringAction.FLAG_AF)
            .addPoint(e, FocusMeteringAction.FLAG_AF)
            .addPoint(wPt, FocusMeteringAction.FLAG_AF)
            .addPoint(center, FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2500L, TimeUnit.MILLISECONDS)
            .build()
        try {
            cam.cameraControl.startFocusAndMetering(action)
            focusArmed = true
            binding.previewView.postDelayed({ focusArmed = false }, 1500)
        } catch (e: Exception) {
            Log.w(TAG, "focus metering failed", e)
        }
    }

    private fun refreshMetaText() {
        val iso = latestIso.get()
        val exp = latestExposureNs.get()
        val expMs = exp?.let { it / 1_000_000.0 }
        val g = latestGuide.get()
        val o = optics
        val d = latestExposureDecision
        val lumaShow = d?.measuredLuma
            ?: g.lightEma.takeIf { it > 0 }
            ?: g.luma
        binding.metaText.text = buildString {
            append(
                when {
                    autoPolicy -> "AUTO_POLICY"
                    manualAe -> "AE=MAN"
                    else -> "AE=EV"
                },
            )
            append("  pol=").append(d?.state?.name ?: "?")
            append("  ISO=").append(iso ?: "?")
            append("  exp=").append(expMs?.let { "%.2fms".format(it) } ?: "?")
            append("  luma=").append("%.0f".format(lumaShow))
            append("  expOK=").append(d?.exposureOk == true)
            append("  res=").append(resolutionW ?: "?").append("x").append(resolutionH ?: "?")
            camCaps?.preferredCaptureSize?.let { p ->
                append("  max=").append(p.width).append("x").append(p.height)
            }
            append("  frac=").append("%.2f".format(activeWindowFraction))
            // Canlı meta = capture optiği (analiz hole px kayda yazılmaz)
            val liveHole = o?.holeSidePx(activeWindowFraction)
            if (liveHole != null && liveHole > 0) {
                append("  hole~").append(liveHole).append("px")
                append("  ").append("%.1f".format(liveHole / 100.0)).append("px/mm")
            } else if (g.holeSidePx > 0) {
                append("  holeA=").append(g.holeSidePx).append("px")
            }
            append("  mode=").append(g.geometryMode)
            append("  blk=").append("%.0f".format(g.blackRingScore))
            append("  grn=").append("%.0f".format(g.greenRingScore))
            append("  fill=").append("%.2f".format(g.fillRatio))
            append("  doku=").append("%.1f".format(g.centerStd))
            append("  net=").append("%.0f".format(g.rawSharpness))
            append("  egim=").append("%.0f".format(g.rawSkewPx))
            if (g.skewDir.isNotBlank()) append("/").append(g.skewDir)
            append("  kenar=").append("%.0f".format(g.borderScore))
            append("  okN=").append(g.consecutiveOk)
            append("  torch=").append(
                when {
                    !torchAvailable -> "YOK"
                    torchOn -> "ON"
                    else -> "OFF"
                },
            )
        }
    }

    private fun toggleTorch() {
        if (!torchAvailable) {
            Toast.makeText(this, "Bu tablette torch yok — sabit oda ışığı kullanın", Toast.LENGTH_LONG).show()
            return
        }
        torchOn = !torchOn
        applyTorch()
        refreshMetaText()
    }

    private fun applyTorch() {
        val cam = camera ?: return
        torchAvailable = cam.cameraInfo.hasFlashUnit()
        if (torchAvailable) {
            cam.cameraControl.enableTorch(torchOn)
            binding.btnTorch.setText(if (torchOn) R.string.torch_on else R.string.torch_off)
        } else {
            torchOn = false
            binding.btnTorch.text = "Torch yok — sabit isik"
        }
    }

    /**
     * @param force true = kalite kapısını aş (analiz için anı kaydet).
     * Auto asla force kullanmaz.
     */
    private fun takePhoto(auto: Boolean, force: Boolean = false) {
        if (!capturing.compareAndSet(false, true)) return
        val guide = latestGuide.get()
        val holeMissing = guide.geometryMode == "no_hole" || !guide.qualityOk
        val exposurePass = isExposureOkForShoot()
        val qualityPass =
            guide.ok && isHardQualityOk(guide) && !holeMissing && exposurePass
        if (!force && !qualityPass) {
            capturing.set(false)
            okSinceElapsed = -1L
            setAwbLock(false)
            if (!auto) {
                val msg = when {
                    !exposurePass ->
                        latestExposureDecision?.guidance
                            ?: "Pozlama yetersiz (ISO/exp)"
                    else -> guide.message.ifBlank { "Kalite yetersiz" }
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            return
        }
        if (force && !qualityPass) {
            Toast.makeText(
                this,
                "Zorla çekim — kapı: ${guide.message.ifBlank { guide.geometryMode }}",
                Toast.LENGTH_LONG,
            ).show()
        }
        captureGuideSnap = guide
        setAwbLock(true)
        // Zaten max MP bağlıysa kamerayı söküp takma (AF kilidi korunur, ~2 sn gecikme sıfırlanır)
        val didRebind = if (needsRebindForMaxStill()) {
            if (!rebindForMaxStill()) {
                capturing.set(false)
                captureGuideSnap = null
                setAwbLock(false)
                Toast.makeText(this, "Max cozunurluk baglanamadi", Toast.LENGTH_LONG).show()
                startCamera()
                return
            }
            focusOnFabricWindow(force = true)
            true
        } else {
            false
        }
        val capture = imageCapture
        if (capture == null) {
            capturing.set(false)
            captureGuideSnap = null
            setAwbLock(false)
            return
        }
        val photoFile = SceneRecordStore.newImageFile(this, "scene_capture")
        val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        binding.btnShot.isEnabled = false
        binding.btnCancel.isEnabled = true
        captureWatchdog?.cancel()
        captureWatchdog = lifecycleScope.launch {
            delay(12_000)
            if (capturing.get()) {
                capturing.set(false)
                captureGuideSnap = null
                okSinceElapsed = -1L
                setAwbLock(false)
                binding.btnShot.isEnabled = true
                Toast.makeText(
                    this@CaptureActivity,
                    "Çekim zaman aşımı — Vazgeç veya tekrar deneyin",
                    Toast.LENGTH_LONG,
                ).show()
                startCamera()
            }
        }

        lifecycleScope.launch {
            if (didRebind) delay(450)
            if (!capturing.get() || isFinishing) return@launch
            capture.takePicture(
                output,
                ContextCompat.getMainExecutor(this@CaptureActivity),
                object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    captureWatchdog?.cancel()
                    lifecycleScope.launch {
                        val post = withContext(Dispatchers.Default) {
                            PostCaptureVerifier.verify(photoFile)
                        }
                        if (!post.pass && !force) {
                            withContext(Dispatchers.IO) {
                                try {
                                    photoFile.delete()
                                } catch (_: Exception) {
                                }
                            }
                            capturing.set(false)
                            captureGuideSnap = null
                            okSinceElapsed = -1L
                            setAwbLock(false)
                            binding.btnShot.isEnabled = true
                            binding.guideText.setBackgroundColor(
                                ContextCompat.getColor(this@CaptureActivity, R.color.guide_bad_bg),
                            )
                            binding.guideText.text =
                                "!  CEKIM TEKRAR (${post.reason}) — cekim bitene kadar sabit tutun"
                            try {
                                coach.onGuide(CoachKey.BLUR)
                            } catch (_: Exception) {
                            }
                            if (didRebind) {
                                startCamera()
                            } else {
                                focusOnFabricWindow(force = true)
                            }
                            return@launch
                        }

                        if (post.fullWidth > 0 && post.fullHeight > 0) {
                            resolutionW = post.fullWidth
                            resolutionH = post.fullHeight
                        } else {
                            resolutionW = capture.resolutionInfo?.resolution?.width
                            resolutionH = capture.resolutionInfo?.resolution?.height
                        }
                        optics = CameraOptics.from(focalMm, sensorSize, resolutionW, resolutionH)
                            ?: optics
                        try {
                            cameraProvider?.unbindAll()
                        } catch (_: Exception) {
                        }
                        try {
                            coach.quiet()
                            coach.onGuide(CoachKey.CAPTURE)
                        } catch (_: Exception) {
                        }

                        val g = captureGuideSnap ?: latestGuide.get()
                        val opticHole = optics?.holeSidePx(activeWindowFraction)
                            ?: (minOf(resolutionW ?: 0, resolutionH ?: 0) * activeWindowFraction).toInt()
                        val capShort = minOf(resolutionW ?: 0, resolutionH ?: 0)
                        val measScale = if (g.analysisShortPx > 0 && capShort > 0) {
                            capShort.toDouble() / g.analysisShortPx
                        } else {
                            1.0
                        }
                        val ppm = when {
                            post.ringOk && post.pxPerMm > 1.0 -> post.pxPerMm
                            g.cornersSeated && g.pxPerMm > 1.0 -> g.pxPerMm * measScale
                            else -> opticHole / 100.0
                        }
                        val holePx = when {
                            post.ringOk && post.holeSidePx > 0 -> post.holeSidePx
                            g.cornersSeated && g.pxPerMm > 1.0 -> (ppm * 100.0).toInt()
                            else -> opticHole
                        }
                        val finalPass = qualityPass && post.pass
                        val guidanceText = when {
                            !post.pass ->
                                "POST_REJECT|${post.reason}|edge=${"%.1f".format(Locale.US, post.meanEdgeStep)}|clip=${"%.2f".format(Locale.US, post.whiteClipRatio)}"
                            force && !qualityPass ->
                                "FORCE|${g.message}|mode=${g.geometryMode}|net=${"%.1f".format(Locale.US, g.rawSharpness)}|egim=${"%.1f".format(Locale.US, g.rawSkewPx)}|kenar=${"%.1f".format(Locale.US, g.borderScore)}"
                            else -> g.message
                        }
                        val expDec = latestExposureDecision
                        val measuredLuma = if (post.ringOk && post.whiteRingMean > 0.0) {
                            post.whiteRingMean
                        } else {
                            expDec?.measuredLuma
                                ?: g.lightEma.takeIf { it > 0 }
                                ?: g.luma
                        }
                        val meta = CaptureMeta(
                            iso = latestIso.get(),
                            exposureNs = latestExposureNs.get(),
                            focalLengthMm = focalMm,
                            resolutionW = resolutionW,
                            resolutionH = resolutionH,
                            sensorWidthMm = sensorSize?.width,
                            sensorHeightMm = sensorSize?.height,
                            cameraId = cameraId,
                            lumaMean = if (post.ringOk) post.pureLumaMean else g.luma,
                            edgeLuma = g.edgeLuma,
                            centerStd = if (post.ringOk) post.pureStd else g.centerStd,
                            sharpness = if (post.ringOk) post.meanEdgeStep else g.sharpness,
                            skewPx = g.skewPx,
                            guidance = guidanceText,
                            framedOk = finalPass,
                            autoCaptured = auto,
                            geometryMode = if (!finalPass) "force_${g.geometryMode}" else g.geometryMode,
                            paperEdgesVisible = g.paperEdgesVisible,
                            colorRingScore = g.colorRingScore,
                            windowFraction = activeWindowFraction.toDouble(),
                            holeSidePx = holePx,
                            pxPerMm = ppm,
                            exposurePolicyState = expDec?.state?.name,
                            targetLumaBand = expDec?.targetLumaBand ?: "white:165-236",
                            measuredLuma = measuredLuma,
                            exposureOk = when {
                                post.ringOk && post.whiteClipRatio > 0.30 -> false
                                expDec == null -> false
                                !expDec.exposureOk -> false
                                autoPolicy -> expDec.state == ExposurePolicyState.EXPOSURE_OK
                                else -> true
                            },
                            lightGuidance = expDec?.guidance ?: g.message,
                            ringLeft = if (post.ringOk) post.ringLeft else g.ringLeft,
                            ringRight = if (post.ringOk) post.ringRight else g.ringRight,
                            ringTop = if (post.ringOk) post.ringTop else g.ringTop,
                            ringBottom = if (post.ringOk) post.ringBottom else g.ringBottom,
                            glareRatio = if (post.ringOk) post.glareRatio else g.glareRatio,
                            focusRegion = "ring_high_contrast_border",
                        )
                        val appCtx = applicationContext
                        val op = operator
                        val batch = batchId
                        val fab = fabric
                        val torch = torchOn
                        val torchAvail = torchAvailable
                        try {
                            withContext(Dispatchers.IO) {
                                SceneRecordStore.saveRecord(
                                    context = appCtx,
                                    operator = op,
                                    batchId = batch,
                                    fabric = fab,
                                    purpose = "scene_capture",
                                    imageFile = photoFile,
                                    torchOn = torch,
                                    torchAvailable = torchAvail,
                                    meta = meta,
                                )
                            }
                            Toast.makeText(
                                this@CaptureActivity,
                                when {
                                    auto -> "Otomatik ~${holePx}px (${"%.1f".format(ppm)} px/mm)"
                                    !finalPass -> "Zorla kaydedildi — train_eligible=false (${post.reason})"
                                    else -> "Kaydedildi ~${holePx}px"
                                },
                                Toast.LENGTH_SHORT,
                            ).show()
                        } catch (e: Exception) {
                            Log.e(TAG, "saveRecord failed", e)
                            Toast.makeText(
                                this@CaptureActivity,
                                "Kayıt hatası: ${e.message}",
                                Toast.LENGTH_LONG,
                            ).show()
                        } finally {
                            capturing.set(false)
                            if (!isFinishing) finish()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "capture failed", exception)
                    captureWatchdog?.cancel()
                    capturing.set(false)
                    captureGuideSnap = null
                    okSinceElapsed = -1L
                    setAwbLock(false)
                    binding.btnShot.isEnabled = true
                    Toast.makeText(
                        this@CaptureActivity,
                        "Çekim hatası: ${exception.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                    if (!isFinishing && !isDestroyed) {
                        startCamera()
                    }
                }
            },
            )
        }
    }

    override fun onDestroy() {
        captureWatchdog?.cancel()
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        super.onDestroy()
        coach.release()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_OPERATOR = "operator"
        const val EXTRA_BATCH = "batch"
        const val EXTRA_FABRIC_STATE = "fabric_state"
        const val EXTRA_LABEL = "label"
        const val EXTRA_FABRIC_TYPE = "fabric_type"
        const val EXTRA_FABRIC_COLOR = "fabric_color"
        const val EXTRA_ORGU = "orgu"
        const val EXTRA_SIP = "sip"
        const val EXTRA_ISEMRI = "isemri"
        const val EXTRA_MAKINA = "makina"
        const val EXTRA_PARTI = "parti"
        private const val TAG = "CaptureActivity"
    }
}
