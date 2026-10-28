package admin

import (
	"fmt"
	"os"
	"syscall"
	"testing"
	"time"

	"golang.org/x/sys/unix"
)

// M5: echo is off while the password is read and back on afterwards.
func openPty(t *testing.T) (master, slave *os.File) {
	t.Helper()
	master, err := os.OpenFile("/dev/ptmx", os.O_RDWR, 0)
	if err != nil {
		t.Skip("no pty:", err)
	}
	if err := unix.IoctlSetPointerInt(int(master.Fd()), unix.TIOCSPTLCK, 0); err != nil {
		t.Skip(err)
	}
	n, err := unix.IoctlGetInt(int(master.Fd()), unix.TIOCGPTN)
	if err != nil {
		t.Skip(err)
	}
	slave, err = os.OpenFile(fmt.Sprintf("/dev/pts/%d", n), os.O_RDWR|unix.O_NOCTTY, 0)
	if err != nil {
		t.Skip(err)
	}
	return master, slave
}

func TestWithoutEcho(t *testing.T) {
	master, slave := openPty(t)
	defer master.Close()
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

// m3: SIGINT, SIGTERM and SIGQUIT while the prompt is open end it at once
// and put echo back; the blocked read does not have to finish.
func TestWithoutEchoSignalAborts(t *testing.T) {
	for _, sig := range []syscall.Signal{syscall.SIGINT, syscall.SIGTERM, syscall.SIGQUIT} {
		master, slave := openPty(t)
		echoOn := func() bool {
			tio, err := unix.IoctlGetTermios(int(slave.Fd()), unix.TCGETS)
			if err != nil {
				t.Fatal(err)
			}
			return tio.Lflag&unix.ECHO != 0
		}
		started := make(chan struct{})
		release := make(chan struct{})
		done := make(chan error, 1)
		go func() {
			done <- withoutEcho(slave, func() error {
				close(started)
				<-release // stands in for a read that never returns
				return nil
			})
		}()
		<-started
		if echoOn() {
			t.Fatal("echo still on while prompting")
		}
		if err := syscall.Kill(os.Getpid(), sig); err != nil {
			t.Fatal(err)
		}
		select {
		case err := <-done:
			if err == nil {
				t.Errorf("%v: no error after the signal", sig)
			}
		case <-time.After(5 * time.Second):
			t.Fatalf("%v: the prompt did not end", sig)
		}
		if !echoOn() {
			t.Errorf("%v: echo not restored", sig)
		}
		close(release)
		master.Close()
		slave.Close()
	}
}
