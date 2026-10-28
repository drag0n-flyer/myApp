package admin

import (
	"context"
	"net/http"
	"runtime"
	"runtime/debug"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/httpx"
)

type overviewCounts struct {
	Accounts      int `json:"accounts"`
	Devices       int `json:"devices"`
	Conversations int `json:"conversations"`
	MessagesToday int `json:"messagesToday"`
}

type overviewRelay struct {
	Host    string `json:"host"`
	Region  string `json:"region"`
	Healthy bool   `json:"healthy"`
	RTTMs   int64  `json:"rttMs"`
}

// buildVersion is the first 12 characters of vcs.revision and vcs.time, else
// "unknown".
func buildVersion() string {
	bi, ok := debug.ReadBuildInfo()
	if !ok {
		return "unknown"
	}
	var rev, at string
	for _, s := range bi.Settings {
		switch s.Key {
		case "vcs.revision":
			rev = s.Value
		case "vcs.time":
			at = s.Value
		}
	}
	if rev == "" {
		return "unknown"
	}
	if len(rev) > 12 {
		rev = rev[:12]
	}
	if at != "" {
		return rev + " " + at
	}
	return rev
}

// overview answers GET /admin/api/overview (design 6.1 and 7).
func (m *Module) overview(w http.ResponseWriter, r *http.Request, s Session) error {
	ctx := r.Context()
	now := m.d.Now()
	out := map[string]any{
		"version":       buildVersion(),
		"goVersion":     runtime.Version(),
		"startedAt":     m.src.Started.UTC(),
		"uptimeSeconds": int64(now.Sub(m.src.Started).Seconds()),
		"database":      "ok",
		"counts":        nil,
		"connections":   0,
	}
	if counts, err := m.overviewCounts(ctx, now); err != nil {
		out["database"] = "down"
	} else {
		out["counts"] = counts
	}
	if m.src.Live != nil {
		out["connections"] = m.src.Live.ConnectionCount()
	}
	push := map[string]any{"enabled": false, "sent": uint64(0), "failed": uint64(0)}
	if m.src.Push != nil {
		st := m.src.Push.Stats()
		push = map[string]any{"enabled": st.Enabled, "sent": st.Sent, "failed": st.Failed}
	}
	out["push"] = push
	relays := []overviewRelay{}
	if m.src.Relays != nil {
		for _, rl := range m.src.Relays.Relays() {
			relays = append(relays, overviewRelay{rl.Host, rl.Region, rl.Healthy, rl.RTT.Milliseconds()})
		}
	}
	out["calls"] = map[string]any{"enabled": m.d.Config.TURNSecret != "", "relays": relays}
	httpx.JSON(w, http.StatusOK, out)
	return nil
}

// overviewCounts pings the database (2 s limit) and counts rows. Any error
// means the database is down.
func (m *Module) overviewCounts(ctx context.Context, now time.Time) (*overviewCounts, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := m.d.DB.Ping(ctx); err != nil {
		return nil, err
	}
	utc := now.UTC()
	midnight := time.Date(utc.Year(), utc.Month(), utc.Day(), 0, 0, 0, 0, time.UTC)
	var c overviewCounts
	err := m.d.DB.QueryRow(ctx, `select
		(select count(*) from accounts),
		(select count(*) from devices),
		(select count(*) from conversations),
		(select count(*) from client_messages where created_at >= $1)`, midnight).
		Scan(&c.Accounts, &c.Devices, &c.Conversations, &c.MessagesToday)
	if err != nil {
		return nil, err
	}
	return &c, nil
}
