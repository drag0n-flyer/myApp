// Package httpx is the HTTP layer shared by every lane: router, error
// envelope, bearer auth, JSON helpers, rate-limit wrapper and client IP.
package httpx

import (
	"fmt"
	"net/http"
)

// statuses is the error code table of the server design, section 7.
var statuses = map[string]int{
	"bad_request":      http.StatusBadRequest,
	"invalid_username": http.StatusBadRequest,
	"invalid_email":    http.StatusBadRequest,
	"code_invalid":     http.StatusBadRequest,
	"escrow_required":  http.StatusBadRequest,
	"escrow_forbidden": http.StatusBadRequest,
	"unauthorized":     http.StatusUnauthorized,
	"forbidden":        http.StatusForbidden,
	"invite_required":  http.StatusForbidden,
	"invite_invalid":   http.StatusForbidden,
	"not_found":        http.StatusNotFound,
	"username_taken":   http.StatusConflict,
	"stale_epoch":      http.StatusConflict,
	"expired":          http.StatusGone,
	"too_large":        http.StatusRequestEntityTooLarge,
	"rate_limited":     http.StatusTooManyRequests,
	"internal":         http.StatusInternalServerError,
	"calls_disabled":   http.StatusServiceUnavailable,
}

// Error is an API error. Handlers return it and the router writes it as
// {"error":{"code","message","details"?}}.
type Error struct {
	Status  int    `json:"-"`
	Code    string `json:"code"`
	Message string `json:"message"`
	Details any    `json:"details,omitempty"`
}

func (e *Error) Error() string { return fmt.Sprintf("%s: %s", e.Code, e.Message) }

// Err returns the error for code, with the status from the design's table.
// It panics on a code that is not in the table.
func Err(code, message string) *Error { return ErrDetails(code, message, nil) }

// ErrDetails is Err with a details value for error.details.
func ErrDetails(code, message string, details any) *Error {
	status, ok := statuses[code]
	if !ok {
		panic("httpx: unknown error code " + code)
	}
	return &Error{Status: status, Code: code, Message: message, Details: details}
}
