// Package core holds the interfaces and values shared by every server module.
package core

import (
	"context"
	"errors"
)

// ErrNotFound is returned when a looked-up record does not exist.
var ErrNotFound = errors.New("not found")

// Directory maps accounts to their devices and back.
type Directory interface {
	DeviceIDs(ctx context.Context, accountID string) ([]string, error)
	AccountOf(ctx context.Context, deviceID string) (string, error) // ErrNotFound if none
}
