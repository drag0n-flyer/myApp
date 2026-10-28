//go:build linux || darwin

package admin

import (
	"errors"
	"os"
	"os/signal"
	"syscall"

	"golang.org/x/sys/unix"
)

// isTerminal reports whether f is a terminal (not just any character device).
func isTerminal(f *os.File) bool {
	_, err := unix.IoctlGetTermios(int(f.Fd()), ioctlGetTermios)
	return err == nil
}

// errAborted is what withoutEcho returns when a signal ends the prompt.
var errAborted = errors.New("aborted")

// withoutEcho runs fn with terminal echo off on f and puts the terminal back
// as it was afterwards, also when fn fails.
//
// While fn runs, SIGINT, SIGTERM and SIGQUIT end the prompt at once: echo is
// restored and errAborted is returned without waiting for fn. The process
// catches these signals (SIGINT and SIGTERM through the server's own
// NotifyContext, SIGQUIT here), so the terminal stays in the state the signal
// handler leaves it in. A plain blocking read cannot be interrupted, so fn
// runs in its own goroutine and is abandoned on a signal; the caller exits
// right after. SIGKILL cannot be caught, and leaves echo off ("stty echo").
func withoutEcho(f *os.File, fn func() error) error {
	fd := int(f.Fd())
	old, err := unix.IoctlGetTermios(fd, ioctlGetTermios)
	if err != nil {
		return err
	}
	quiet := *old
	quiet.Lflag &^= unix.ECHO
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM, syscall.SIGQUIT)
	defer signal.Stop(sigs)
	if err := unix.IoctlSetTermios(fd, ioctlSetTermios, &quiet); err != nil {
		return err
	}
	defer unix.IoctlSetTermios(fd, ioctlSetTermios, old)
	done := make(chan error, 1)
	go func() { done <- fn() }()
	select {
	case err := <-done:
		return err
	case <-sigs:
		return errAborted
	}
}
