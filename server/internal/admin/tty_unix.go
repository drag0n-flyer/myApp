//go:build linux || darwin

package admin

import (
	"os"

	"golang.org/x/sys/unix"
)

// isTerminal reports whether f is a terminal (not just any character device).
func isTerminal(f *os.File) bool {
	_, err := unix.IoctlGetTermios(int(f.Fd()), ioctlGetTermios)
	return err == nil
}

// withoutEcho runs fn with terminal echo off on f and puts the terminal back
// as it was afterwards, also when fn fails.
func withoutEcho(f *os.File, fn func() error) error {
	fd := int(f.Fd())
	old, err := unix.IoctlGetTermios(fd, ioctlGetTermios)
	if err != nil {
		return err
	}
	quiet := *old
	quiet.Lflag &^= unix.ECHO
	if err := unix.IoctlSetTermios(fd, ioctlSetTermios, &quiet); err != nil {
		return err
	}
	defer unix.IoctlSetTermios(fd, ioctlSetTermios, old)
	return fn()
}
