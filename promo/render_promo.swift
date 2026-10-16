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
func ease(_ x: Double) -> Double { let t = clamp(x); return 1 - pow(1 - t, 4) }
func smooth(_ x: Double) -> Double { let t = clamp(x); return t * t * (3 - 2 * t) }
func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b - a) * t }
func arrival(_ t: Double, _ delay: Double = 0, _ d: Double = 1) -> Double { ease((t - delay) / d) }

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
var suppressedDevices: Set<String> = []
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

func device(_ key: String, _ cx: Double, _ cy: Double, _ h: Double, _ angle: Double = 0, _ alpha: Double = 1, _ tilt: Double = 0) {
    if suppressedDevices.contains(key) { return }
    guard let img = phones[key] else { return }
    let w = h * 600 / 1210
    g.saveGState(); g.setAlpha(clamp(alpha)); g.translateBy(x: cx, y: cy); g.rotate(by: angle*Double.pi/180)
    g.concatenate(CGAffineTransform(a: 1-abs(tilt)*0.10, b: tilt*0.055, c: tilt*0.035, d: 1, tx: 0, ty: 0))
    g.setShadow(offset: CGSize(width: 3, height: 17), blur: 32, color: color(0x24192C, 0.12))
    image(img, CGRect(x: -w/2, y: -h/2, width: w, height: h))
    g.restoreGState()
}
func chip(_ s: String, _ x: Double, _ y: Double, _ w: Double, selected: Bool = false, alpha: Double = 1) {
    roundRect(CGRect(x: x, y: y, width: w, height: 64), 32, selected ? purple : 0xFFFFFF, alpha,
              stroke: selected ? purple : 0xE6E3E9, line: 1.5)
    text(s, x+w/2, y+13, 25, selected ? 0xFFFFFF : 0x625B6C, "medium", alpha, "center")
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

let starts: [Double] = [0, 3.2, 7.0, 10.6, 14.0, 18.0, 22.0, 25.5, 29.0, 32.5, 36.5, 40.5]
let sceneNames = ["开场", "产品亮相", "馆内概况", "区域总览", "习惯筛选", "布局选座", "收藏", "预约安排", "本机保存", "六色主题", "功能全景", "下载"]

func drawScene(_ scene: Int, _ t: Double) {
    let a = arrival(t), slow = t*0.45
    switch scene {
    case 0:
        sceneBackground()
        image(logo, CGRect(x: 920, y: 170+18*(1-a), width: 80, height: 80), arrival(t,0.2))
        label("BUCEA  /  LIBRARY", 822, 285, arrival(t,0.35))
        let aa = arrival(t,0.15,1.1)
        text("把时间，", 960, 375+65*(1-aa), 128, ink, "semibold", aa, "center", -4)
        let bb = arrival(t,0.55,1.05)
        text("留给专注。", 960, 525+65*(1-bb), 128, purple, "semibold", bb, "center", -4)
        text("一张书桌，一段属于自己的时间。",960,760,30,gray,"regular",arrival(t,1.1),"center")
    case 1:
        sceneBackground()
        let rise = 110*(1-a)
        device("search", 1045-60*(1-a), 573+rise, 750, -12+2*a, arrival(t,0.12), -1)
        device("map", 1590+80*(1-a), 563+rise, 742, 12-2*a, arrival(t,0.25), 1)
        device("home", 1315, 555+95*(1-a), 880+slow, -1.7+0.4*a, 1)
        label("为北建大的每一段学习时光", 132, 306, arrival(t,0.2))
        title("北建大",132,370+28*(1-a),86,a)
        title("图书馆选座。",132,480+28*(1-a),86,a)
        sub("简洁，顺手。刚刚好。",136,638,arrival(t,0.5))
        roundRect(CGRect(x:136,y:720,width:220,height:52),26,0xEDE5F1,arrival(t,0.7))
        text("原生 Android 体验",246,732,21,purple,"medium",arrival(t,0.7),"center")
        sampleNote()
    case 2:
        sceneBackground()
        label("先看馆内情况", 135, 145, a)
        title("出发之前，",135,208+24*(1-a),90,a)
        title("心中有数。",135,318+24*(1-a),90,a)
        sub("馆内概况，一眼看清。",137,468,arrival(t,0.35))
        // Editorial enlargement of the actual overview metrics.
        let cardX = 133.0, cardY = 590.0+35*(1-a)
        shadowCard(CGRect(x:cardX,y:cardY,width:825,height:290))
        let count = Int(172*ease(t/1.65))
        text("\(count)",cardX+44,cardY+24,102,purple,"medium")
        text("空闲座位",cardX+47,cardY+155,24,gray)
        text("1475",cardX+344,cardY+49,68,ink,"regular",arrival(t,0.2))
        text("正在使用",cardX+348,cardY+155,24,gray,"regular",arrival(t,0.2))
        text("9",cardX+643,cardY+49,68,ink,"regular",arrival(t,0.35))
        text("等待签到",cardX+644,cardY+155,24,gray,"regular",arrival(t,0.35))
        roundRect(CGRect(x:cardX+44,y:cardY+227,width:737,height:10),5,0xE6E4E9)
        roundRect(CGRect(x:cardX+44,y:cardY+227,width:737*a*0.104,height:10),5,purple)
        device("home", 1416+80*(1-a), 557, 865+slow*2, 6-3*a, a)
        sampleNote()
    case 3:
        sceneBackground()
        label("各区域座位", 135, 139, a)
        title("每一区，",135,208,88,a)
        title("都心中有数。",135,316,88,a)
        sub("馆层与区域，清楚呈现。",137,470,arrival(t,0.35))
        chip("馆内概况",136,584,190,selected:false,alpha:arrival(t,0.2))
        chip("各区域座位",344,584,210,selected:true,alpha:arrival(t,0.35))
        let r = CGRect(x:1010+85*(1-a),y:188,width:700,height:322)
        shadowCard(r,32); clippedImage(regionCards,r.insetBy(dx:12,dy:12),24)
        let r2 = CGRect(x:930+110*(1-arrival(t,0.3)),y:558,width:700,height:322)
        shadowCard(r2,32); clippedImage(regionExtra,r2.insetBy(dx:12,dy:12),24)
        circle(1790,270,4,purple,0.30)
        text("今天 · 数据随同步更新",138,740,23,gray,"regular",arrival(t,0.5))
        sampleNote()
    case 4:
        sceneBackground()
        device("filter", 474-80*(1-a), 566, 875+slow, -5+3*a, a)
        label("自选座位", 908, 176, a)
        title("你的时间。",906,239,84,arrival(t,0.1))
        title("你的习惯。",906,346,84,arrival(t,0.45))
        title("你的位置。",906,453,84,arrival(t,0.8),purple)
        let chips: [(String,Double)] = [("日期",128),("时段",128),("电源",128),("靠窗",128)]
        for (i,item) in chips.enumerated() {
            let ca = arrival(t,0.55+Double(i)*0.18,0.7)
            chip(item.0,909+Double(i)*151,630+22*(1-ca),item.1,selected:t>1.6+Double(i)*0.18,alpha:ca)
        }
        sub("按习惯筛选，少几步操作。",911,753,arrival(t,1.0))
        sampleNote()
    case 5:
        sceneBackground()
        label("布局选座", 130, 118, a)
        title("喜欢的位置，",130,180,76,a)
        title("就在眼前。",130,273,76,a,purple)
        sub("沿着真实布局，找到你的座位。",133,397,arrival(t,0.35))
        // A real map close-up passes through the foreground, beside its full view.
        let r = CGRect(x:130,y:527+28*(1-a),width:800,height:360)
        shadowCard(r,32)
        g.saveGState(); g.addPath(CGPath(roundedRect:r.insetBy(dx:10,dy:10),cornerWidth:24,cornerHeight:24,transform:nil));g.clip()
        let z = 1.0+0.04*smooth(t/4)
        let mh = 304.0*z, mw = mh*475/270
        image(mapDetail,CGRect(x:r.midX-mw/2,y:r.midY-mh/2,width:mw,height:mh))
        g.restoreGState()
        device("map", 1405+110*(1-a), 559, 875+slow, 4-2*a, a)
        sampleNote()
    case 6:
        sceneBackground()
        label("收藏常坐的位置", 1030, 191, a)
        title("熟悉的位置，",1025,260,79,a)
        title("替你记着。",1025,361,79,a,purple)
        sub("长按座位收藏，下次直接预约。",1029,516,arrival(t,0.35))
        let scale = 0.88+0.12*a
        g.saveGState();g.translateBy(x:506,y:536);g.rotate(by:(-5+2*a)*Double.pi/180);g.scaleBy(x:scale,y:scale)
        shadowCard(CGRect(x:-285,y:-296,width:570,height:592),42)
        text("039A",-225,-232,123,ink,"medium",a)
        text("二层内环区",-221,-65,35,gray,"regular",a)
        chip("空闲 · 可约",-222,23,235,selected:true,alpha:a)
        line([CGPoint(x:-219,y:136),CGPoint(x:222,y:136)],0xECE8EF,1)
        text("喜欢，就收藏。",-220,185,32,gray,"regular",arrival(t,0.4))
        let sa = arrival(t,0.65,0.8)
        star(201,-206,37,purple,sa,true)
        g.restoreGState()
        chip("收藏座位",1030,648,204,selected:false,alpha:arrival(t,0.6))
        smallIcon("check",1265,668,purple)
        sampleNote()
    case 7:
        sceneBackground()
        label("管理自己的预约", 132, 153, a)
        title("选好时间，",130,216,86,a)
        title("安心安排。",130,323,86,a,purple)
        sub("预约、记录与到馆签到，有序进行。",134,478,arrival(t,0.4))
        let by = 636+32*(1-a)
        circle(152,by+36,8,purple)
        line([CGPoint(x:152,y:by+58),CGPoint(x:152,y:by+206)],0xDAD2E1,2)
        circle(152,by+220,8,purple,arrival(t,0.6))
        text("选择日期与时段",195,by,34,ink,"medium",a)
        text("确认座位，查看预约记录",195,by+67,25,gray,"regular",arrival(t,0.3))
        text("下一次到馆，心里有底",195,by+185,30,purple,"medium",arrival(t,0.6))
        device("hub", 1480+40*(1-a), 551, 805, 8,a,-0.5)
        device("booking", 1204+100*(1-a), 574, 870, -6,a,0.3)
        sampleNote()
    case 8:
        sceneBackground(true)
        label("顺手，也省心", 1035, 185, a,0xC9B4D8)
        title("登录信息，",1030,251,80,a,0xFAF8FC)
        title("留在本机。",1030,353,80,a,0xDDBFEA)
        text("账号密码与登录信息加密保存在设备上。",1035,521,27,0xACA3B6,"regular",arrival(t,0.3))
        text("验证码，也在设备上识别。",1035,574,27,0xACA3B6,"regular",arrival(t,0.45))
        // A restrained outline shield, drawn as a geometric brand illustration.
        g.saveGState();g.translateBy(x:505,y:540+20*(1-a));g.scaleBy(x:1+0.015*sin(t),y:1+0.015*sin(t))
        let p = CGMutablePath();p.move(to:CGPoint(x:0,y:-240));p.addLine(to:CGPoint(x:215,y:-155));p.addLine(to:CGPoint(x:195,y:80));p.addCurve(to:CGPoint(x:0,y:260),control1:CGPoint(x:180,y:160),control2:CGPoint(x:70,y:225));p.addCurve(to:CGPoint(x:-195,y:80),control1:CGPoint(x:-70,y:225),control2:CGPoint(x:-180,y:160));p.addLine(to:CGPoint(x:-215,y:-155));p.closeSubpath()
        g.addPath(p);g.setFillColor(color(0x3C2849));g.fillPath()
        g.addPath(p);g.setStrokeColor(color(0xB793CD,0.7));g.setLineWidth(2);g.strokePath()
        line([CGPoint(x:-79,y:8),CGPoint(x:-17,y:70),CGPoint(x:103,y:-62)],0xEBD9F4,12,a)
        g.restoreGState()
        chip("多账号",1035,704,166,selected:true,alpha:arrival(t,0.6))
        text("自动恢复登录",1248,720,25,0xD3C5DC,"medium",arrival(t,0.75))
    case 9:
        sceneBackground()
        text("留白之间，也有你的颜色。",960,111+20*(1-a),75,ink,"semibold",a,"center",-1)
        text("六种主题，选一种喜欢的。",960,222,27,gray,"regular",arrival(t,0.25),"center")
        let colors: [UInt32] = [0x6C397F,0x315DA8,0x28766C,0xA85A20,0xAC4567,0xFFCE32]
        let names = ["建大紫","湖蓝","青绿","暖橙","玫瑰","奶龙黄"]
        for i in 0..<6 {
            let aa = arrival(t,0.22+Double(i)*0.09,0.8)
            let x = 265.0+Double(i)*278
            let y = 510.0+70*(1-aa)
            g.saveGState();g.translateBy(x:x,y:y);g.rotate(by:lerp(Double(i-3)*0.03,0,aa))
            g.setShadow(offset:CGSize(width:0,height:18),blur:24,color:color(colors[i],0.15))
            roundRect(CGRect(x:-98,y:-98,width:196,height:196),44,colors[i],aa)
            g.restoreGState()
            // Use the actual logo silhouette for the theme collection.
            if let themed = themeLogos[i] { image(themed,CGRect(x:x-98,y:y-98,width:196,height:196),aa) }
            text(names[i],x,671,26,gray,"regular",aa,"center")
            circle(x,750,6,colors[i],aa)
        }
        text("与 App 桌面图标同步切换",960,875,24,gray,"regular",arrival(t,0.9),"center")
    case 10:
        sceneBackground()
        let keys = ["home","search","map","hub","settings"]
        for i in 0..<5 {
            let aa = arrival(t,Double(i)*0.08,1.0)
            let x = 288.0+Double(i)*335
            let h = i == 2 ? 656.0 : 608.0
            device(keys[i],x,479+55*(1-aa),h,Double(i-2)*2,aa)
        }
        text("更少操作。",960,841+20*(1-a),71,ink,"semibold",a,"center",-1)
        text("更多专注。",960,930+20*(1-a),71,purple,"semibold",arrival(t,0.45),"center",-1)
        sampleNote()
    default:
        sceneBackground()
        image(logo,CGRect(x:334,y:247,width:136,height:136),a)
        label("BUCEA  /  LIBRARY", 338, 438, a)
        title("把时间，留给专注。",333,491+24*(1-a),91,a)
        text("北建大图书馆选座",338,636,37,ink,"medium",arrival(t,0.25))
        text("library.miofelix.com",338,737,32,purple,"medium",arrival(t,0.45))
        text("Android 8.0+  ·  非官方客户端",339,805,22,gray,"regular",arrival(t,0.55))
        if let qr = qrImage {
            shadowCard(CGRect(x:1355,y:364,width:270,height:320),28)
            image(qr,CGRect(x:1380,y:389,width:220,height:220),arrival(t,0.35))
            text("扫码下载",1490,631,23,gray,"medium",arrival(t,0.55),"center")
        }
        text("登录、查座与预约需校园网络；签到需馆内 Wi-Fi。",338,935,17,0xA5A0AC,"regular",arrival(t,0.8))
    }
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

func drawFrame(_ time: Double) {
    var active = starts.count-1
    for i in 0..<starts.count-1 { if time < starts[i+1] { active = i; break } }
    let preroll = 0.55
    let local = time-starts[active]+(active > 0 ? preroll : 0)
    // Keep a shared device in continuous motion while its surrounding composition changes.
    let matched = active == 1 || active == 4
    let inTransition = active < starts.count-1 && time > starts[active+1]-preroll
    if inTransition && matched { suppressedDevices = [active == 1 ? "home" : "filter"] }
    drawScene(active,local)
    suppressedDevices = []
    // A short optical dissolve bridges the remaining compositions.
    if active < starts.count-1 {
        let next = starts[active+1]
        let cross = preroll
        if time > next-cross {
            let a = smooth((time-(next-cross))/cross)
            if matched { suppressedDevices = [active == 1 ? "home" : "map"] }
            g.saveGState();g.setAlpha(a);g.beginTransparencyLayer(auxiliaryInfo:nil)
            drawScene(active+1,time-(next-cross))
            g.endTransparencyLayer();g.restoreGState()
            suppressedDevices = []
            if matched {
                let v = smooth((time-(next-cross))/cross)
                let nt = time-(next-cross)
                let na = arrival(nt)
                if active == 1 {
                    device("home",lerp(1315,1416+80*(1-na),v),lerp(555+95*(1-arrival(local)),557,v),
                           lerp(880+local*0.45,865+nt*0.9,v),lerp(-1.7+0.4*arrival(local),6-3*na,v))
                } else {
                    let x = lerp(474-80*(1-arrival(local)),1405+110*(1-na),v)
                    let y = lerp(566,559,v), h = lerp(875+local*0.45,875+nt*0.45,v)
                    let angle = lerp(-5+3*arrival(local),4-2*na,v)
                    device("filter",x,y,h,angle,1-v)
                    device("map",x,y,h,angle,v)
                }
            }
        }
    }
}

if previewOnly {
    let times: [Double] = [1.8,5.6,8.9,12.6,16.4,20.5,24.0,27.5,31.1,34.9,38.6,43.0]
    for (i,t) in times.enumerated() {
        let img = bitmap(Int(W),Int(H)) { drawFrame(t) }
        savePNG(img,previewDir.appendingPathComponent(String(format:"shot-%02d.png",i)))
    }
    let cover = bitmap(Int(W),Int(H)) { drawScene(1,2.4) }
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
        drawFrame(Double(frame)/Double(fps))
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
