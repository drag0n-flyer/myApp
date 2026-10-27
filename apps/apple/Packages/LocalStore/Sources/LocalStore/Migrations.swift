import Foundation

private struct Migration {
    let version: Int
    let statements: [String]
}

private let migrations: [Migration] = [
    Migration(version: 1, statements: schemaV1),
]

public struct SchemaTooNew: Error, Equatable {
    public let found: Int
    public let supported: Int
}

/// Brings the database to the latest schema using `PRAGMA user_version`. Each migration runs in its own
/// `BEGIN IMMEDIATE` transaction and reads the version inside it, so two processes (app and extension)
/// opening the same file cannot both apply a migration. Throws `SchemaTooNew` for a database written by a newer build.
public func migrate(_ db: Database) throws {
    let latest = migrations.last?.version ?? 0
    for m in migrations {
        try db.transaction { d in
            let current = try d.query("PRAGMA user_version") { Int($0.int(0)) }.first ?? 0
            if current > latest { throw SchemaTooNew(found: current, supported: latest) }
            guard m.version > current else { return }
            for s in m.statements { try d.execute(s) }
            try d.execute("PRAGMA user_version = \(m.version)")
        }
    }
}
