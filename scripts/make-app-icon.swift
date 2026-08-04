import AppKit
import ImageIO
import CoreGraphics
import CoreText
import Foundation

// LARK app icon: the wordmark's gold "L" on the app background, 1024×1024, fully opaque and
// square (iOS applies its own corner mask, so a pre-rounded or transparent icon is rejected).
//
// PLACEHOLDER, and deliberately reproducible rather than a committed binary nobody can regenerate.
// It exists because App Store Connect rejects an upload with no icon at all, and it uses the app's
// own two colours so a TestFlight build does not look broken. Replace with the real mark from the
// design source when there is one.
//
//   swift scripts/make-app-icon.swift iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png
let size = 1024
let background = CGColor(red: 0x0B / 255.0, green: 0x0C / 255.0, blue: 0x0E / 255.0, alpha: 1)
let gold = CGColor(red: 0xE8 / 255.0, green: 0xC1 / 255.0, blue: 0x5C / 255.0, alpha: 1)

guard let context = CGContext(
    data: nil,
    width: size,
    height: size,
    bitsPerComponent: 8,
    bytesPerRow: 0,
    space: CGColorSpaceCreateDeviceRGB(),
    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
) else { fatalError("no context") }

context.setFillColor(background)
context.fill(CGRect(x: 0, y: 0, width: size, height: size))

// Tracking matches the home screen's letterspaced wordmark feel; the single glyph reads at 60pt
// where the full word would not.
let font = CTFontCreateWithName("SFPro-Display-Bold" as CFString, 660, nil)
let attributes: [NSAttributedString.Key: Any] = [
    .font: font,
    .foregroundColor: gold,
    .kern: 0,
]
let line = CTLineCreateWithAttributedString(
    NSAttributedString(string: "L", attributes: attributes)
)

// Centre on the glyph's own ink, not on its typographic box: an "L" has asymmetric side bearings,
// so trusting the advance width leaves it visibly off-centre.
let bounds = CTLineGetBoundsWithOptions(line, .useGlyphPathBounds)
context.textPosition = CGPoint(
    x: (CGFloat(size) - bounds.width) / 2 - bounds.minX,
    y: (CGFloat(size) - bounds.height) / 2 - bounds.minY
)
CTLineDraw(line, context)

guard let image = context.makeImage() else { fatalError("no image") }
let out = URL(fileURLWithPath: CommandLine.arguments[1])
guard let dest = CGImageDestinationCreateWithURL(out as CFURL, "public.png" as CFString, 1, nil) else {
    fatalError("no destination")
}
CGImageDestinationAddImage(dest, image, nil)
guard CGImageDestinationFinalize(dest) else { fatalError("write failed") }
print("wrote \(out.path)")
