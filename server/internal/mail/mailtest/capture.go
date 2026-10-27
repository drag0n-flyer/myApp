// Package mailtest holds a capturing core.Mailer for tests.
package mailtest

import (
	"context"
	"sync"
)

// Message is one captured email.
type Message struct{ To, Subject, Body string }

// Capture is a core.Mailer that keeps what it is asked to send. Read Sent only
// when no Send calls are in flight.
type Capture struct {
	mu   sync.Mutex
	Sent []Message
}

// Send records the message and succeeds.
func (c *Capture) Send(_ context.Context, to, subject, body string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.Sent = append(c.Sent, Message{To: to, Subject: subject, Body: body})
	return nil
}

// Last returns the most recent message, or the zero Message if none was sent.
func (c *Capture) Last() Message {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.Sent) == 0 {
		return Message{}
	}
	return c.Sent[len(c.Sent)-1]
}
