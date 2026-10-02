// Walks the macOS accessibility tree of a running process and prints it.
//
// Used to compare what the native-image desktop build exposes against what the JVM
// development shell exposes; the two trees have to have the same shape.
//
// VoiceOver itself needs a human, but the tree VoiceOver reads is the same one the
// Accessibility API exposes, so an empty or role-less tree here is a real failure and a
// tree with labelled text fields and buttons is real evidence that the bridge survived
// the native-image build.
//
// Usage: swift ax-dump.swift <pid> [maxDepth]
//
// Requires the calling terminal to hold the Accessibility permission
// (System Settings > Privacy & Security > Accessibility). Without it
// AXUIElementCopyAttributeValue returns kAXErrorAPIDisabled (-25211) and this tool says so
// rather than reporting an empty tree as a failure of the renderer.

import ApplicationServices
import Foundation

let args = CommandLine.arguments
guard args.count >= 2, let pid = Int32(args[1]) else {
    FileHandle.standardError.write("usage: swift ax-dump.swift <pid> [maxDepth]\n".data(using: .utf8)!)
    exit(2)
}
let maxDepth = args.count >= 3 ? Int(args[2]) ?? 12 : 12

if !AXIsProcessTrusted() {
    print("note: this process is not trusted for accessibility; reads may fail with -25211")
}

func copyValue(_ element: AXUIElement, _ attribute: String) -> (AXError, CFTypeRef?) {
    var value: CFTypeRef?
    let err = AXUIElementCopyAttributeValue(element, attribute as CFString, &value)
    return (err, value)
}

func string(_ element: AXUIElement, _ attribute: String) -> String? {
    let (err, value) = copyValue(element, attribute)
    guard err == .success, let value else { return nil }
    if let s = value as? String { return s }
    if let n = value as? NSNumber { return n.stringValue }
    return String(describing: value)
}

func children(_ element: AXUIElement) -> [AXUIElement] {
    let (err, value) = copyValue(element, kAXChildrenAttribute as String)
    guard err == .success, let array = value as? [AXUIElement] else { return [] }
    return array
}

var counts: [String: Int] = [:]
var total = 0

func walk(_ element: AXUIElement, depth: Int) {
    let role = string(element, kAXRoleAttribute as String) ?? "?"
    counts[role, default: 0] += 1
    total += 1
    var parts = ["\(role)"]
    if let sub = string(element, kAXSubroleAttribute as String) { parts.append("subrole=\(sub)") }
    for (label, attribute) in [("title", kAXTitleAttribute), ("desc", kAXDescriptionAttribute),
                               ("value", kAXValueAttribute), ("help", kAXHelpAttribute)] {
        if let v = string(element, attribute as String), !v.isEmpty {
            parts.append("\(label)=\(v.prefix(80).replacingOccurrences(of: "\n", with: "\\n"))")
        }
    }
    print(String(repeating: "  ", count: depth) + parts.joined(separator: " "))
    guard depth < maxDepth else {
        print(String(repeating: "  ", count: depth + 1) + "... (depth limit)")
        return
    }
    for child in children(element) { walk(child, depth: depth + 1) }
}

let app = AXUIElementCreateApplication(pid)

// Ask for the focused element first. This is the attribute an assistive client reads as
// soon as it attaches (the macOS Accessibility Keyboard does it on every focus change),
// and it takes a different path through AppKit than walking AXWindows does:
// -[NSWindow accessibilityFocusedUIElement] resolves java.awt.Window through JNI. Walking
// the tree never touches it, so a dump can print a full, healthy tree while turning on an
// assistive technology still aborts the process. Read it here so that gap cannot hide.
let (focusErr, focusValue) = copyValue(app, kAXFocusedUIElementAttribute as String)
switch focusErr {
case .success:
    let element = focusValue as! AXUIElement
    let role = string(element, kAXRoleAttribute as String) ?? "?"
    let label = string(element, kAXTitleAttribute as String)
        ?? string(element, kAXDescriptionAttribute as String) ?? ""
    print("AXFocusedUIElement: \(role) \(label)")
case .noValue, .attributeUnsupported:
    // Nothing focused is a legitimate answer, and the query survived, which is the point.
    print("AXFocusedUIElement: none (query answered)")
default:
    print("AXFocusedUIElement failed: \(focusErr.rawValue)")
}

let (windowsErr, windowsValue) = copyValue(app, kAXWindowsAttribute as String)
if windowsErr != .success {
    print("AXWindows failed: \(windowsErr.rawValue)")
    if windowsErr.rawValue == -25211 {
        print("-25211 is kAXErrorAPIDisabled: grant Accessibility permission to this terminal.")
    }
    exit(1)
}
let windows = (windowsValue as? [AXUIElement]) ?? []
print("pid \(pid): \(windows.count) AX window(s)")
for window in windows { walk(window, depth: 0) }
print("---")
print("total elements: \(total)")
for (role, count) in counts.sorted(by: { $0.key < $1.key }) { print("  \(role): \(count)") }
