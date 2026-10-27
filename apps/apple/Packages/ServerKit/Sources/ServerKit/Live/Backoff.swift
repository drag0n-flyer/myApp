import Foundation

/// Full-jitter exponential backoff: `random(0...min(cap, base * 2^attempt))`.
public struct Backoff: Sendable {
    private let base: Double
    private let cap: Double
    private let random: @Sendable (ClosedRange<Double>) -> Double
    private var attempt = 0

    public init(base: Double = 1, cap: Double = 60,
                random: @escaping @Sendable (ClosedRange<Double>) -> Double) {
        self.base = base
        self.cap = cap
        self.random = random
    }

    public mutating func next() -> Double {
        let bound = min(cap, base * pow(2, Double(min(attempt, 62))))
        attempt += 1
        return random(0...bound)
    }

    public mutating func reset() { attempt = 0 }
}
