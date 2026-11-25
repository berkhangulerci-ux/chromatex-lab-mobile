import AVFoundation
import SwiftUI
import UIKit

struct CameraPreviewView: UIViewRepresentable {
    let session: AVCaptureSession
    var holeFraction: Double = CameraOptics.compositionFraction
    var onTap: ((CGPoint) -> Void)?

    func makeUIView(context: Context) -> PreviewUIView {
        let view = PreviewUIView()
        view.previewLayer.session = session
        // Letterbox so yellow hole overlay matches JPEG analysis geometry (not aspectFill crop).
        view.previewLayer.videoGravity = .resizeAspect
        view.holeFraction = holeFraction
        view.onTap = onTap
        return view
    }

    func updateUIView(_ uiView: PreviewUIView, context: Context) {
        uiView.previewLayer.session = session
        uiView.previewLayer.videoGravity = .resizeAspect
        uiView.holeFraction = holeFraction
        uiView.onTap = onTap
        uiView.setNeedsLayout()
    }

    final class PreviewUIView: UIView {
        var onTap: ((CGPoint) -> Void)?
        var holeFraction: Double = CameraOptics.compositionFraction
        private lazy var tap = UITapGestureRecognizer(target: self, action: #selector(handleTap(_:)))
        private let holeHost = UIView()
        private let holeBorder = UIView()

        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var previewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }

        override init(frame: CGRect) {
            super.init(frame: frame)
            backgroundColor = .black
            holeHost.isUserInteractionEnabled = false
            holeBorder.isUserInteractionEnabled = false
            holeBorder.layer.borderWidth = 3
            holeBorder.layer.borderColor = UIColor(red: 1, green: 0.84, blue: 0, alpha: 1).cgColor
            holeBorder.layer.cornerRadius = 4
            holeBorder.backgroundColor = UIColor.white.withAlphaComponent(0.04)
            addSubview(holeHost)
            holeHost.addSubview(holeBorder)
            addGestureRecognizer(tap)
        }

        required init?(coder: NSCoder) {
            super.init(coder: coder)
            addGestureRecognizer(tap)
        }

        override func layoutSubviews() {
            super.layoutSubviews()
            let video = previewLayer.layerRectConverted(fromMetadataOutputRect: CGRect(x: 0, y: 0, width: 1, height: 1))
            let rect = video.isNull || video.isEmpty ? bounds : video
            holeHost.frame = rect
            let side = min(rect.width, rect.height) * CGFloat(holeFraction)
            holeBorder.bounds = CGRect(x: 0, y: 0, width: side, height: side)
            holeBorder.center = CGPoint(x: rect.width / 2, y: rect.height / 2)
        }

        @objc private func handleTap(_ g: UITapGestureRecognizer) {
            let p = g.location(in: self)
            let n = previewLayer.captureDevicePointConverted(fromLayerPoint: p)
            onTap?(n)
        }
    }
}
