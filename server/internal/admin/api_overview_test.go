package admin

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/push"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
	"github.com/drag0n-flyer/myApp/server/internal/turn"
)

// Helpers shared by lane B's admin tests.

var testSession = Session{TokenID: "22222222-2222-2222-2222-222222222222", Label: "test"}

// call runs one request through testHandler as testSession.
func call(t testing.TB, m *Module, method, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var rd *strings.Reader
	if body == nil {
		rd = strings.NewReader("")
	} else {
		b, err := json.Marshal(body)
		if err != nil {
			t.Fatal(err)
		}
		rd = strings.NewReader(string(b))
	}
	req := httptest.NewRequest(method, path, rd)
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	m.testHandler(testSession).ServeHTTP(rec, req)
	return rec
}

func errCode(rec *httptest.ResponseRecorder) string {
	var e struct {
		Error struct{ Code string } `json:"error"`
	}
	_ = json.Unmarshal(rec.Body.Bytes(), &e)
	return e.Error.Code
}

func decodeBody(t testing.TB, rec *httptest.ResponseRecorder, v any) {
	t.Helper()
	if err := json.Unmarshal(rec.Body.Bytes(), v); err != nil {
		t.Fatalf("body %q: %v", rec.Body.String(), err)
	}
}

func mustExec(t testing.TB, d core.Deps, sql string, args ...any) {
	t.Helper()
	if _, err := d.DB.Exec(context.Background(), sql, args...); err != nil {
		t.Fatalf("%s: %v", sql, err)
	}
}

func auditCount(t testing.TB, d core.Deps, action string) (n int) {
	t.Helper()
	if err := d.DB.QueryRow(context.Background(), `select count(*) from admin_audit where action = $1`, action).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

type fakeLive int

func (f fakeLive) ConnectionCount() int { return int(f) }

type fakePush push.Stats

func (f fakePush) Stats() push.Stats { return push.Stats(f) }

type fakeRelays []turn.RelayStatus

func (f fakeRelays) Relays() []turn.RelayStatus { return f }

func TestOverview(t *testing.T) {
	d, f := testkit.NewDeps(t)
	started := f.Clock.Now()
	m := New(d, Sources{
		Started: started,
		Live:    fakeLive(7),
		Push:    fakePush{Enabled: true, Sent: 5, Failed: 2},
		Relays:  fakeRelays{{Host: "r1.test", Region: "eu", Healthy: true, RTT: 12 * time.Millisecond}, {Host: "r2.test", Region: "us"}},
	})
	a := testkit.Account(t, d, "ana")
	testkit.Device(t, d, a.AccountID, "mac")
	b := testkit.Account(t, d, "bob")
	conv := core.NewID()
	mustExec(t, d, `insert into conversations (id, mode) values ($1, 'e2ee')`, conv)
	now := f.Clock.Now()
	for i, at := range []time.Time{now.Add(-time.Hour), now.Add(-2 * time.Hour), now.Add(-13 * time.Hour)} {
		mustExec(t, d, `insert into client_messages (device_id, conversation_id, client_msg_id, seq, created_at) values ($1, $2, $3, $4, $5)`,
			b.DeviceID, conv, string(rune('a'+i)), i+1, at)
	}
	f.Clock.Advance(90 * time.Second)

	rec := call(t, m, "GET", "/admin/api/overview", nil)
	if rec.Code != http.StatusOK {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var got struct {
		Version       string
		GoVersion     string
		StartedAt     time.Time
		UptimeSeconds int
		Database      string
		Counts        *struct{ Accounts, Devices, Conversations, MessagesToday int }
		Connections   int
		Push          struct {
			Enabled      bool
			Sent, Failed int
		}
		Calls struct {
			Enabled bool
			Relays  []struct {
				Host, Region string
				Healthy      bool
				RttMs        int
			}
		}
	}
	decodeBody(t, rec, &got)
	if got.Database != "ok" || got.Counts == nil || *got.Counts != (struct{ Accounts, Devices, Conversations, MessagesToday int }{2, 3, 1, 2}) {
		t.Errorf("database %q counts %+v", got.Database, got.Counts)
	}
	if got.Connections != 7 || !got.Push.Enabled || got.Push.Sent != 5 || got.Push.Failed != 2 {
		t.Errorf("connections %d push %+v", got.Connections, got.Push)
	}
	if got.UptimeSeconds != 90 || !got.StartedAt.Equal(started) || got.Version == "" || !strings.HasPrefix(got.GoVersion, "go") {
		t.Errorf("uptime %d started %v version %q go %q", got.UptimeSeconds, got.StartedAt, got.Version, got.GoVersion)
	}
	if !got.Calls.Enabled || len(got.Calls.Relays) != 2 || got.Calls.Relays[0].Host != "r1.test" ||
		!got.Calls.Relays[0].Healthy || got.Calls.Relays[0].RttMs != 12 || got.Calls.Relays[1].Healthy {
		t.Errorf("calls %+v", got.Calls)
	}
}

func TestOverviewCallsOffWithoutSecret(t *testing.T) {
	d, _ := testkit.NewDeps(t, func(d *core.Deps) { d.Config.TURNSecret = "" })
	m := New(d, Sources{Started: d.Now(), Live: fakeLive(0), Push: fakePush{}, Relays: fakeRelays{}})
	var got struct {
		Calls struct {
			Enabled bool
			Relays  []any
		}
		Push struct{ Enabled bool }
	}
	decodeBody(t, call(t, m, "GET", "/admin/api/overview", nil), &got)
	if got.Calls.Enabled || got.Calls.Relays == nil || got.Push.Enabled {
		t.Errorf("%+v", got)
	}
}

func TestOverviewDatabaseDown(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d, Sources{Started: d.Now(), Live: fakeLive(1), Push: fakePush{}, Relays: fakeRelays{}})
	d.DB.Close()
	rec := call(t, m, "GET", "/admin/api/overview", nil)
	if rec.Code != http.StatusOK {
		t.Fatal(rec.Code, rec.Body.String())
	}
	var got struct {
		Database string
		Counts   *struct{}
	}
	decodeBody(t, rec, &got)
	if got.Database != "down" || got.Counts != nil || !strings.Contains(rec.Body.String(), `"counts":null`) {
		t.Errorf("%s", rec.Body.String())
	}
}
