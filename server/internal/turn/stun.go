package turn

import (
	"encoding/binary"
	"errors"
)

const (
	stunMagic           = 0x2112A442
	stunBindingRequest  = 0x0001
	stunBindingResponse = 0x0101
	stunHeaderLen       = 20
)

// bindingRequest returns a STUN Binding Request (RFC 5389) with no attributes.
func bindingRequest(txID [12]byte) []byte {
	b := make([]byte, stunHeaderLen)
	binary.BigEndian.PutUint16(b[0:], stunBindingRequest)
	binary.BigEndian.PutUint32(b[4:], stunMagic)
	copy(b[8:], txID[:])
	return b
}

var errBadSTUN = errors.New("turn: not a binding success response")

// parseBindingResponse accepts only a Binding success response to txID.
func parseBindingResponse(b []byte, txID [12]byte) error {
	if len(b) < stunHeaderLen ||
		binary.BigEndian.Uint16(b[0:]) != stunBindingResponse ||
		binary.BigEndian.Uint32(b[4:]) != stunMagic ||
		int(binary.BigEndian.Uint16(b[2:])) != len(b)-stunHeaderLen ||
		string(b[8:20]) != string(txID[:]) {
		return errBadSTUN
	}
	return nil
}
