import Foundation
import Vision
import CoreGraphics
import AppKit

// 子命令:
//   list <owner>        列出 owner 匹配的窗口: windowId|x|y|w|h|title
//   ocr <png>           对图片做 OCR: x|y|w|h|text (像素坐标)
//   locked              检测锁屏: true/false
//   accessibility       检测辅助功能权限: true/false
//   screenaccess        检测屏幕录制权限: true/false
//   idleseconds         距上次键盘/鼠标输入的系统空闲秒数（整数）
//   watchkeys           常驻监听键盘输入，每收到一次按键输出一行 "k"

func printUsage() -> Never {
    FileHandle.standardError.write("usage: ocr_helper <list|ocr|locked|accessibility|screenaccess> [args]\n".data(using: .utf8)!)
    exit(2)
}

func cmdList(_ owner: String) {
    let options: CGWindowListOption = [.optionOnScreenOnly, .excludeDesktopElements]
    guard let infos = CGWindowListCopyWindowInfo(options, kCGNullWindowID) as? [[String: Any]] else {
        exit(1)
    }
    for info in infos {
        guard let ownerName = info[kCGWindowOwnerName as String] as? String,
              ownerName.lowercased().contains(owner.lowercased()) else { continue }
        guard let num = info[kCGWindowNumber as String] as? Int,
              let boundsDict = info[kCGWindowBounds as String] as? [String: Any],
              let x = boundsDict["X"] as? Double, let y = boundsDict["Y"] as? Double,
              let w = boundsDict["Width"] as? Double, let h = boundsDict["Height"] as? Double else { continue }
        let title = (info[kCGWindowName as String] as? String) ?? ""
        let safeTitle = title.replacingOccurrences(of: "|", with: " ").replacingOccurrences(of: "\n", with: " ")
        print("\(num)|\(Int(x))|\(Int(y))|\(Int(w))|\(Int(h))|\(safeTitle)")
    }
}

func cmdOcr(_ pngPath: String) {
    guard let image = NSImage(contentsOfFile: pngPath),
          let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
        FileHandle.standardError.write("cannot load image: \(pngPath)\n".data(using: .utf8)!)
        exit(1)
    }
    let request = VNRecognizeTextRequest()
    request.recognitionLevel = .accurate
    request.recognitionLanguages = ["zh-Hans", "en-US"]
    request.usesLanguageCorrection = false
    let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
    do {
        try handler.perform([request])
    } catch {
        FileHandle.standardError.write("ocr failed: \(error)\n".data(using: .utf8)!)
        exit(1)
    }
    let width = Double(cgImage.width)
    let height = Double(cgImage.height)
    guard let results = request.results else { return }
    for obs in results {
        guard let candidate = obs.topCandidates(1).first else { continue }
        let box = obs.boundingBox  // 归一化, 原点在左下
        let x = box.origin.x * width
        let y = (1.0 - box.origin.y - box.height) * height
        let w = box.width * width
        let h = box.height * height
        let text = candidate.string.replacingOccurrences(of: "\n", with: " ").replacingOccurrences(of: "\t", with: " ")
        print("\(Int(x))|\(Int(y))|\(Int(w))|\(Int(h))|\(text)")
    }
}

func cmdLocked() {
    let dict = CGSessionCopyCurrentDictionary() as? [String: Any]
    let locked = (dict?["CGSSessionScreenIsLocked"] as? Int ?? 0) == 1
    print(locked ? "true" : "false")
}

func cmdAccessibility() {
    let trusted = AXIsProcessTrusted()
    print(trusted ? "true" : "false")
}

func cmdScreenAccess() {
    if #available(macOS 10.15, *) {
        print(CGPreflightScreenCaptureAccess() ? "true" : "false")
    } else {
        print("true")
    }
}

func cmdIdleSeconds() {
    let key = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .keyDown)
    let mouseMove = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .mouseMoved)
    let leftDown = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .leftMouseDown)
    let rightDown = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .rightMouseDown)
    let scroll = CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: .scrollWheel)
    let idle = min(key, mouseMove, leftDown, rightDown, scroll)
    print(Int(idle))
}

func cmdWatchKeys() {
    // 监听按键、滚轮、鼠标点击；输出 "k"(按键) / "s"(滚轮) / "c"(点击)
    let eventMask = CGEventMask(1 << CGEventType.keyDown.rawValue)
        | CGEventMask(1 << CGEventType.scrollWheel.rawValue)
        | CGEventMask(1 << CGEventType.leftMouseDown.rawValue)
        | CGEventMask(1 << CGEventType.rightMouseDown.rawValue)
    guard let tap = CGEvent.tapCreate(
        tap: .cgSessionEventTap,
        place: .headInsertEventTap,
        options: .listenOnly,
        eventsOfInterest: eventMask,
        callback: { proxy, type, event, refcon in
            switch type {
            case .keyDown:
                print("k")
            case .scrollWheel:
                print("s")
            case .leftMouseDown, .rightMouseDown:
                print("c")
            default:
                break
            }
            fflush(stdout)
            return Unmanaged.passUnretained(event)
        },
        userInfo: nil
    ) else {
        FileHandle.standardError.write("watchkeys: 创建输入监听失败，需要「辅助功能/输入监控」权限\n".data(using: .utf8)!)
        exit(3)
    }
    guard let runLoopSource = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0) else {
        FileHandle.standardError.write("watchkeys: runloop source 创建失败\n".data(using: .utf8)!)
        exit(3)
    }
    CFRunLoopAddSource(CFRunLoopGetCurrent(), runLoopSource, .commonModes)
    CGEvent.tapEnable(tap: tap, enable: true)
    print("ready")
    fflush(stdout)
    CFRunLoopRun()
}

let args = CommandLine.arguments
guard args.count >= 2 else { printUsage() }
switch args[1] {
case "list":
    guard args.count >= 3 else { printUsage() }
    cmdList(args[2])
case "ocr":
    guard args.count >= 3 else { printUsage() }
    cmdOcr(args[2])
case "locked":
    cmdLocked()
case "accessibility":
    cmdAccessibility()
case "screenaccess":
    cmdScreenAccess()
case "idleseconds":
    cmdIdleSeconds()
case "watchkeys":
    cmdWatchKeys()
default:
    printUsage()
}
