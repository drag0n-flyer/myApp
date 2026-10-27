// Package ratelimit is an in-memory, per-key token bucket.
package ratelimit

import (
	"sync"
	"time"
)

// Limiter allows n events per period for each key, with bursts up to n.
//
// It tracks, per key, the time at which the bucket is full again. Each event
// costs per/n of that time, so a bucket is full when that time is not in the
// future, and such keys carry no state and are dropped.
type Limiter struct {
	n       int
	per     time.Duration
	step    time.Duration // time to refill one token
	now     func() time.Time
	mu      sync.Mutex
	buckets map[string]time.Time // key -> time the bucket is full again
	swept   time.Time
}

// New returns a Limiter with capacity n that refills n tokens per period.
// A nil now uses the system clock.
func New(n int, per time.Duration, now func() time.Time) *Limiter {
	if n <= 0 || per <= 0 {
		panic("ratelimit: n and per must be positive")
	}
	if now == nil {
		now = time.Now
	}
	step := per / time.Duration(n)
	if step < 1 {
		step = 1
	}
	return &Limiter{n: n, per: per, step: step, now: now, buckets: map[string]time.Time{}}
}

// Allow takes one token for key. When it is denied, the duration says how long
// to wait before one token is available.
func (l *Limiter) Allow(key string) (bool, time.Duration) { return l.AllowN(key, 1) }

// AllowN takes n tokens for key, all or nothing. A denied call takes nothing.
func (l *Limiter) AllowN(key string, n int) (bool, time.Duration) {
	if n <= 0 {
		return true, 0
	}
	if n > l.n {
		return false, l.per // can never be satisfied
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.sweep(now)

	full := l.buckets[key]
	if full.Before(now) {
		full = now
	}
	next := full.Add(time.Duration(n) * l.step)
	if over := next.Sub(now) - time.Duration(l.n)*l.step; over > 0 {
		return false, over
	}
	l.buckets[key] = next
	return true, 0
}

// sweep drops keys whose bucket is full again, at most once per period.
func (l *Limiter) sweep(now time.Time) {
	if now.Sub(l.swept) < l.per {
		return
	}
	l.swept = now
	for k, full := range l.buckets {
		if !full.After(now) {
			delete(l.buckets, k)
		}
	}
}
