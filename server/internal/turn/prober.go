package turn

import (
	"context"
	"crypto/rand"
	"net"
	"slices"
	"sync"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/config"
)

const (
	failuresToUnhealthy = 3
	defaultInterval     = 30 * time.Second
	defaultTimeout      = 2 * time.Second
)

type relayState struct {
	fails int
	down  bool
	rtt   time.Duration // 0 until a probe has succeeded
}

// Prober checks relay health with STUN Binding Requests. A relay is unhealthy
// after 3 failures in a row and healthy again after 1 success.
type Prober struct {
	Interval time.Duration // between rounds in Run
	Timeout  time.Duration // per probe

	relays []config.Relay
	addr   func(host string) string // UDP address to probe; replaceable in tests

	mu    sync.Mutex
	state map[string]*relayState
}

// NewProber returns a Prober for relays. Every relay starts healthy.
func NewProber(relays []config.Relay) *Prober {
	p := &Prober{Interval: defaultInterval, Timeout: defaultTimeout, relays: relays,
		addr: func(h string) string { return net.JoinHostPort(h, "3478") }, state: map[string]*relayState{}}
	for _, r := range relays {
		p.state[r.Host] = &relayState{}
	}
	return p
}

// Run probes every Interval until ctx is cancelled.
func (p *Prober) Run(ctx context.Context) {
	t := time.NewTicker(p.Interval)
	defer t.Stop()
	for {
		p.Check(ctx)
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
	}
}

// Check probes every relay once, concurrently, and updates their health.
func (p *Prober) Check(ctx context.Context) {
	var wg sync.WaitGroup
	for _, r := range p.relays {
		wg.Go(func() {
			rtt, err := p.probe(ctx, p.addr(r.Host))
			if ctx.Err() != nil {
				return // shutting down: not a relay failure
			}
			p.record(r.Host, rtt, err)
		})
	}
	wg.Wait()
}

func (p *Prober) record(host string, rtt time.Duration, err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := p.state[host]
	if err != nil {
		if s.fails++; s.fails >= failuresToUnhealthy {
			s.down = true
		}
		return
	}
	s.fails, s.down, s.rtt = 0, false, rtt
}

func (p *Prober) probe(ctx context.Context, addr string) (time.Duration, error) {
	ctx, cancel := context.WithTimeout(ctx, p.Timeout)
	defer cancel()
	var d net.Dialer
	c, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	if dl, ok := ctx.Deadline(); ok {
		if err := c.SetDeadline(dl); err != nil {
			return 0, err // without a deadline the read below could hang
		}
	}
	var tx [12]byte
	if _, err := rand.Read(tx[:]); err != nil {
		return 0, err
	}
	start := time.Now()
	if _, err := c.Write(bindingRequest(tx)); err != nil {
		return 0, err
	}
	buf := make([]byte, 1500)
	for {
		n, err := c.Read(buf)
		if err != nil {
			return 0, err
		}
		if parseBindingResponse(buf[:n], tx) == nil {
			return max(time.Since(start), time.Nanosecond), nil
		}
		// A stray datagram: keep waiting until the deadline.
	}
}

func (p *Prober) isHealthy(host string) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return !p.state[host].down
}

// Healthy lists the healthy relays, fastest first (relays never measured
// come last, in configured order). If none is healthy it lists all relays.
func (p *Prober) Healthy() []config.Relay {
	p.mu.Lock()
	defer p.mu.Unlock()
	var out []config.Relay
	for _, r := range p.relays {
		if !p.state[r.Host].down {
			out = append(out, r)
		}
	}
	if len(out) == 0 {
		return slices.Clone(p.relays)
	}
	slices.SortStableFunc(out, func(a, b config.Relay) int {
		ra, rb := p.state[a.Host].rtt, p.state[b.Host].rtt
		switch {
		case ra == rb:
			return 0
		case ra == 0:
			return 1
		case rb == 0:
			return -1
		case ra < rb:
			return -1
		}
		return 1
	})
	return out
}
