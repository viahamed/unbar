// QRCodeView.swift — renders a string as a QR code with CoreImage.
//
// Used for config export: the QR payload is the same `unbar://add?c=...`
// link as the copyable text. No camera scanning is needed for import;
// import works by pasting a link.

import SwiftUI
import CoreImage
import CoreImage.CIFilterBuiltins

public struct QRCodeView: View {
    public var text: String
    public var size: CGFloat

    public init(text: String, size: CGFloat = 220) {
        self.text = text
        self.size = size
    }

    public var body: some View {
        Image(uiImage: QRCodeView.makeImage(from: text) ?? UIImage())
            .interpolation(.none)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .padding(12)
            .background(Color.white)
            .cornerRadius(12)
    }

    public static func makeImage(from text: String) -> UIImage? {
        guard let data = text.data(using: .utf8) else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = data
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scale = 220.0 / output.extent.width
        let scaled = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        let context = CIContext(options: nil)
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }
}
