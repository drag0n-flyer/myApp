package mail

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log/slog"
	"net"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeSMTP is a minimal SMTP server that records one session.
type fakeSMTP struct {
	ln       net.Listener
	tlsCfg   *tls.Config // non-nil: offer STARTTLS
	offerTLS bool
	mu       sync.Mutex
	lines    []string // commands received, with DATA content as one entry
	data     string
	done     chan struct{}
}

func startSMTP(t *testing.T, tlsCfg *tls.Config) *fakeSMTP {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s := &fakeSMTP{ln: ln, tlsCfg: tlsCfg, done: make(chan struct{})}
	t.Cleanup(func() { ln.Close() })
	go s.serve()
	return s
}

func (s *fakeSMTP) addr() (host, port string) {
	h, p, _ := net.SplitHostPort(s.ln.Addr().String())
	return h, p
}

func (s *fakeSMTP) serve() {
	defer close(s.done)
	conn, err := s.ln.Accept()
	if err != nil {
		return
	}
	defer conn.Close()
	var rw net.Conn = conn
	r := bufio.NewReader(rw)
	say := func(f string, a ...any) { fmt.Fprintf(rw, f+"\r\n", a...) }
	say("220 fake ESMTP")
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		line = strings.TrimRight(line, "\r\n")
		s.mu.Lock()
		s.lines = append(s.lines, line)
		s.mu.Unlock()
		switch cmd := strings.ToUpper(strings.Fields(line + " x")[0]); cmd {
		case "EHLO":
			say("250-fake")
			if s.tlsCfg != nil && !isTLS(rw) {
				say("250-STARTTLS")
			}
			say("250 AUTH PLAIN")
		case "STARTTLS":
			say("220 go ahead")
			tc := tls.Server(conn, s.tlsCfg)
			if err := tc.Handshake(); err != nil {
				return
			}
			rw = tc
			r = bufio.NewReader(tc)
		case "AUTH", "MAIL", "RCPT":
			if cmd == "AUTH" {
				say("235 ok")
			} else {
				say("250 ok")
			}
		case "DATA":
			say("354 go")
			var buf bytes.Buffer
			for {
				l, err := r.ReadString('\n')
				if err != nil {
					return
				}
				if l == ".\r\n" {
					break
				}
				buf.WriteString(l)
			}
			s.mu.Lock()
			s.data = buf.String()
			s.mu.Unlock()
			say("250 queued")
		case "QUIT":
			say("221 bye")
			return
		default:
			say("250 ok")
		}
	}
}

func isTLS(c net.Conn) bool { _, ok := c.(*tls.Conn); return ok }

func (s *fakeSMTP) commands() []string {
	<-s.done
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.lines...)
}

func hasPrefix(lines []string, p string) bool {
	for _, l := range lines {
		if strings.HasPrefix(strings.ToUpper(l), p) {
			return true
		}
	}
	return false
}

func TestNewRejectsNonSMTPURL(t *testing.T) {
	for _, u := range []string{"http://mail.example.org", "smtps://u:secretpw@mail.example.org", "mail.example.org:587", "smtp://"} {
		_, err := New(u, "no-reply@chat.test", nil)
		if err == nil {
			t.Errorf("New(%q) = nil error", u)
		} else if strings.Contains(err.Error(), "secretpw") {
			t.Errorf("error leaks the password: %v", err)
		}
	}
}

func TestUnconfiguredLogsWithoutRecipientOrBody(t *testing.T) {
	var buf bytes.Buffer
	m, err := New("", "no-reply@chat.test", slog.New(slog.NewTextHandler(&buf, nil)))
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Send(context.Background(), "alice@example.org", "Your code", "123456"); err != nil {
		t.Fatal(err)
	}
	out := buf.String()
	if !strings.Contains(out, "mail not sent: SMTP not configured") {
		t.Errorf("log = %q", out)
	}
	for _, secret := range []string{"alice", "example.org", "123456", "Your code"} {
		if strings.Contains(out, secret) {
			t.Errorf("log leaks %q: %s", secret, out)
		}
	}
}

func TestPlainLoopbackNoAuth(t *testing.T) {
	s := startSMTP(t, nil)
	h, p := s.addr()
	m, err := New("smtp://"+net.JoinHostPort(h, p), "no-reply@chat.test", nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Send(context.Background(), "alice@example.org", "Your code", "line1\nline2"); err != nil {
		t.Fatal(err)
	}
	cmds := s.commands()
	if hasPrefix(cmds, "AUTH") {
		t.Errorf("AUTH sent without user info: %v", cmds)
	}
	if !hasPrefix(cmds, "MAIL FROM:<NO-REPLY@CHAT.TEST>") || !hasPrefix(cmds, "RCPT TO:<ALICE@EXAMPLE.ORG>") {
		t.Errorf("envelope wrong: %v", cmds)
	}
	for _, want := range []string{"Subject: Your code\r\n", "To: alice@example.org\r\n", "\r\n\r\nline1\r\nline2\r\n"} {
		if !strings.Contains(s.data, want) {
			t.Errorf("message lacks %q:\n%q", want, s.data)
		}
	}
}

func TestAuthOnlyWithUserInfo(t *testing.T) {
	s := startSMTP(t, nil)
	h, p := s.addr()
	m, err := New("smtp://bot:pw@"+net.JoinHostPort(h, p), "no-reply@chat.test", nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Send(context.Background(), "alice@example.org", "s", "b"); err != nil {
		t.Fatal(err)
	}
	if !hasPrefix(s.commands(), "AUTH PLAIN") {
		t.Errorf("AUTH not sent: %v", s.commands())
	}
}

func TestStartTLSUsedWhenOffered(t *testing.T) {
	ts := httptest.NewTLSServer(nil)
	defer ts.Close()
	cert := ts.TLS.Certificates[0]
	pool := x509.NewCertPool()
	pool.AddCert(ts.Certificate())

	s := startSMTP(t, &tls.Config{Certificates: []tls.Certificate{cert}})
	h, p := s.addr()
	mi, err := New("smtp://"+net.JoinHostPort(h, p), "no-reply@chat.test", nil)
	if err != nil {
		t.Fatal(err)
	}
	m := mi.(*smtpMailer)

	// With verification on and an untrusted certificate, delivery must fail.
	if err := m.Send(context.Background(), "alice@example.org", "s", "b"); err == nil {
		t.Fatal("sent over a connection with an unverifiable certificate")
	}
	if hasPrefix(s.commands(), "MAIL") {
		t.Error("mail was sent before TLS was verified")
	}

	// With the certificate trusted it succeeds, after STARTTLS.
	s2 := startSMTP(t, &tls.Config{Certificates: []tls.Certificate{cert}})
	h, p = s2.addr()
	mi, _ = New("smtp://"+net.JoinHostPort(h, p), "no-reply@chat.test", nil)
	m = mi.(*smtpMailer)
	m.tlsConfig = &tls.Config{RootCAs: pool, InsecureSkipVerify: false, ServerName: "example.com"}
	// httptest certificates are valid for example.com.
	if err := m.Send(context.Background(), "alice@example.org", "s", "b"); err != nil {
		t.Fatal(err)
	}
	if !hasPrefix(s2.commands(), "STARTTLS") {
		t.Errorf("STARTTLS not used: %v", s2.commands())
	}
}

func TestErrorsDoNotCarryRecipient(t *testing.T) {
	m := &smtpMailer{host: "127.0.0.1", port: "1", from: "no-reply@chat.test", log: slog.New(slog.DiscardHandler), now: time.Now}
	err := m.Send(context.Background(), "alice@example.org", "s", "b")
	if err == nil || strings.Contains(err.Error(), "alice") {
		t.Errorf("err = %v", err)
	}
}

func TestSendRejectsHeaderInjection(t *testing.T) {
	m := &smtpMailer{host: "127.0.0.1", port: "1", from: "no-reply@chat.test", now: time.Now}
	if err := m.Send(context.Background(), "alice@example.org", "hi\r\nBcc: x@y.z", "b"); err == nil {
		t.Error("subject with CRLF accepted")
	}
	if err := m.Send(context.Background(), "alice@example.org\r\nBcc: x@y.z", "s", "b"); err == nil {
		t.Error("recipient with CRLF accepted")
	}
}
