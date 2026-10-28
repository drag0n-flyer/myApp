package admin

import (
	"io"
	"regexp"
	"strings"
	"testing"
)

// withStdin makes the CLI read its password from s.
func withStdin(t *testing.T, s string) {
	t.Helper()
	old := cliStdin
	cliStdin = strings.NewReader(s)
	t.Cleanup(func() { cliStdin = old })
}

func TestCLIUserAddFromStdin(t *testing.T) {
	m, _ := newTestModule(t)
	withStdin(t, goodPassword+"\n")
	out, err := runCmd(t, m, "admin user add", "Carol")
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(out, goodPassword) || !strings.Contains(out, "carol") {
		t.Errorf("output = %q", out)
	}
	b := passwordSignIn(t, m, "carol", goodPassword)
	_ = b
	if n := count(t, m, `select count(*) from admin_audit where action = 'admin.add' and actor = 'cli' and admin_user_id is null and detail->>'username' = 'carol'`); n != 1 {
		t.Errorf("audit rows = %d", n)
	}
	// A duplicate and a bad password are errors that name the problem.
	withStdin(t, goodPassword+"\n")
	if _, err := runCmd(t, m, "admin user add", "carol"); err == nil || !strings.Contains(err.Error(), "taken") {
		t.Errorf("duplicate = %v", err)
	}
	withStdin(t, "short\n")
	if _, err := runCmd(t, m, "admin user add", "dave"); err == nil || !strings.Contains(err.Error(), "12") {
		t.Errorf("short password = %v", err)
	}
	withStdin(t, "")
	if _, err := runCmd(t, m, "admin user add", "dave"); err == nil {
		t.Error("empty stdin accepted")
	}
	if _, err := runCmd(t, m, "admin user add"); err == nil {
		t.Error("add without a username accepted")
	}
}

func TestCLIUserAddGenerate(t *testing.T) {
	m, _ := newTestModule(t)
	withStdin(t, "")
	out, err := runCmd(t, m, "admin user add", "erin", "--generate")
	if err != nil {
		t.Fatal(err)
	}
	pw := regexp.MustCompile(`password: (\S{20})\n`).FindStringSubmatch(out)
	if pw == nil {
		t.Fatalf("output = %q", out)
	}
	passwordSignIn(t, m, "erin", pw[1])
	// A second generated password differs.
	out2, _ := runCmd(t, m, "admin user add", "frank", "--generate")
	if strings.Contains(out2, pw[1]) {
		t.Error("generated passwords repeat")
	}
	// The flag may also come first.
	if _, err := runCmd(t, m, "admin user add", "--generate", "gina"); err != nil {
		t.Errorf("flag first: %v", err)
	}
}

func TestCLIUserList(t *testing.T) {
	m, _ := newTestModule(t)
	if out, err := runCmd(t, m, "admin user list"); err != nil || !strings.Contains(out, "no admins") {
		t.Errorf("empty list = %q, %v", out, err)
	}
	setupFirst(t, m, "alice", goodPassword)
	withStdin(t, goodPassword+"\n")
	runCmd(t, m, "admin user add", "bob")
	runCmd(t, m, "admin user disable", "bob")
	out, err := runCmd(t, m, "admin user list")
	if err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(out), "\n")
	if len(lines) != 2 || !strings.Contains(lines[0], "alice") || !strings.Contains(lines[0], "enabled") ||
		!strings.Contains(lines[1], "bob") || !strings.Contains(lines[1], "disabled") {
		t.Errorf("list = %q", out)
	}
	if strings.Contains(out, "argon2") {
		t.Error("list shows a hash")
	}
}

func TestCLIResetPasswordEndsSessionsAndEnablesNothing(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	a.want(a.do("POST", "/admin/api/admins", map[string]string{"username": "bob", "password": goodPassword}, false), 200, "add bob")
	bob := passwordSignIn(t, m, "bob", goodPassword)

	withStdin(t, "a brand new password\n")
	out, err := runCmd(t, m, "admin user reset-password", "bob")
	if err != nil || strings.Contains(out, "brand new") {
		t.Fatalf("reset = %q, %v", out, err)
	}
	bob.want(bob.do("GET", "/admin/api/session", nil, false), 401, "bob session after reset")
	a.want(a.do("GET", "/admin/api/session", nil, false), 200, "alice session untouched")
	if rec := passwordLogin(bob, "bob", goodPassword); rec.Code != 401 {
		t.Errorf("old password = %d", rec.Code)
	}
	passwordSignIn(t, m, "bob", "a brand new password")
	if n := count(t, m, `select count(*) from admin_audit where action = 'admin.password' and actor = 'cli' and detail->>'username' = 'bob'`); n != 1 {
		t.Errorf("audit rows = %d", n)
	}

	// A disabled admin stays disabled.
	runCmd(t, m, "admin user disable", "bob")
	withStdin(t, "yet another password\n")
	if _, err := runCmd(t, m, "admin user reset-password", "bob"); err != nil {
		t.Fatal(err)
	}
	if n := count(t, m, `select count(*) from admin_users where username = 'bob' and disabled_at is not null`); n != 1 {
		t.Error("reset-password re-enabled bob")
	}
	// --generate works here too, and an unknown name is an error.
	out, err = runCmd(t, m, "admin user reset-password", "--generate", "alice")
	if err != nil || !regexp.MustCompile(`password: \S{20}\n`).MatchString(out) {
		t.Errorf("generate = %q, %v", out, err)
	}
	withStdin(t, goodPassword+"\n")
	if _, err := runCmd(t, m, "admin user reset-password", "nobody"); err == nil || !strings.Contains(err.Error(), "no admin") {
		t.Errorf("unknown = %v", err)
	}
}

func TestCLIDisableEnable(t *testing.T) {
	m, _ := newTestModule(t)
	a := setupFirst(t, m, "alice", goodPassword)
	// The CLI may disable the last admin, with a warning; enable recovers.
	out, err := runCmd(t, m, "admin user disable", "alice")
	if err != nil || !strings.Contains(out, "warning") {
		t.Fatalf("disable last = %q, %v", out, err)
	}
	a.want(a.do("GET", "/admin/api/session", nil, false), 401, "session after CLI disable")
	if rec := passwordLogin(a, "alice", goodPassword); rec.Code != 401 {
		t.Errorf("disabled login = %d", rec.Code)
	}
	if out, err := runCmd(t, m, "admin user enable", "alice"); err != nil || strings.Contains(out, "warning") {
		t.Fatalf("enable = %q, %v", out, err)
	}
	passwordSignIn(t, m, "alice", goodPassword)

	withStdin(t, goodPassword+"\n")
	runCmd(t, m, "admin user add", "bob")
	if out, _ := runCmd(t, m, "admin user disable", "bob"); strings.Contains(out, "warning") {
		t.Errorf("warning with another admin enabled: %q", out)
	}
	for _, c := range []struct{ action string }{{"admin.disable"}, {"admin.enable"}} {
		if n := count(t, m, `select count(*) from admin_audit where action = $1 and actor = 'cli' and detail->>'username' = 'alice'`, c.action); n != 1 {
			t.Errorf("%s audit rows = %d", c.action, n)
		}
	}
	if _, err := runCmd(t, m, "admin user disable", "nobody"); err == nil {
		t.Error("disable unknown succeeded")
	}
	if _, err := runCmd(t, m, "admin user enable"); err == nil {
		t.Error("enable without a name succeeded")
	}
	if _, err := runCmd(t, m, "admin user list", "extra"); err == nil {
		t.Error("list with an argument succeeded")
	}
}

func TestCLIReadPasswordLine(t *testing.T) {
	for in, want := range map[string]string{"pw\n": "pw", "pw\r\n": "pw", "pw": "pw", "  keep spaces  \nsecond": "  keep spaces  "} {
		got, err := readPasswordLine(strings.NewReader(in))
		if err != nil || got != want {
			t.Errorf("readPasswordLine(%q) = %q, %v", in, got, err)
		}
	}
	if _, err := readPasswordLine(strings.NewReader("")); err == nil {
		t.Error("empty input accepted")
	}
	if _, err := readPasswordLine(io.LimitReader(strings.NewReader(strings.Repeat("x", 5000)), 5000)); err == nil {
		t.Error("overlong line accepted")
	}
}
