package realtime

import (
	"context"
	"sync"
	"time"

	"github.com/coder/websocket"
)

const (
	sendBuffer   = 64
	writeTimeout = 10 * time.Second
	missedPongs  = 2

	// Close codes (design §7.3).
	CloseReplaced = 4001
	CloseRevoked  = 4003
)

// conn is one live device connection.
type conn struct {
	ws   *websocket.Conn
	dev  string
	send chan []byte
	done chan struct{}
	once sync.Once
	hub  *hub
	// cancel cancels the context frame handlers run with; close calls it.
	cancel context.CancelFunc
}

// hub holds at most one connection per device.
type hub struct {
	mu     sync.Mutex
	conns  map[string]*conn
	closed bool
}

func newHub() *hub { return &hub{conns: map[string]*conn{}} }

// add registers c, replacing (and closing with 4001) any earlier connection of
// the device. It reports false when the hub is shutting down.
func (h *hub) add(c *conn) bool {
	h.mu.Lock()
	if h.closed {
		h.mu.Unlock()
		return false
	}
	old := h.conns[c.dev]
	h.conns[c.dev] = c
	h.mu.Unlock()
	if old != nil {
		old.close(CloseReplaced, "replaced")
	}
	return true
}

func (h *hub) count() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.conns)
}

func (h *hub) get(dev string) *conn {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.conns[dev]
}

func (h *hub) remove(c *conn) {
	h.mu.Lock()
	if h.conns[c.dev] == c {
		delete(h.conns, c.dev)
	}
	h.mu.Unlock()
}

// closeAll refuses new connections and closes every live one.
func (h *hub) closeAll(code int, reason string) {
	h.mu.Lock()
	h.closed = true
	all := make([]*conn, 0, len(h.conns))
	for _, c := range h.conns {
		all = append(all, c)
	}
	h.mu.Unlock()
	for _, c := range all {
		c.close(code, reason)
	}
}

// close stops the connection once, sending a close frame with code.
func (c *conn) close(code int, reason string) {
	c.once.Do(func() {
		close(c.done)
		if c.cancel != nil {
			c.cancel()
		}
		c.hub.remove(c)
		go func() {
			if err := c.ws.Close(websocket.StatusCode(code), reason); err != nil {
				c.ws.CloseNow()
			}
		}()
	})
}

// enqueue queues a frame without blocking. A full buffer closes the
// connection with 1013 and reports false so the caller can fall back to push.
func (c *conn) enqueue(b []byte) bool {
	select {
	case <-c.done:
		return false
	default:
	}
	select {
	case c.send <- b:
		return true
	default:
		c.close(int(websocket.StatusTryAgainLater), "slow consumer")
		return false
	}
}

func (c *conn) writeLoop(ctx context.Context) {
	for {
		select {
		case <-c.done:
			return
		case <-ctx.Done():
			return
		case b := <-c.send:
			wctx, cancel := context.WithTimeout(ctx, writeTimeout)
			err := c.ws.Write(wctx, websocket.MessageText, b)
			cancel()
			if err != nil {
				c.close(int(websocket.StatusGoingAway), "write failed")
				return
			}
		}
	}
}

// pingLoop pings every interval and drops the connection after missedPongs
// pings in a row go unanswered. The read loop must be running to see pongs.
func (c *conn) pingLoop(ctx context.Context, every time.Duration) {
	t := time.NewTicker(every)
	defer t.Stop()
	missed := 0
	for {
		select {
		case <-c.done:
			return
		case <-ctx.Done():
			return
		case <-t.C:
		}
		pctx, cancel := context.WithTimeout(ctx, every)
		err := c.ws.Ping(pctx)
		cancel()
		if err == nil {
			missed = 0
			continue
		}
		if ctx.Err() != nil {
			return
		}
		if missed++; missed >= missedPongs {
			c.close(int(websocket.StatusGoingAway), "ping timeout")
			return
		}
	}
}
