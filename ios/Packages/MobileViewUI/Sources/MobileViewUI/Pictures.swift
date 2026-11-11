import Foundation
import ImageIO
import UIKit

/**
 * Pictures in mobile views, decoded off the main thread at about the size they
 * show, rather than the size they were sent at, and kept a while by entry,
 * so scrolling back to one doesn't decode it again.
 */
enum Pictures {
    private nonisolated(unsafe) static let kept: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.totalCostLimit = keptBytes
        return cache
    }()

    private static let keptBytes = 24 << 20

    static func kept(_ id: String) -> UIImage? { kept.object(forKey: id as NSString) }

    /** [data] as a picture that fills [width] by [height] pixels, or as near as halving it allows. */
    static func decode(_ id: String, _ data: Data, width: Int, height: Int) async -> UIImage? {
        if let image = kept(id) { return image }
        let image = await Task.detached(priority: .userInitiated) { () -> UIImage? in
            guard let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                  let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                  let w = props[kCGImagePropertyPixelWidth] as? Int,
                  let h = props[kCGImagePropertyPixelHeight] as? Int
            else { return nil }
            var sample = 1
            while w / (sample * 2) >= width && h / (sample * 2) >= height { sample *= 2 }
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceShouldCacheImmediately: true,
                kCGImageSourceThumbnailMaxPixelSize: max(w, h) / sample,
            ]
            guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
            return UIImage(cgImage: cg)
        }.value
        if let image { kept.setObject(image, forKey: id as NSString, cost: Int(image.size.width * image.size.height) * 4) }
        return image
    }
}
