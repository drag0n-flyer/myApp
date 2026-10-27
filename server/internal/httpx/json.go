package httpx

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
)

const maxBody = 1 << 20 // 1 MiB

// Decode reads one JSON value from the request body into v. Bodies over
// 1 MiB give too_large; anything that is not exactly one JSON value gives
// bad_request. Unknown fields are allowed.
func Decode(r *http.Request, v any) error {
	dec := json.NewDecoder(http.MaxBytesReader(nil, r.Body, maxBody))
	err := dec.Decode(v)
	if err == nil {
		// Nothing but whitespace may follow the value.
		if _, err = dec.Token(); err == io.EOF {
			return nil
		}
		if err == nil {
			err = errors.New("trailing data")
		}
	}
	var tooBig *http.MaxBytesError
	if errors.As(err, &tooBig) {
		return Err("too_large", "request body is over 1 MiB")
	}
	return Err("bad_request", "request body is not valid JSON")
}

// JSON writes v as the response body with the given status.
func JSON(w http.ResponseWriter, status int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		writeError(w, Err("internal", "internal error"))
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(append(b, '\n'))
}

func writeError(w http.ResponseWriter, e *Error) {
	JSON(w, e.Status, struct {
		Error *Error `json:"error"`
	}{e})
}
