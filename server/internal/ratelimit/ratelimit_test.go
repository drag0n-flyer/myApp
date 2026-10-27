package ratelimit

import (
	"testing"
	"time"
)

type clock struct{ t time.Time }

func (c *clock) now() time.Time { return c.t }

func TestLimiter(t *testing.T) {
	c := &clock{t: time.Unix(1000, 0)}
	l := New(3, time.Minute, c.now)
	for i := 0; i < 3; i++ {
		if ok, _ := l.Allow("k"); !ok {
			t.Fatalf("call %d denied", i+1)
		}
	}
	ok, retry := l.Allow("k")
	if ok || retry != 20*time.Second {
		t.Fatalf("got ok=%v retry=%v, want false 20s", ok, retry)
	}
	if ok, _ := l.Allow("other"); !ok {
		t.Fatal("other key must have its own bucket")
	}
	c.t = c.t.Add(20 * time.Second)
	if ok, _ := l.Allow("k"); !ok {
		t.Fatal("want one token after 20s")
	}
	if ok, _ := l.Allow("k"); ok {
		t.Fatal("want denied after using the refilled token")
	}
}

func TestAllowN(t *testing.T) {
	c := &clock{t: time.Unix(1000, 0)}
	l := New(100, time.Hour, c.now)
	if ok, _ := l.AllowN("a", 60); !ok {
		t.Fatal("60 of 100 denied")
	}
	if ok, _ := l.AllowN("a", 41); ok {
		t.Fatal("41 more must be denied")
	}
	if ok, _ := l.AllowN("a", 40); !ok {
		t.Fatal("a denied call must not consume tokens")
	}
	if ok, _ := l.AllowN("b", 101); ok {
		t.Fatal("more than capacity can never be allowed")
	}
}

func TestIdleKeysDropped(t *testing.T) {
	c := &clock{t: time.Unix(1000, 0)}
	l := New(2, time.Minute, c.now)
	for _, k := range []string{"a", "b", "c"} {
		l.Allow(k)
	}
	c.t = c.t.Add(2 * time.Minute)
	l.Allow("d")
	l.mu.Lock()
	n := len(l.buckets)
	l.mu.Unlock()
	if n != 1 {
		t.Fatalf("%d buckets kept, want 1", n)
	}
}
