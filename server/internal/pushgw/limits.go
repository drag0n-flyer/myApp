package pushgw

import (
	"crypto/sha256"
	"encoding/hex"
	"sync"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/ratelimit"
)

const (
	replayWindow   = 10*time.Minute + time.Second // covers a request skewed by the full 300 s
	registerPerIP  = 5                            // per hour
	serverPerMin   = 1000                         // per server
	messagePerHour = 120                          // per handle
	callPerHour    = 20                           // per handle
)

// replayCache remembers signatures for replayWindow. Entries are queued in
// insertion order, so pruning on access only looks at the front.
type replayCache struct {
	mu    sync.Mutex
	seen  map[string]time.Time
	order []replayEntry
}

type replayEntry struct {
	sig     string
	expires time.Time
}

func newReplayCache() *replayCache { return &replayCache{seen: map[string]time.Time{}} }

// firstUse records sig and reports true, or reports false if it was seen
// within the window.
func (c *replayCache) firstUse(sig string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	for len(c.order) > 0 && !c.order[0].expires.After(now) {
		delete(c.seen, c.order[0].sig)
		c.order = c.order[1:]
	}
	if _, dup := c.seen[sig]; dup {
		return false
	}
	exp := now.Add(replayWindow)
	c.seen[sig] = exp
	c.order = append(c.order, replayEntry{sig, exp})
	return true
}

// limits holds every rate limiter of the gateway. All are in memory, so a
// restart resets them.
type limits struct {
	register *ratelimit.Limiter // per client IP
	server   *ratelimit.Limiter // per server ID
	messages *ratelimit.Limiter // per handle
	calls    *ratelimit.Limiter // per handle
}

func newLimits(now func() time.Time) *limits {
	return &limits{
		register: ratelimit.New(registerPerIP, time.Hour, now),
		server:   ratelimit.New(serverPerMin, time.Minute, now),
		messages: ratelimit.New(messagePerHour, time.Hour, now),
		calls:    ratelimit.New(callPerHour, time.Hour, now),
	}
}

// handleKey names a device for rate limiting without keeping its token.
func handleKey(env, bundle, token string) string {
	sum := sha256.Sum256([]byte(env + "\x00" + bundle + "\x00" + token))
	return hex.EncodeToString(sum[:])
}
