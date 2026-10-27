package delivery

import (
	"errors"
	"io/fs"
	"testing"
)

func TestErrTypeNamesFirstJoinedError(t *testing.T) {
	joined := errors.Join(&fs.PathError{Op: "x", Path: "y", Err: errors.New("z")}, errors.New("other"))
	if got := errType(joined); got != "*fs.PathError" {
		t.Fatalf("joined: got %s", got)
	}
	if got := errType(errors.New("x")); got != "*errors.errorString" {
		t.Fatalf("plain: got %s", got)
	}
}
