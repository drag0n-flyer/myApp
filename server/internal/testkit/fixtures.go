package testkit

import (
	"context"
	"testing"

	"github.com/drag0n-flyer/myApp/server/internal/core"
)

// Account creates an account with email <username>@example.org and one iphone
// device, and returns that device's Principal.
func Account(t testing.TB, d core.Deps, username string) core.Principal {
	t.Helper()
	id := core.NewID()
	email := username + "@example.org"
	_, err := d.DB.Exec(context.Background(),
		`insert into accounts (id, username, email, email_lower) values ($1, $2, $3, lower($3))`,
		id, username, email)
	if err != nil {
		t.Fatalf("testkit.Account(%q): %v", username, err)
	}
	return Device(t, d, id, "iphone")
}

// Device adds a device of the given platform ("iphone" or "mac") to an
// existing account and returns its Principal.
func Device(t testing.TB, d core.Deps, accountID, platform string) core.Principal {
	t.Helper()
	id := core.NewID()
	_, err := d.DB.Exec(context.Background(),
		`insert into devices (id, account_id, name, platform) values ($1, $2, $3, $4)`,
		id, accountID, "test "+platform, platform)
	if err != nil {
		t.Fatalf("testkit.Device(%q): %v", platform, err)
	}
	return core.Principal{AccountID: accountID, DeviceID: id}
}
