import Foundation

private struct Migration {
    let version: Int
    let statements: [String]
}

private let migrations: [Migration] = [
    Migration(version: 1, statements: schemaV1),
]

/// Brings the database to the latest schema using `PRAGMA user_version`. Each migration runs in its own transaction.
public func migrate(_ db: Database) throws {
    for m in migrations {
        let current = try db.query("PRAGMA user_version") { Int($0.int(0)) }.first ?? 0
        guard m.version > current else { continue }
        try db.transaction { d in
            for s in m.statements { try d.execute(s) }
            try d.execute("PRAGMA user_version = \(m.version)")
        }
    }
}
