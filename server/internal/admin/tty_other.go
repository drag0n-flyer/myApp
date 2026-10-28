//go:build !linux && !darwin

package admin

import "os"

// No terminal control here: a password is read as a plain line.
func isTerminal(*os.File) bool { return false }

func withoutEcho(_ *os.File, fn func() error) error { return fn() }
