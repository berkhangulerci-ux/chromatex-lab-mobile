package org.chromatex.lab.data

/**
 * Çekim anı meta — master JPEG ile birlikte kaydedilir.
 * [framedOk] + [exposureOk] train-grade kapılarıdır; FORCE zayıf karede ikisi de false kalır.
 *
 * [ringLeft]/[ringRight]/[ringTop]/[ringBottom]: siyah halka iç kenarları,
 * analiz çözünürlüğüne normalize (0.0..1.0). Tespit yoksa -1.0.
 */
data class CaptureMeta(
    val iso: Int? = null,
    val exposureNs: Long? = null,
    val focalLengthMm: Float? = null,
    val resolutionW: Int? = null,
    val resolutionH: Int? = null,
    val sensorWidthMm: Float? = null,
    val sensorHeightMm: Float? = null,
    val cameraId: String? = null,
    val lumaMean: Double? = null,
    val edgeLuma: Double? = null,
    val centerStd: Double? = null,
    val sharpness: Double? = null,
    val skewPx: Double? = null,
    val guidance: String? = null,
    val framedOk: Boolean = false,
    val autoCaptured: Boolean = false,
    val geometryMode: String? = null,
    val paperEdgesVisible: Int? = null,
    val colorRingScore: Double? = null,
    val windowFraction: Double? = null,
    val holeSidePx: Int? = null,
    val pxPerMm: Double? = null,
    val exposurePolicyState: String? = null,
    val targetLumaBand: String? = null,
    val measuredLuma: Double? = null,
    val exposureOk: Boolean? = null,
    val lightGuidance: String? = null,
    /** Analiz karesinde normalize siyah-halka iç kenar X (sol). */
    val ringLeft: Double? = null,
    val ringRight: Double? = null,
    val ringTop: Double? = null,
    val ringBottom: Double? = null,
    /** Kumaş deliğindeki specular parlama oranı (luma>235). */
    val glareRatio: Double? = null,
    /** AF ölçüm bölgesi — düz kumaş merkezi değil halka kenarı. */
    val focusRegion: String? = "ring_high_contrast_border",
)
