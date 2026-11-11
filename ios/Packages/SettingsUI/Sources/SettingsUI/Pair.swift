import AVFoundation
import SessionCore
import SwiftUI
import UIKit
import VisionKit
import WallflowerDesign

/**
 * The camera, reading QR codes until one is a pairing code ([onPair]); or,
 * where there's no camera that can, or wallflower may not use it, why not.
 */
struct PairScanner: View {
    @Environment(\.palette) private var palette
    @Environment(\.haptics) private var haptics
    let onPair: (Pairing) -> Void
    let onClose: () -> Void
    @State private var camera: Camera?
    @State private var other = false

    private enum Camera { case ready, unsupported, denied }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            PageHeader("scan the code", onBack: nil) {
                Button(action: onClose) {
                    GlyphIcon(.close, palette.grey, size: 14).padding(10).contentShape(Circle())
                }
                .buttonStyle(Pressable())
                .accessibilityLabel("close")
            }
            switch camera {
            case .ready:
                CodeScanner(onPair: { p in
                    haptics.confirm()
                    onPair(p)
                }, onOther: { other = true })
                .clipShape(RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal, 20)
                Paragraph(other ? "That code isn't wallflower's. Scan the one your computer shows." : "Point the camera at the code your computer shows.")
                    .padding(.horizontal, 20)
                    .padding(.bottom, 20)
            case .unsupported:
                Paragraph("There's no camera here that can read the code. Set up by hand instead.")
                    .padding(.horizontal, 20)
                Spacer()
            case .denied:
                Paragraph("wallflower isn't allowed the camera. Let it in Settings, then scan again.")
                    .padding(.horizontal, 20)
                WordButton("open settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                }
                .padding(.horizontal, 20)
                Spacer()
            case nil:
                Spacer()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(palette.bg0.ignoresSafeArea())
        .task {
            guard DataScannerViewController.isSupported else {
                camera = .unsupported
                return
            }
            if AVCaptureDevice.authorizationStatus(for: .video) == .notDetermined {
                _ = await AVCaptureDevice.requestAccess(for: .video)
            }
            camera = DataScannerViewController.isAvailable ? .ready : .denied
        }
    }
}

/** VisionKit's scanner, for QR codes: the first that's a pairing code stops it. */
private struct CodeScanner: UIViewControllerRepresentable {
    let onPair: (Pairing) -> Void
    let onOther: () -> Void

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced, isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        context.coordinator.parent = self
        if !scanner.isScanning && !context.coordinator.found { try? scanner.startScanning() }
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Coordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        var parent: CodeScanner
        var found = false

        init(_ parent: CodeScanner) { self.parent = parent }

        func dataScanner(_ scanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            read(addedItems, scanner)
        }

        func dataScanner(_ scanner: DataScannerViewController, didUpdate updatedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            read(updatedItems, scanner)
        }

        private func read(_ items: [RecognizedItem], _ scanner: DataScannerViewController) {
            guard !found else { return }
            for item in items {
                guard case .barcode(let code) = item, let text = code.payloadStringValue else { continue }
                guard let url = URL(string: text), let pairing = Pairing(url: url) else {
                    parent.onOther()
                    continue
                }
                found = true
                scanner.stopScanning()
                parent.onPair(pairing)
                return
            }
        }
    }
}
