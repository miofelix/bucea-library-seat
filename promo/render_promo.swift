import AppKit
import AVFoundation
import CoreGraphics
import CoreImage
import CoreText
import Foundation
import ImageIO

// Native 16:9 motion graphics. All coordinates below use a 1920 × 1080 stage.
// Screens are real app captures; large callouts are editorial illustrations.
let W = 1920.0, H = 1080.0
let duration = 45.0, fps: Int32 = 60
let root = URL(fileURLWithPath: CommandLine.arguments[1])
let output = URL(fileURLWithPath: CommandLine.arguments[2])
let previewOnly = CommandLine.arguments.contains("--stills")
let assetDir = root.appendingPathComponent("promo/assets")
let previewDir = root.appendingPathComponent("promo/.render/landscape")
try FileManager.default.createDirectory(at: assetDir, withIntermediateDirectories: true)
try FileManager.default.createDirectory(at: previewDir, withIntermediateDirectories: true)

func color(_ hex: UInt32, _ alpha: Double = 1) -> CGColor {
    CGColor(red: Double((hex >> 16) & 255) / 255, green: Double((hex >> 8) & 255) / 255, blue: Double(hex & 255) / 255, alpha: alpha)
}
let purple: UInt32 = 0x6C397F
let ink: UInt32 = 0x222126
let gray: UInt32 = 0x7E7A86
let white: UInt32 = 0xFBFBFD
var g: CGContext!
let space = CGColorSpace(name: CGColorSpace.sRGB)!
let ciContext = CIContext(options: [.cacheIntermediates: true])

func clamp(_ x: Double, _ a: Double = 0, _ b: Double = 1) -> Double { min(b, max(a, x)) }
// Quintic curves start and finish with zero velocity and acceleration.
func smooth(_ x: Double) -> Double { let t = clamp(x); return t*t*t*(10+t*(-15+6*t)) }
func ease(_ x: Double) -> Double { smooth(x) }
func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
func arrival(_ t: Double, _ delay: Double = 0, _ d: Double = 1.2) -> Double { smooth((t - delay) / d) }
func blendColor(_ a: UInt32, _ b: UInt32, _ amount: Double) -> UInt32 {
    var result: UInt32 = 0
    for shift in [16,8,0] {
        let channel = lerp(Double((a >> shift) & 255),Double((b >> shift) & 255),clamp(amount))
        result |= UInt32(channel.rounded()) << shift
    }
    return result
}
func layer(_ opacity: Double = 1, _ dx: Double = 0, _ dy: Double = 0, _ scale: Double = 1, _ draw: () -> Void) {
    guard opacity > 0.00001 else { return }
    g.saveGState(); g.setAlpha(clamp(opacity)); g.beginTransparencyLayer(auxiliaryInfo:nil)
    g.translateBy(x:W/2+dx,y:H/2+dy); g.scaleBy(x:scale,y:scale); g.translateBy(x:-W/2,y:-H/2)
    draw()
    g.endTransparencyLayer(); g.restoreGState()
}

func bitmap(_ w: Int, _ h: Int, _ draw: () -> Void) -> CGImage {
    let old = g
    let c = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: space,
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    g = c
    g.translateBy(x: 0, y: Double(h))
    g.scaleBy(x: 1, y: -1)
    draw()
    let result = c.makeImage()!
    g = old
    return result
}

func fill(_ hex: UInt32, _ alpha: Double = 1) { g.setFillColor(color(hex, alpha)); g.fill(CGRect(x: 0, y: 0, width: W, height: H)) }
func roundRect(_ r: CGRect, _ radius: Double, _ hex: UInt32, _ alpha: Double = 1, stroke: UInt32? = nil, line: Double = 1) {
    let p = CGPath(roundedRect: r, cornerWidth: radius, cornerHeight: radius, transform: nil)
    g.addPath(p); g.setFillColor(color(hex, alpha)); g.fillPath()
    if let stroke = stroke { g.addPath(p); g.setStrokeColor(color(stroke, alpha)); g.setLineWidth(line); g.strokePath() }
}
func circle(_ x: Double, _ y: Double, _ radius: Double, _ hex: UInt32, _ alpha: Double = 1) {
    g.setFillColor(color(hex, alpha)); g.fillEllipse(in: CGRect(x: x-radius, y: y-radius, width: radius*2, height: radius*2))
}
func line(_ points: [CGPoint], _ hex: UInt32, _ width: Double = 2, _ alpha: Double = 1) {
    guard let first = points.first else { return }
    g.beginPath(); g.move(to: first)
    for p in points.dropFirst() { g.addLine(to: p) }
    g.setStrokeColor(color(hex, alpha)); g.setLineWidth(width); g.setLineCap(.round); g.setLineJoin(.round); g.strokePath()
}
func image(_ img: CGImage, _ r: CGRect, _ alpha: Double = 1) {
    g.saveGState(); g.setAlpha(alpha)
    g.translateBy(x: r.minX, y: r.maxY); g.scaleBy(x: 1, y: -1)
    g.interpolationQuality = .high
    g.draw(img, in: CGRect(x: 0, y: 0, width: r.width, height: r.height))
    g.restoreGState()
}
func clippedImage(_ img: CGImage, _ r: CGRect, _ radius: Double, _ alpha: Double = 1) {
    g.saveGState(); g.addPath(CGPath(roundedRect: r, cornerWidth: radius, cornerHeight: radius, transform: nil)); g.clip()
    image(img, r, alpha); g.restoreGState()
}

var textCache: [String: CTLine] = [:]
@discardableResult
func text(_ s: String, _ x: Double, _ y: Double, _ size: Double, _ hex: UInt32 = ink, _ weight: String = "regular", _ alpha: Double = 1, _ align: String = "left", _ kern: Double = 0) -> Double {
    let cacheKey = "\(s)|\(size)|\(hex)|\(weight)|\(kern)"
    let l: CTLine
    if let cached = textCache[cacheKey] { l = cached }
    else {
        let name = weight == "semibold" ? "PingFangSC-Semibold" : weight == "medium" ? "PingFangSC-Medium" : "PingFangSC-Regular"
        let f = CTFontCreateWithName(name as CFString, size, nil)
        let attrs: [NSAttributedString.Key: Any] = [
            NSAttributedString.Key(kCTFontAttributeName as String): f,
            NSAttributedString.Key(kCTForegroundColorAttributeName as String): color(hex),
            NSAttributedString.Key(kCTKernAttributeName as String): kern
        ]
        l = CTLineCreateWithAttributedString(NSAttributedString(string: s, attributes: attrs)); textCache[cacheKey] = l
    }
    var ascent: CGFloat = 0, descent: CGFloat = 0
    let width = CTLineGetTypographicBounds(l, &ascent, &descent, nil)
    let xx = align == "center" ? x-width/2 : align == "right" ? x-width : x
    g.saveGState(); g.setAlpha(clamp(alpha)); g.translateBy(x: xx, y: y + Double(ascent)); g.scaleBy(x: 1, y: -1)
    g.textMatrix = .identity; g.textPosition = .zero; CTLineDraw(l, g); g.restoreGState()
    return width
}
func label(_ s: String, _ x: Double, _ y: Double, _ alpha: Double = 1, _ hex: UInt32 = purple) {
    text(s, x, y, 19, hex, "medium", alpha, "left", 2.6)
}
func title(_ s: String, _ x: Double, _ y: Double, _ size: Double = 84, _ alpha: Double = 1, _ hex: UInt32 = ink) {
    text(s, x, y, size, hex, "semibold", alpha, "left", -1.4)
}
func sub(_ s: String, _ x: Double, _ y: Double, _ alpha: Double = 1, _ hex: UInt32 = gray) {
    text(s, x, y, 28, hex, "regular", alpha)
}
func shadowCard(_ r: CGRect, _ radius: Double = 30, _ hex: UInt32 = 0xFFFFFF, _ alpha: Double = 1) {
    g.saveGState(); g.setShadow(offset: CGSize(width: 0, height: 14), blur: 40, color: color(0x21142B, 0.09*alpha))
    roundRect(r, radius, hex, alpha); g.restoreGState()
}

func load(_ rel: String) -> CGImage {
    let url = root.appendingPathComponent(rel)
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil), let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        fputs("Missing image: \(rel)\n", stderr); exit(3)
    }
    return img
}
func savePNG(_ img: CGImage, _ url: URL) {
    let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.png" as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, img, nil); CGImageDestinationFinalize(dest)
}
func crop(_ img: CGImage, _ rect: CGRect) -> CGImage { img.cropping(to: rect)! }

let paths: [String: String] = [
    "home": ".private/qa-compact-layout-20261015/final/home.png",
    "search": ".private/qa-compact-layout-20261015/final/seats.png",
    "filter": ".private/qa-compact-layout-20261015/seat-order-1.png",
    "map": ".private/qa-1.0.0/release-map/03-secondary-tabs-scrolled-away.png",
    "seats": ".private/qa-1.0.0/release-map/01-region-cards.png",
    "booking": ".private/qa-1.0.0/compressed-arm64-smoke/02-reservation-review-no-submit.png",
    "hub": ".private/qa-compact-layout-20261015/final/hub.png",
    "settings": ".private/qa-compact-layout-20261015/final/settings.png"
]
var screens: [String: CGImage] = [:]
for (key, path) in paths {
    let exported = assetDir.appendingPathComponent("\(key).png")
    if FileManager.default.fileExists(atPath: exported.path) && !FileManager.default.fileExists(atPath: root.appendingPathComponent(path).path) {
        screens[key] = load("promo/assets/\(key).png")
    } else {
        let raw = load(path)
        // Remove status / gesture bars, and replace the personal greeting.
        let clean = bitmap(raw.width, raw.height) {
            image(raw, CGRect(x: 0, y: 0, width: raw.width, height: raw.height))
            if key == "home" {
                roundRect(CGRect(x: 50, y: 232, width: 840, height: 112), 0, 0xFAFAFA)
                text("下午好，同学", 58, 246, 76, ink, "regular")
            }
            if key == "booking" {
                roundRect(CGRect(x: 96, y: 1920, width: 1000, height: 112), 0, 0xF0EAF2)
                text("预约账号：演示同学", 120, 1940, 38, gray, "regular")
            }
        }
        let screen = crop(clean, CGRect(x: 0, y: 142, width: raw.width, height: raw.height-214))
        screens[key] = screen; savePNG(screen, exported)
    }
}
let logo = load("website/assets/icon.png")
let regionCards = crop(screens["home"]!, CGRect(x: 42, y: 1710, width: 1132, height: 520))
let regionExtraPath = assetDir.appendingPathComponent("region-extra.png")
let regionExtra: CGImage
if FileManager.default.fileExists(atPath: root.appendingPathComponent(".private/qa-compact-tabs-20261015/home-notices.png").path) {
    regionExtra = crop(load(".private/qa-compact-tabs-20261015/home-notices.png"),CGRect(x:80,y:650,width:1050,height:520))
    savePNG(regionExtra,regionExtraPath)
} else { regionExtra = load("promo/assets/region-extra.png") }
let mapDetail = crop(screens["map"]!, CGRect(x: 335, y: 1290, width: 475, height: 270))

// Cache each device once, including the metal rim, buttons, rounded glass and UI.
var phones: [String: CGImage] = [:]
for (key, screen) in screens {
    phones[key] = bitmap(600, 1210) {
        roundRect(CGRect(x: 4, y: 6, width: 592, height: 1198), 75, 0xB9B7BF)
        roundRect(CGRect(x: 9, y: 11, width: 582, height: 1188), 71, 0xEEEDEF, stroke: 0xF6F6F7, line: 2)
        roundRect(CGRect(x: 15, y: 17, width: 570, height: 1176), 65, 0x17161A)
        let r = CGRect(x: 20, y: 22, width: 560, height: 1166)
        clippedImage(screen, r, 57)
        roundRect(CGRect(x: 272, y: 32, width: 56, height: 6), 3, 0x1D1B21)
        roundRect(CGRect(x: 0, y: 245, width: 5, height: 95), 2, 0xABA8B0)
        roundRect(CGRect(x: 595, y: 290, width: 5, height: 150), 2, 0xABA8B0)
    }
}

struct PhonePose {
    var key: String
    var x: Double, y: Double, height: Double, angle: Double = 0, tilt: Double = 0
}
func drawDevice(_ pose: PhonePose, _ alpha: Double = 1, _ nextScreen: String? = nil, _ screenMix: Double = 0) {
    guard let img = phones[pose.key] else { return }
    // Apply opacity to a group so nested image drawing cannot override it.
    layer(alpha) {
        g.saveGState(); g.translateBy(x:pose.x,y:pose.y);g.rotate(by:pose.angle*Double.pi/180)
        g.concatenate(CGAffineTransform(a:1-abs(pose.tilt)*0.10,b:pose.tilt*0.055,c:pose.tilt*0.035,d:1,tx:0,ty:0))
        let scale = pose.height/1210
        g.scaleBy(x:scale,y:scale);g.translateBy(x:-300,y:-605)
        g.setShadow(offset:CGSize(width:3/scale,height:17/scale),blur:32/scale,color:color(0x24192C,0.12))
        image(img,CGRect(x:0,y:0,width:600,height:1210))
        g.setShadow(offset:.zero,blur:0,color:nil)
        // One opaque chassis and one shadow. Only the display content dissolves.
        if let key = nextScreen, key != pose.key, let screen = screens[key], screenMix > 0 {
            clippedImage(screen,CGRect(x:20,y:22,width:560,height:1166),57,screenMix)
            roundRect(CGRect(x:272,y:32,width:56,height:6),3,0x1D1B21)
        }
        g.restoreGState()
    }
}
func device(_ key: String, _ cx: Double, _ cy: Double, _ h: Double, _ angle: Double = 0, _ alpha: Double = 1, _ tilt: Double = 0) {
    drawDevice(PhonePose(key:key,x:cx,y:cy,height:h,angle:angle,tilt:tilt),alpha)
}
func chip(_ s: String, _ x: Double, _ y: Double, _ w: Double, selected: Bool = false, alpha: Double = 1, selection: Double? = nil) {
    let mix = clamp(selection ?? (selected ? 1 : 0))
    layer(alpha) {
        roundRect(CGRect(x:x,y:y,width:w,height:64),32,blendColor(0xFFFFFF,purple,mix),stroke:blendColor(0xE6E3E9,purple,mix),line:1.5)
        text(s,x+w/2,y+13,25,blendColor(0x625B6C,0xFFFFFF,mix),"medium",1,"center")
    }
}
func star(_ cx: Double, _ cy: Double, _ radius: Double, _ hex: UInt32, _ progress: Double = 1, _ solid: Bool = false) {
    let p = CGMutablePath()
    for i in 0..<10 {
        let angle = -Double.pi/2 + Double(i)*Double.pi/5
        let r = i % 2 == 0 ? radius : radius*0.46
        let point = CGPoint(x: cx+cos(angle)*r, y: cy+sin(angle)*r)
        if i == 0 { p.move(to: point) } else { p.addLine(to: point) }
    }
    p.closeSubpath(); g.addPath(p)
    if solid { g.setFillColor(color(hex, progress)); g.fillPath() }
    else { g.setStrokeColor(color(hex, progress)); g.setLineWidth(4); g.setLineJoin(.round); g.strokePath() }
}
func smallIcon(_ kind: String, _ x: Double, _ y: Double, _ hex: UInt32 = purple) {
    if kind == "check" { line([CGPoint(x:x,y:y+12),CGPoint(x:x+8,y:y+20),CGPoint(x:x+24,y:y)],hex,3) }
    if kind == "clock" {
        g.setStrokeColor(color(hex)); g.setLineWidth(3); g.strokeEllipse(in:CGRect(x:x,y:y,width:28,height:28))
        line([CGPoint(x:x+14,y:y+6),CGPoint(x:x+14,y:y+15),CGPoint(x:x+21,y:y+19)],hex,2.5)
    }
}
func sampleNote(_ dark: Bool = false) { text("界面与数据为演示画面", 1790, 1014, 16, dark ? 0xAAA0B7 : 0xA6A1AD, "regular", 0.9, "right") }
func sceneBackground(_ dark: Bool = false) {
    fill(dark ? 0x17121D : white)
    if dark { circle(1470, 590, 485, 0x41264F, 0.22) }
}

let starts: [Double] = [0, 10.0/3, 22.0/3, 32.0/3, 14, 18, 22, 76.0/3, 88.0/3, 98.0/3, 110.0/3, 122.0/3]
let sceneNames = ["开场", "产品亮相", "馆内概况", "区域总览", "习惯筛选", "布局选座", "收藏", "预约安排", "本机保存", "六色主题", "功能全景", "下载"]

// Scene copy is rendered separately from product objects: outgoing headings clear
// before the next headings appear, while the product keeps moving underneath.
let transitionWidths: [Double] = [0,1.15,1.15,1.10,1.15,1.55,1.15,1.15,1.25,1.25,1.20,1.15]
func lead(_ scene: Int) -> Double { transitionWidths[scene]/2 }
func localTime(_ scene: Int, _ time: Double) -> Double { time-starts[scene]+lead(scene) }
func sceneProgress(_ scene: Int, _ t: Double) -> Double {
    let end = scene+1 < starts.count ? starts[scene+1] : duration
    return smooth(t/(end-starts[scene]+lead(scene)))
}
func heroPose(_ scene: Int, _ t: Double) -> PhonePose? {
    let p = sceneProgress(scene,t)
    switch scene {
    case 1: return PhonePose(key:"home",x:1315+10*p,y:555-8*p,height:880*(1+0.020*p),angle:-1.6+0.5*p)
    case 2: return PhonePose(key:"home",x:1416+12*p,y:557-6*p,height:865*(1+0.020*p),angle:3-0.6*p)
    case 4: return PhonePose(key:"filter",x:474-5*p,y:566-10*p,height:875*(1+0.018*p),angle:-2+0.5*p)
    case 5: return PhonePose(key:"map",x:1405+7*p,y:559-6*p,height:875*(1+0.020*p),angle:2-0.4*p)
    case 7: return PhonePose(key:"booking",x:1204-10*p,y:574-6*p,height:870*(1+0.015*p),angle:-6+0.5*p,tilt:0.3)
    case 10:return PhonePose(key:"map",x:958,y:479-8*p,height:656*(1+0.015*p))
    default:return nil
    }
}
func drawBackground(_ darkMix: Double = 0) {
    fill(blendColor(white,0x17121D,darkMix))
    circle(1470,590,485,0x41264F,0.22*darkMix)
}
func drawCopy(_ scene: Int, _ t: Double) {
    let a = arrival(t), lift = 24*(1-a)
    switch scene {
    case 0:
        label("BUCEA  /  LIBRARY",822,285,arrival(t,0.35))
        let aa=arrival(t,0.15,1.4),bb=arrival(t,0.55,1.4)
        text("把时间，",960,375+50*(1-aa),128,ink,"semibold",aa,"center",-4)
        text("留给专注。",960,525+50*(1-bb),128,purple,"semibold",bb,"center",-4)
        text("一张书桌，一段属于自己的时间。",960,760,30,gray,"regular",arrival(t,1.1),"center")
    case 1:
        label("为北建大的每一段学习时光",132,306+lift,a)
        title("北建大",132,370+lift,86,a)
        title("图书馆选座。",132,480+lift,86,a)
        sub("简洁，顺手。刚刚好。",136,638+lift,arrival(t,0.28))
        roundRect(CGRect(x:136,y:720,width:220,height:52),26,0xEDE5F1,arrival(t,0.45))
        text("原生 Android 体验",246,732,21,purple,"medium",arrival(t,0.45),"center")
    case 2:
        label("先看馆内情况",135,145+lift,a)
        title("出发之前，",135,208+lift,90,a)
        title("心中有数。",135,318+lift,90,a)
        sub("馆内概况，一眼看清。",137,468+lift,arrival(t,0.25))
    case 3:
        label("各区域座位",135,139+lift,a)
        title("每一区，",135,208+lift,88,a)
        title("都心中有数。",135,316+lift,88,a)
        sub("馆层与区域，清楚呈现。",137,470+lift,arrival(t,0.25))
        text("今天 · 数据随同步更新",138,740,23,gray,"regular",arrival(t,0.45))
    case 4:
        label("自选座位",908,176+lift,a)
        for (i,s) in ["你的时间。","你的习惯。","你的位置。"].enumerated() {
            let aa=arrival(t,0.12+Double(i)*0.24)
            title(s,906,239+Double(i)*107+24*(1-aa),84,aa,i==2 ? purple : ink)
        }
        sub("按习惯筛选，少几步操作。",911,753,arrival(t,0.85))
    case 5:
        label("布局选座",130,118+lift,a)
        title("喜欢的位置，",130,180+lift,76,a)
        title("就在眼前。",130,273+lift,76,a,purple)
        sub("沿着真实布局，找到你的座位。",133,397+lift,arrival(t,0.25))
    case 6:
        label("收藏常坐的位置",1030,191+lift,a)
        title("熟悉的位置，",1025,260+lift,79,a)
        title("替你记着。",1025,361+lift,79,a,purple)
        sub("长按座位收藏，下次直接预约。",1029,516+lift,arrival(t,0.25))
    case 7:
        label("管理自己的预约",132,153+lift,a)
        title("选好时间，",130,216+lift,86,a)
        title("安心安排。",130,323+lift,86,a,purple)
        sub("预约、记录与到馆签到，有序进行。",134,478+lift,arrival(t,0.25))
    case 8:
        label("顺手，也省心",1035,185+lift,a,0xC9B4D8)
        title("登录信息，",1030,251+lift,80,a,0xFAF8FC)
        title("留在本机。",1030,353+lift,80,a,0xDDBFEA)
        text("账号密码与登录信息加密保存在设备上。",1035,521+lift,27,0xACA3B6,"regular",arrival(t,0.25))
        text("验证码，也在设备上识别。",1035,574+lift,27,0xACA3B6,"regular",arrival(t,0.4))
    case 9:
        text("留白之间，也有你的颜色。",960,111+lift,75,ink,"semibold",a,"center",-1)
        text("六种主题，选一种喜欢的。",960,222+lift,27,gray,"regular",arrival(t,0.2),"center")
        text("与 App 桌面图标同步切换",960,875,24,gray,"regular",arrival(t,0.8),"center")
    case 10:
        text("更少操作。",960,841+lift,71,ink,"semibold",a,"center",-1)
        text("更多专注。",960,930+lift,71,purple,"semibold",arrival(t,0.28),"center",-1)
    default:
        label("BUCEA  /  LIBRARY",338,438,a)
        title("把时间，留给专注。",333,491+lift,91,a)
        text("北建大图书馆选座",338,636+lift,37,ink,"medium",arrival(t,0.2))
        text("library.miofelix.com",338,737,32,purple,"medium",arrival(t,0.35))
        text("Android 8.0+  ·  非官方客户端",339,805,22,gray,"regular",arrival(t,0.45))
        text("登录、查座与预约需校园网络；签到需馆内 Wi-Fi。",338,935,17,0xA5A0AC,"regular",arrival(t,0.65))
    }
}
func drawVisuals(_ scene: Int, _ t: Double) {
    let a=arrival(t),p=sceneProgress(scene,t)
    switch scene {
    case 0:
        image(logo,CGRect(x:920,y:170+18*(1-a),width:80,height:80),arrival(t,0.2))
    case 1:
        device("search",1045-35*(1-a)+5*p,573+60*(1-a)-5*p,750*(1+0.012*p),-10-0.5*p,arrival(t,0.10),-1)
        device("map",1590+45*(1-a)-5*p,563+60*(1-a)-5*p,742*(1+0.012*p),10+0.5*p,arrival(t,0.20),1)
    case 2:
        let x=133.0,y=590.0+20*(1-a)-6*p
        shadowCard(CGRect(x:x,y:y,width:825,height:290))
        text("\(Int((172*smooth(t/2.0)).rounded()))",x+44,y+24,102,purple,"medium")
        text("空闲座位",x+47,y+155,24,gray)
        text("1475",x+344,y+49,68,ink,"regular",arrival(t,0.15))
        text("正在使用",x+348,y+155,24,gray,"regular",arrival(t,0.15))
        text("9",x+643,y+49,68,ink,"regular",arrival(t,0.3))
        text("等待签到",x+644,y+155,24,gray,"regular",arrival(t,0.3))
        roundRect(CGRect(x:x+44,y:y+227,width:737,height:10),5,0xE6E4E9)
        roundRect(CGRect(x:x+44,y:y+227,width:737*a*0.104,height:10),5,purple)
    case 3:
        chip("馆内概况",136,584,190,alpha:arrival(t,0.2))
        chip("各区域座位",344,584,210,selected:true,alpha:arrival(t,0.3))
        let r=CGRect(x:1010+45*(1-a)-10*p,y:188-6*p,width:700,height:322)
        shadowCard(r,32);clippedImage(regionCards,r.insetBy(dx:12,dy:12),24)
        let r2=CGRect(x:930+55*(1-arrival(t,0.18))+8*p,y:558-10*p,width:700,height:322)
        shadowCard(r2,32);clippedImage(regionExtra,r2.insetBy(dx:12,dy:12),24)
    case 4:
        for (i,s) in ["日期","时段","电源","靠窗"].enumerated() {
            let aa=arrival(t,0.55+Double(i)*0.14,0.9)
            let selection=smooth((t-1.6-Double(i)*0.22)/0.5)
            chip(s,909+Double(i)*151,630+16*(1-aa),128,alpha:aa,selection:selection)
        }
    case 5:
        let r=CGRect(x:130,y:527+18*(1-a)-4*p,width:800,height:360)
        shadowCard(r,32)
        g.saveGState();g.addPath(CGPath(roundedRect:r.insetBy(dx:10,dy:10),cornerWidth:24,cornerHeight:24,transform:nil));g.clip()
        let mh=304.0*(1+0.04*p),mw=mh*475/270
        image(mapDetail,CGRect(x:r.midX-mw/2-5*p,y:r.midY-mh/2,width:mw,height:mh));g.restoreGState()
    case 6:
        g.saveGState();g.translateBy(x:506+7*p,y:536-9*p);g.rotate(by:(-3+0.8*p)*Double.pi/180)
        let sc=0.94+0.06*a+0.015*p;g.scaleBy(x:sc,y:sc)
        shadowCard(CGRect(x:-285,y:-296,width:570,height:592),42)
        text("039A",-225,-232,123,ink,"medium",a)
        text("二层内环区",-221,-65,35,gray,"regular",a)
        chip("空闲 · 可约",-222,23,235,selected:true,alpha:a)
        line([CGPoint(x:-219,y:136),CGPoint(x:222,y:136)],0xECE8EF,1)
        text("喜欢，就收藏。",-220,185,32,gray,"regular",arrival(t,0.3))
        let sa=arrival(t,0.65,0.9)
        star(201,-206,37*(0.7+0.3*sa),purple,sa,true);g.restoreGState()
        chip("收藏座位",1030,648,204,alpha:arrival(t,0.55))
        layer(arrival(t,0.85)){smallIcon("check",1265,668)}
    case 7:
        let by=636+20*(1-a)
        circle(152,by+36,8,purple)
        line([CGPoint(x:152,y:by+58),CGPoint(x:152,y:by+206)],0xDAD2E1,2)
        circle(152,by+220,8,purple,arrival(t,0.5))
        text("选择日期与时段",195,by,34,ink,"medium",a)
        text("确认座位，查看预约记录",195,by+67,25,gray,"regular",arrival(t,0.25))
        text("下一次到馆，心里有底",195,by+185,30,purple,"medium",arrival(t,0.5))
        device("hub",1480+25*(1-a)-4*p,551-8*p,805*(1+0.012*p),8+0.3*p,a,-0.5)
    case 8:
        g.saveGState();g.translateBy(x:505+8*p,y:540+16*(1-a)-8*p);g.scaleBy(x:1+0.02*p,y:1+0.02*p)
        let path=CGMutablePath();path.move(to:CGPoint(x:0,y:-240));path.addLine(to:CGPoint(x:215,y:-155));path.addLine(to:CGPoint(x:195,y:80));path.addCurve(to:CGPoint(x:0,y:260),control1:CGPoint(x:180,y:160),control2:CGPoint(x:70,y:225));path.addCurve(to:CGPoint(x:-195,y:80),control1:CGPoint(x:-70,y:225),control2:CGPoint(x:-180,y:160));path.addLine(to:CGPoint(x:-215,y:-155));path.closeSubpath()
        g.addPath(path);g.setFillColor(color(0x3C2849));g.fillPath()
        g.addPath(path);g.setStrokeColor(color(0xB793CD,0.7));g.setLineWidth(2);g.strokePath()
        line([CGPoint(x:-79,y:8),CGPoint(x:-17,y:70),CGPoint(x:103,y:-62)],0xEBD9F4,12,a);g.restoreGState()
        chip("多账号",1035,704,166,selected:true,alpha:arrival(t,0.55))
        text("自动恢复登录",1248,720,25,0xD3C5DC,"medium",arrival(t,0.7))
    case 9:
        let names=["建大紫","湖蓝","青绿","暖橙","玫瑰","奶龙黄"]
        for i in 0..<6 {
            let aa=arrival(t,0.15+Double(i)*0.11,1.1)
            let x=265.0+Double(i)*278,y=510.0+45*(1-aa)-7*p
            layer(aa){
                g.saveGState();g.setShadow(offset:CGSize(width:0,height:13),blur:24,color:color(themeColors[i],0.13))
                roundRect(CGRect(x:x-98,y:y-98,width:196,height:196),44,themeColors[i]);g.restoreGState()
                if let logo=themeLogos[i]{image(logo,CGRect(x:x-98,y:y-98,width:196,height:196))}
                text(names[i],x,671,26,gray,"regular",1,"center");circle(x,750,6,themeColors[i])
            }
        }
    case 10:
        let keys=["home","search","map","hub","settings"]
        for i in [0,1,3,4] {
            let aa=arrival(t,Double(i)*0.07,1.2)
            let x=288.0+Double(i)*335+Double(i-2)*3*p
            device(keys[i],x,479+36*(1-aa)-8*p,608*(1+0.012*p),Double(i-2)*2,aa)
        }
    default:
        image(logo,CGRect(x:334,y:247+12*(1-a),width:136,height:136),a)
        layer(arrival(t,0.3)){
            shadowCard(CGRect(x:1355,y:364,width:270,height:320),28)
            if let qr=qrImage{image(qr,CGRect(x:1380,y:389,width:220,height:220))}
            text("扫码下载",1490,631,23,gray,"medium",1,"center")
        }
    }
}
func drawScene(_ scene: Int, _ t: Double) {
    drawBackground(scene==8 ? 1 : 0)
    drawVisuals(scene,t)
    if let pose=heroPose(scene,t){drawDevice(pose)}
    drawCopy(scene,t)
    if (1...7).contains(scene) || scene==10 {sampleNote()}
}

// Six official theme variants of the book/seat logo, with colors sourced from AppThemeColor.
var themeLogos: [Int: CGImage] = [:]
let themeColors: [UInt32] = [0x6C397F,0x315DA8,0x28766C,0xA85A20,0xAC4567,0xFFCE32]
let logoRaw = load("website/assets/icon.png")
for (i, hex) in themeColors.enumerated() {
    // Draw the SVG path geometry directly so all six match the actual launcher mark.
    let svg = try! String(contentsOf: root.appendingPathComponent("website/assets/icon.svg"),encoding:.utf8)
    var variant = svg.replacingOccurrences(of:"#6C397F",with:String(format:"#%06X",hex),options:.caseInsensitive)
    if i == 5 { variant = variant.replacingOccurrences(of:"#FFFFFF",with:"#3C2B06",options:.caseInsensitive).replacingOccurrences(of:"#EADDF2",with:"#735300",options:.caseInsensitive) }
    let url = previewDir.appendingPathComponent("theme-\(i).svg")
    try! variant.write(to:url,atomically:true,encoding:.utf8)
    if let ns = NSImage(contentsOf:url) {
        var rect = CGRect(x:0,y:0,width:512,height:512)
        themeLogos[i] = ns.cgImage(forProposedRect:&rect,context:nil,hints:nil)
    }
}
let qrFilter = CIFilter(name:"CIQRCodeGenerator")!
qrFilter.setValue(Data("https://library.miofelix.com/".utf8),forKey:"inputMessage")
qrFilter.setValue("M",forKey:"inputCorrectionLevel")
let qrCI = qrFilter.outputImage!.transformed(by:CGAffineTransform(scaleX:10,y:10))
let qrImage: CGImage? = ciContext.createCGImage(qrCI,from:qrCI.extent)

struct SceneTransition {
    let incoming: Int
    let start: Double, width: Double, progress: Double
}
func transitionAt(_ time: Double) -> SceneTransition? {
    for i in 1..<starts.count {
        let width=transitionWidths[i],start=starts[i]-width/2
        if time>=start && time<start+width {
            return SceneTransition(incoming:i,start:start,width:width,progress:clamp((time-start)/width))
        }
    }
    return nil
}
func poseValues(_ p: PhonePose) -> [Double] { [p.x,p.y,p.height,p.angle,p.tilt] }
// Quintic Hermite interpolation carries the velocity of the preceding shot into
// the transition and hands it to the next shot, instead of stopping at the cut.
func trajectory(_ a: Double, _ b: Double, _ va: Double, _ vb: Double, _ u: Double, _ seconds: Double) -> Double {
    let d=b-a,m0=va*seconds,m1=vb*seconds
    let c3=10*d-6*m0-4*m1,c4 = -15*d+8*m0+7*m1,c5=6*d-3*m0-3*m1
    return a+m0*u+c3*pow(u,3)+c4*pow(u,4)+c5*pow(u,5)
}
func travelingDevice(_ tr: SceneTransition) {
    let old=tr.incoming-1,new=tr.incoming,epsilon=1.0/600
    let ta=localTime(old,tr.start),tb=localTime(new,tr.start+tr.width)
    guard let a=heroPose(old,ta),let b=heroPose(new,tb),
          let am=heroPose(old,ta-epsilon),let ap=heroPose(old,ta+epsilon),
          let bm=heroPose(new,tb-epsilon),let bp=heroPose(new,tb+epsilon) else {return}
    let av=poseValues(a),bv=poseValues(b),amv=poseValues(am),apv=poseValues(ap),bmv=poseValues(bm),bpv=poseValues(bp)
    let values=(0..<5).map { i in
        trajectory(av[i],bv[i],(apv[i]-amv[i])/(2*epsilon),(bpv[i]-bmv[i])/(2*epsilon),tr.progress,tr.width)
    }
    let pose=PhonePose(key:a.key,x:values[0],y:values[1],height:values[2],angle:values[3],tilt:values[4])
    drawDevice(pose,1,b.key,smooth((tr.progress-0.18)/0.64))
}
func drawFrame(_ time: Double) {
    guard let tr=transitionAt(time) else {
        let scene=starts.lastIndex(where:{$0<=time}) ?? 0
        drawScene(scene,localTime(scene,time))
        return
    }
    let old=tr.incoming-1,new=tr.incoming,u=tr.progress,q=smooth(u)
    let oldTime=localTime(old,time),newTime=localTime(new,time)
    drawBackground(lerp(old==8 ? 1 : 0,new==8 ? 1 : 0,q))
    // The scene objects share one gentle camera direction; text has its own timing.
    layer(1-q,-16*q,-8*q,1-0.008*q){drawVisuals(old,oldTime)}
    layer(q,20*(1-q),16*(1-q),0.994+0.006*q){drawVisuals(new,newTime)}
    let a=heroPose(old,oldTime),b=heroPose(new,newTime)
    if a != nil && b != nil {travelingDevice(tr)}
    else {
        if var pose=a {
            pose.y -= 24*q;pose.height *= 1-0.012*q
            drawDevice(pose,1-q)
        }
        if var pose=b {
            pose.y += 38*(1-q);pose.height *= 0.98+0.02*q
            drawDevice(pose,q)
        }
    }
    // No two large headings are visible simultaneously.
    let oldCopy=1-smooth(u/0.42),newCopy=smooth((u-0.48)/0.52)
    layer(oldCopy,0,-18*(1-oldCopy)){drawCopy(old,oldTime)}
    layer(newCopy,0,24*(1-newCopy)){drawCopy(new,newTime)}
    let oldNote=(1...7).contains(old) || old==10
    let newNote=(1...7).contains(new) || new==10
    layer(lerp(oldNote ? 1 : 0,newNote ? 1 : 0,q)){sampleNote(new==8)}
}
func drawVideoFrame(_ time: Double) {
    // A 180-degree shutter only on the wide device move. Three subframes soften
    // fast edges while every other shot retains crisp UI and typography.
    if let tr=transitionAt(time),tr.incoming==5 {
        for (i,offset) in [-1.0/240,0,1.0/240].enumerated() {
            let sample=bitmap(Int(W),Int(H)){drawFrame(time+offset)}
            image(sample,CGRect(x:0,y:0,width:W,height:H),1/Double(i+1))
        }
    } else {drawFrame(time)}
}
func drawCover() {
    drawBackground()
    circle(1740,930,330,0xEADDF2,0.32)
    circle(250,120,250,0xEADDF2,0.20)
    // Left column: all copy and the QR card stay clear of the device artwork.
    image(logo,CGRect(x:130,y:130,width:94,height:94))
    label("BUCEA  /  LIBRARY",132,270,1)
    title("北建大",132,335,92,1,ink)
    title("图书馆选座",132,445,92,1,purple)
    sub("把时间，留给专注。",136,580,1,gray)
    chip("看馆内概况",136,660,182,selected:true)
    chip("布局选座",334,660,178)
    chip("轻松预约",568,660,158)
    shadowCard(CGRect(x:130,y:760,width:244,height:256),24)
    if let qr=qrImage { image(qr,CGRect(x:167,y:785,width:170,height:170)) }
    text("扫码下载",252,976,20,gray,"medium",1,"center")
    text("library.miofelix.com",420,824,26,purple,"medium")
    text("Android 8.0+  ·  非官方客户端",420,880,22,gray,"regular")
    text("查看馆内情况 · 筛选座位 · 管理预约",420,942,20,gray,"regular")
    // Right column: three screens with deliberate gutters. The layout map is the
    // rightmost screen so the core differentiator reads immediately from the cover.
    device("search",1000,570,560,-7,1,-0.4)
    device("home",1380,545,820,0,1,0)
    device("map",1755,570,600,7,1,0.4)
    text("画面与数据为演示内容",1790,1014,16,0xA6A1AD,"regular",0.9,"right")
}

if previewOnly {
    let times: [Double] = [1.8,5.6,8.9,12.6,16.4,20.5,24.0,27.5,31.1,34.9,38.6,43.0]
    for (i,t) in times.enumerated() {
        let img = bitmap(Int(W),Int(H)) { drawFrame(t) }
        savePNG(img,previewDir.appendingPathComponent(String(format:"shot-%02d.png",i)))
    }
    let cover = bitmap(Int(W),Int(H)) { drawCover() }
    savePNG(cover,root.appendingPathComponent("promo/cover.png"))
    fputs("Rendered 12 landscape storyboard frames.\n",stderr)
    exit(0)
}

// Encode directly with AVFoundation. A pixel-buffer pool keeps memory bounded.
if FileManager.default.fileExists(atPath:output.path) { try FileManager.default.removeItem(at:output) }
let writer = try AVAssetWriter(outputURL:output,fileType:.mp4)
let settings: [String:Any] = [
    AVVideoCodecKey: AVVideoCodecType.h264,
    AVVideoWidthKey: Int(W), AVVideoHeightKey: Int(H),
    AVVideoColorPropertiesKey: [AVVideoColorPrimariesKey:AVVideoColorPrimaries_ITU_R_709_2,
                               AVVideoTransferFunctionKey:AVVideoTransferFunction_ITU_R_709_2,
                               AVVideoYCbCrMatrixKey:AVVideoYCbCrMatrix_ITU_R_709_2],
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey:15_000_000,
                                     AVVideoProfileLevelKey:AVVideoProfileLevelH264HighAutoLevel,
                                     AVVideoExpectedSourceFrameRateKey:Int(fps),
                                     AVVideoMaxKeyFrameIntervalKey:Int(fps)*2]
]
let input = AVAssetWriterInput(mediaType:.video,outputSettings:settings)
input.expectsMediaDataInRealTime = false
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput:input,sourcePixelBufferAttributes:[
    kCVPixelBufferPixelFormatTypeKey as String:kCVPixelFormatType_32BGRA,
    kCVPixelBufferWidthKey as String:Int(W),kCVPixelBufferHeightKey as String:Int(H),
    kCVPixelBufferCGImageCompatibilityKey as String:true,kCVPixelBufferCGBitmapContextCompatibilityKey as String:true
])
writer.add(input);writer.startWriting();writer.startSession(atSourceTime:.zero)
let frames = Int(duration*Double(fps))
for frame in 0..<frames {
    autoreleasepool {
        while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval:0.002) }
        var optional: CVPixelBuffer?
        guard let pool = adaptor.pixelBufferPool, CVPixelBufferPoolCreatePixelBuffer(nil,pool,&optional) == kCVReturnSuccess,
              let buffer = optional else { fatalError("Could not create video buffer") }
        CVPixelBufferLockBaseAddress(buffer,[])
        let ctx = CGContext(data:CVPixelBufferGetBaseAddress(buffer),width:Int(W),height:Int(H),bitsPerComponent:8,
                            bytesPerRow:CVPixelBufferGetBytesPerRow(buffer),space:space,
                            bitmapInfo:CGBitmapInfo.byteOrder32Little.rawValue | CGImageAlphaInfo.noneSkipFirst.rawValue)!
        g = ctx;g.translateBy(x:0,y:H);g.scaleBy(x:1,y:-1)
        drawVideoFrame(Double(frame)/Double(fps))
        CVPixelBufferUnlockBaseAddress(buffer,[])
        if !adaptor.append(buffer,withPresentationTime:CMTime(value:Int64(frame),timescale:fps)) {
            fatalError("Encode failed: \(String(describing:writer.error))")
        }
    }
    if frame % 300 == 0 { fputs("Rendered \(frame)/\(frames) frames\n",stderr) }
}
input.markAsFinished()
let done = DispatchSemaphore(value:0)
writer.finishWriting { done.signal() }
done.wait()
guard writer.status == .completed else { fatalError("Final encode failed: \(String(describing:writer.error))") }
fputs("Completed 45s landscape video at 60fps.\n",stderr)
