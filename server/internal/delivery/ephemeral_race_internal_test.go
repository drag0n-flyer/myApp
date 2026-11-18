package delivery

import (
	"context"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
	"github.com/drag0n-flyer/myApp/server/internal/httpx"
	"github.com/drag0n-flyer/myApp/server/internal/testkit"
)

// A frame racing the conversation's deletion reads a kind that is gone: that
// is not_found, like a non-member, never an internal error.
func TestEphemeralKindOfDeletedConversationIsNotFound(t *testing.T) {
	d, _ := testkit.NewDeps(t)
	m := New(d)
	_, err := m.ephemeralRespectsBlocks(context.Background(), core.NewID())
	he, ok := err.(*httpx.Error)
	if !ok || he.Code != "not_found" {
		t.Fatalf("err = %v", err)
	}
}
