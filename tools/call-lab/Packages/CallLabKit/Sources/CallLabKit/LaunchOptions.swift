import Foundation

/// Launch arguments for the unattended CI calls (L10a):
/// `--headless --role caller|callee [--preset <LabPreset>] [--path direct|relay] [--stimulus <wav>] [--out <dir>]`.
/// Server host, token and peer come from the environment (`LAB_HOST`, `LAB_TOKEN`, `LAB_PEER`), so the
/// token never appears in a process list. Arguments the lab does not know (AppKit's `-NS...`) are ignored.
public struct LabLaunchOptions: Equatable, Sendable {
    public enum Role: String, Sendable { case caller, callee }
    public struct ParseError: Error, Equatable, CustomStringConvertible {
        public let description: String
    }

    public var role: Role
    public var preset: LabPreset
    public var path: PathPolicy
    public var stimulus: String?
    public var out: String?

    public init(role: Role, preset: LabPreset, path: PathPolicy, stimulus: String?, out: String?) {
        self.role = role; self.preset = preset; self.path = path; self.stimulus = stimulus; self.out = out
    }

    /// The preset's knobs with the requested path.
    public var knobs: LabKnobs {
        var k = preset.knobs
        k.path = path
        return k
    }

    /// nil unless `--headless` is present. `args` includes the program name, like `CommandLine.arguments`.
    public static func parse(_ args: [String]) throws -> LabLaunchOptions? {
        guard args.contains("--headless") else { return nil }
        var values: [String: String] = [:]
        var i = 1
        while i < args.count {
            let a = args[i]
            if ["--role", "--preset", "--path", "--stimulus", "--out"].contains(a) {
                guard i + 1 < args.count, !args[i + 1].hasPrefix("--") else {
                    throw ParseError(description: "\(a) needs a value")
                }
                values[a] = args[i + 1]
                i += 2
            } else {
                i += 1
            }
        }
        guard let r = values["--role"] else { throw ParseError(description: "--headless needs --role caller|callee") }
        guard let role = Role(rawValue: r) else { throw ParseError(description: "unknown role: \(r)") }
        let p = values["--preset"] ?? LabPreset.balanced.rawValue
        guard let preset = LabPreset(rawValue: p) else { throw ParseError(description: "unknown preset: \(p)") }
        let pa = values["--path"] ?? PathPolicy.direct.rawValue
        guard let path = PathPolicy(rawValue: pa) else { throw ParseError(description: "unknown path: \(pa)") }
        return LabLaunchOptions(role: role, preset: preset, path: path, stimulus: values["--stimulus"], out: values["--out"])
    }
}
