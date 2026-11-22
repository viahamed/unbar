// QRScannerView.swift — camera QR scanner for config import.
//
// A plain AVFoundation capture session (no third-party dependency):
// the back camera, a QR metadata output, one scan then stop. The
// scanned payload is the same `unbar://add?c=...` link the Paste
// field accepts, so the caller pre-fills the Import page with it
// and the Add sheet re-opens on that page. Requires the camera
// usage description in Info.plist.

import SwiftUI
import AVFoundation

public struct QRScannerView: View {
    @EnvironmentObject private var loc: LocalizationManager
    @Environment(\.presentationMode) private var presentationMode

    public var onCode: (String) -> Void

    @State private var denied = false

    public init(onCode: @escaping (String) -> Void) {
        self.onCode = onCode
    }

    public var body: some View {
        NavigationView {
            ZStack {
                if denied {
                    VStack(spacing: 12) {
                        Image(systemName: "camera.fill")
                            .font(.largeTitle)
                            .foregroundColor(.secondary)
                        Text(loc.tr("servers.scan_qr_no_permission"))
                            .multilineTextAlignment(.center)
                            .padding(.horizontal)
                    }
                } else {
                    QRScannerCameraView(onCode: { code in
                        onCode(code)
                        presentationMode.wrappedValue.dismiss()
                    }, onDenied: {
                        denied = true
                    })
                    VStack {
                        Spacer()
                        Text(loc.tr("servers.scan_qr_hint"))
                            .font(.footnote)
                            .foregroundColor(.white)
                            .padding(8)
                            .background(Color.black.opacity(0.6))
                            .cornerRadius(8)
                            .padding(.bottom, 32)
                    }
                }
            }
            .navigationBarTitle(loc.tr("servers.scan_qr"), displayMode: .inline)
            .navigationBarItems(trailing: Button(loc.tr("common.cancel")) {
                presentationMode.wrappedValue.dismiss()
            })
        }
        .onAppear {
            if AVCaptureDevice.authorizationStatus(for: .video) == .denied {
                denied = true
            }
        }
    }
}

/// The capture session itself: back camera + QR metadata output.
/// Reports the first QR string once, then stops the session.
private struct QRScannerCameraView: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    var onDenied: () -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onCode = onCode
        controller.onDenied = onDenied
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController,
                                context: Context) {}
}

private final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onDenied: (() -> Void)?
    private var session: AVCaptureSession?
    private var didReport = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            startSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.startSession()
                    } else {
                        self?.onDenied?()
                    }
                }
            }
        default:
            onDenied?()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        session?.stopRunning()
    }

    private func startSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device) else {
            onDenied?()
            return
        }
        let session = AVCaptureSession()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            onDenied?()
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
        output.metadataObjectTypes = [.qr]
        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        self.session = session
        session.startRunning()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput,
                        didOutput metadataObjects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !didReport else { return }
        for object in metadataObjects {
            if let code = object as? AVMetadataMachineReadableCodeObject,
               let string = code.stringValue, !string.isEmpty {
                didReport = true
                session?.stopRunning()
                onCode?(string)
                return
            }
        }
    }
}
