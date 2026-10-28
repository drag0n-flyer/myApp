package attachments_test

import (
	"bytes"
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"testing"
	"time"

	"github.com/drag0n-flyer/myApp/server/internal/attachments"
	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

type fx struct {
	c    *testkit.Client
	d    core.Deps
	f    *testkit.Fakes
	m    *attachments.Module
	root string
	me   core.Principal
}

func setup(t *testing.T, opts ...func(*core.Deps)) *fx {
	t.Helper()
	root := t.TempDir()
	opts = append([]func(*core.Deps){func(d *core.Deps) { d.Config.DataDir = root }}, opts...)
	d, f := testkit.NewDeps(t, opts...)
	m := attachments.New(d)
	return &fx{c: testkit.Serve(t, d, m), d: d, f: f, m: m, root: root, me: testkit.Account(t, d, "ana")}
}

func files(t *testing.T, root string) []string {
	t.Helper()
	var out []string
	filepath.WalkDir(root, func(p string, e os.DirEntry, err error) error {
		if err == nil && !e.IsDir() {
			out = append(out, p)
		}
		return nil
	})
	return out
}

type uploaded struct {
	ID        string    `json:"id"`
	ExpiresAt time.Time `json:"expiresAt"`
}

func TestUploadDownload(t *testing.T) {
	x := setup(t)
	data := make([]byte, 1000)
	for i := range data {
		data[i] = byte(i)
	}
	res := x.c.Do(&x.me, "POST", "/v1/attachments", data)
	if res.Status != 200 {
		t.Fatalf("upload: %s", res)
	}
	var up uploaded
	res.Decode(t, &up)
	if len(up.ID) != 32 {
		t.Fatalf("id %q", up.ID)
	}
	if want := x.f.Clock.Now().Add(30 * 24 * time.Hour); !up.ExpiresAt.Equal(want) {
		t.Fatalf("expiresAt %v want %v", up.ExpiresAt, want)
	}
	got := x.c.Do(&x.me, "GET", "/v1/attachments/"+up.ID, nil)
	if got.Status != 200 || !bytes.Equal(got.Body, data) {
		t.Fatalf("download: %d len %d", got.Status, len(got.Body))
	}
	// Range needs a header, so go below Client.Do.
	req := httptest.NewRequest("GET", "/v1/attachments/"+up.ID, nil)
	req.Header.Set("Authorization", "Bearer "+testkit.Token(x.me))
	req.Header.Set("Range", "bytes=10-19")
	rec := httptest.NewRecorder()
	x.c.Handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusPartialContent || !bytes.Equal(rec.Body.Bytes(), data[10:20]) {
		t.Fatalf("range: %d %v", rec.Code, rec.Body.Bytes())
	}
	if res := x.c.Do(nil, "GET", "/v1/attachments/"+up.ID, nil); res.Status != 401 {
		t.Fatalf("unauthenticated: %s", res)
	}
}

func TestUploadLimits(t *testing.T) {
	x := setup(t, func(d *core.Deps) {
		d.Policy.Limits.AttachmentBytes = 100
		d.Policy.Limits.AttachmentBytesPerDay = 250
	})
	if res := x.c.Do(&x.me, "POST", "/v1/attachments", nil); res.Status != 400 {
		t.Fatalf("no length: %s", res)
	}
	if res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 101)); res.Status != 413 || res.Code() != "too_large" {
		t.Fatalf("too large: %s", res)
	}
	if n := len(files(t, x.root)); n != 0 {
		t.Fatalf("%d files left", n)
	}
	for i := 0; i < 2; i++ {
		if res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 100)); res.Status != 200 {
			t.Fatalf("upload %d: %s", i, res)
		}
	}
	if res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 100)); res.Status != 429 {
		t.Fatalf("daily: %s", res)
	}
	if n := len(files(t, x.root)); n != 2 {
		t.Fatalf("%d files, want 2", n)
	}
	x.f.Clock.Advance(25 * time.Hour)
	if res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 100)); res.Status != 200 {
		t.Fatalf("next day: %s", res)
	}
}

func TestParallelUploadsRespectDailyBudget(t *testing.T) {
	x := setup(t, func(d *core.Deps) {
		d.Policy.Limits.AttachmentBytes = 100
		d.Policy.Limits.AttachmentBytesPerDay = 250
	})
	const n = 8
	codes := make(chan int, n)
	retry := make(chan string, n)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 100))
			codes <- res.Status
			if res.Status == 429 {
				retry <- res.Header.Get("Retry-After")
			}
		}()
	}
	wg.Wait()
	close(codes)
	close(retry)
	for h := range retry {
		if h != "3600" {
			t.Fatalf("Retry-After = %q", h)
		}
	}
	ok, limited := 0, 0
	for c := range codes {
		switch c {
		case 200:
			ok++
		case 429:
			limited++
		default:
			t.Fatalf("status %d", c)
		}
	}
	if ok != 2 || limited != n-2 {
		t.Fatalf("ok=%d limited=%d", ok, limited)
	}
	if got := len(files(t, x.root)); got != 2 {
		t.Fatalf("%d files, want 2", got)
	}
}

func TestFailedUploadReleasesQuota(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("root ignores directory permissions")
	}
	x := setup(t, func(d *core.Deps) { d.Policy.Limits.AttachmentBytesPerDay = 100 })
	// Body shorter than its declared length: httptest cannot send that, so
	// call the store-failing path by making the data dir unwritable.
	if err := os.Chmod(x.root, 0o500); err != nil {
		t.Fatal(err)
	}
	if res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 100)); res.Status != 500 {
		t.Fatalf("disk failure: %s", res)
	}
	os.Chmod(x.root, 0o700)
	if res := x.c.Do(&x.me, "POST", "/v1/attachments", make([]byte, 100)); res.Status != 200 {
		t.Fatalf("quota not released: %s", res)
	}
}

func TestUnknownIDNotFound(t *testing.T) {
	x := setup(t)
	for _, id := range []string{"0123456789abcdef0123456789abcdef", "..%2Fx", "x", "ABCDEF0123456789ABCDEF0123456789"} {
		if res := x.c.Do(&x.me, "GET", "/v1/attachments/"+id, nil); res.Status != 404 {
			t.Fatalf("%s: %s", id, res)
		}
	}
}

func TestPrune(t *testing.T) {
	x := setup(t)
	var up uploaded
	x.c.Do(&x.me, "POST", "/v1/attachments", []byte("secret")).Decode(t, &up)
	if len(files(t, x.root)) != 1 {
		t.Fatal("no file written")
	}
	x.f.Clock.Advance(31 * 24 * time.Hour)
	if err := x.m.PruneOnce(context.Background()); err != nil {
		t.Fatal(err)
	}
	if res := x.c.Do(&x.me, "GET", "/v1/attachments/"+up.ID, nil); res.Status != 404 {
		t.Fatalf("after prune: %s", res)
	}
	if n := len(files(t, x.root)); n != 0 {
		t.Fatalf("%d files left", n)
	}
	var n int
	if err := x.d.DB.QueryRow(context.Background(), `select count(*) from attachments`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("%d rows left", n)
	}
}

func TestPruneContinuesPastOneFailure(t *testing.T) {
	x := setup(t)
	for i := 0; i < 3; i++ {
		if res := x.c.Do(&x.me, "POST", "/v1/attachments", []byte("secret")); res.Status != 200 {
			t.Fatal(res)
		}
	}
	// Make one blob undeletable: a non-empty directory where the file was.
	// Prune goes in id order, so the first id is the one that fails first and
	// a pass that stops at the first error never reaches the other two.
	fs := files(t, x.root)
	sort.Slice(fs, func(i, j int) bool { return filepath.Base(fs[i]) < filepath.Base(fs[j]) })
	stuck := fs[0]
	if err := os.Remove(stuck); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(stuck, "child"), 0o750); err != nil {
		t.Fatal(err)
	}
	x.f.Clock.Advance(31 * 24 * time.Hour)
	if err := x.m.PruneOnce(context.Background()); err == nil {
		t.Fatal("expected the failure to be reported")
	}
	var n int
	if err := x.d.DB.QueryRow(context.Background(), `select count(*) from attachments`).Scan(&n); err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("%d rows left, want only the stuck one", n)
	}
}
