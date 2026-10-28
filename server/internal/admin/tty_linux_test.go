package admin

import (
	"fmt"
	"os"
	"testing"

	"golang.org/x/sys/unix"
)

// M5: echo is off while the password is read and back on afterwards.
func TestWithoutEcho(t *testing.T) {
	master, err := os.OpenFile("/dev/ptmx", os.O_RDWR, 0)
	if err != nil {
		t.Skip("no pty:", err)
	}
	defer master.Close()
	if err := unix.IoctlSetPointerInt(int(master.Fd()), unix.TIOCSPTLCK, 0); err != nil {
		t.Skip(err)
	}
	n, err := unix.IoctlGetInt(int(master.Fd()), unix.TIOCGPTN)
	if err != nil {
		t.Skip(err)
	}
	slave, err := os.OpenFile(fmt.Sprintf("/dev/pts/%d", n), os.O_RDWR|unix.O_NOCTTY, 0)
	if err != nil {
		t.Skip(err)
	}
	defer slave.Close()
	echo := func() bool {
		tio, err := unix.IoctlGetTermios(int(slave.Fd()), unix.TCGETS)
		if err != nil {
			t.Fatal(err)
		}
		return tio.Lflag&unix.ECHO != 0
	}
	if !isTerminal(slave) {
		t.Fatal("pty not seen as a terminal")
	}
	if !echo() {
		t.Skip("pty starts without echo")
	}
	var during bool
	if err := withoutEcho(slave, func() error { during = echo(); return nil }); err != nil || during || !echo() {
		t.Errorf("success path: err=%v echo during=%v after=%v", err, during, echo())
	}
	wantErr := fmt.Errorf("boom")
	if err := withoutEcho(slave, func() error { during = echo(); return wantErr }); err != wantErr || during || !echo() {
		t.Errorf("error path: err=%v echo during=%v after=%v", err, during, echo())
	}
	f, _ := os.Open("/dev/null")
	defer f.Close()
	if isTerminal(f) {
		t.Error("/dev/null is a terminal")
	}
}
