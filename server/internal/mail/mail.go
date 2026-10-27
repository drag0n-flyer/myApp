// Package mail sends the server's email over SMTP (spec section 5).
package mail

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"log/slog"
	"mime"
	"net"
	"net/mail"
	"net/smtp"
	"net/textproto"
	"net/url"
	"strings"
	"time"

	"github.com/velixio/myApp/server/internal/core"
)

const (
	defaultPort = "587"
	// sendTimeout bounds one whole delivery when the caller's context has no
	// earlier deadline.
	sendTimeout = 30 * time.Second
)

// New returns a Mailer for smtpURL, which looks like smtp://user:pass@host:587.
//
// The connection upgrades with STARTTLS whenever the server offers it, and
// certificates are verified. AUTH is used only when the URL has user info.
// Without STARTTLS the connection is refused unless it goes to a loopback
// address, so credentials and mail never cross a network in the clear.
//
// An empty smtpURL gives a Mailer that logs "mail not sent: SMTP not
// configured", without the recipient or the body, and returns nil. A URL that
// is not smtp:// is an error.
func New(smtpURL, from string, log *slog.Logger) (core.Mailer, error) {
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	if smtpURL == "" {
		return unconfigured{log: log}, nil
	}
	u, err := url.Parse(smtpURL)
	if err != nil || u.Scheme != "smtp" || u.Hostname() == "" {
		// The URL can carry a password, so it stays out of the error.
		return nil, errors.New("mail: APP_SMTP_URL must look like smtp://[user:pass@]host[:port]")
	}
	fromAddr, err := mail.ParseAddress(from)
	if err != nil {
		return nil, errors.New("mail: sender address is not a valid email address")
	}
	m := &smtpMailer{
		host: u.Hostname(),
		port: u.Port(),
		from: fromAddr.Address,
		log:  log,
		now:  time.Now,
	}
	if m.port == "" {
		m.port = defaultPort
	}
	if u.User != nil {
		m.user = u.User.Username()
		m.pass, _ = u.User.Password()
		m.auth = true
	}
	return m, nil
}

type unconfigured struct{ log *slog.Logger }

func (u unconfigured) Send(ctx context.Context, _, _, _ string) error {
	u.log.InfoContext(ctx, "mail not sent: SMTP not configured")
	return nil
}

type smtpMailer struct {
	host, port string
	user, pass string
	auth       bool
	from       string
	log        *slog.Logger
	now        func() time.Time
	tlsConfig  *tls.Config // tests only; nil means verify against the host name
}

// Send delivers one plain-text message. Errors name the failing step but never
// carry the server's reply, which can echo the recipient address.
func (m *smtpMailer) Send(ctx context.Context, to, subject, body string) error {
	rcpt, err := mail.ParseAddress(to)
	if err != nil {
		return errors.New("mail: recipient is not a valid email address")
	}
	if strings.ContainsAny(subject, "\r\n") {
		return errors.New("mail: subject has a line break")
	}
	msg := m.compose(rcpt.Address, subject, body)

	ctx, cancel := context.WithTimeout(ctx, sendTimeout)
	defer cancel()

	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(m.host, m.port))
	if err != nil {
		return errors.New("mail: cannot connect to the SMTP server")
	}
	// Unblock any pending read or write if the context ends first.
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	defer conn.Close()
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}

	c, err := smtp.NewClient(conn, m.host)
	if err != nil {
		return smtpError("greeting", err)
	}
	defer c.Close()
	if err := c.Hello("localhost"); err != nil {
		return smtpError("hello", err)
	}
	if ok, _ := c.Extension("STARTTLS"); ok {
		cfg := m.tlsConfig
		if cfg == nil {
			cfg = &tls.Config{ServerName: m.host, MinVersion: tls.VersionTLS12}
		}
		if err := c.StartTLS(cfg); err != nil {
			return smtpError("starttls", err)
		}
	} else if !isLoopback(conn.RemoteAddr()) {
		return errors.New("mail: SMTP server does not offer STARTTLS and is not on this machine")
	}
	if m.auth {
		if err := c.Auth(smtp.PlainAuth("", m.user, m.pass, m.host)); err != nil {
			return smtpError("auth", err)
		}
	}
	if err := c.Mail(m.from); err != nil {
		return smtpError("sender", err)
	}
	if err := c.Rcpt(rcpt.Address); err != nil {
		return smtpError("recipient", err)
	}
	w, err := c.Data()
	if err != nil {
		return smtpError("data", err)
	}
	if _, err := w.Write(msg); err != nil {
		return smtpError("data", err)
	}
	if err := w.Close(); err != nil {
		return smtpError("data", err)
	}
	return smtpError("quit", c.Quit())
}

func (m *smtpMailer) compose(to, subject, body string) []byte {
	var b bytes.Buffer
	fmt.Fprintf(&b, "From: %s\r\n", m.from)
	fmt.Fprintf(&b, "To: %s\r\n", to)
	fmt.Fprintf(&b, "Subject: %s\r\n", mime.QEncoding.Encode("utf-8", subject))
	fmt.Fprintf(&b, "Date: %s\r\n", m.now().UTC().Format(time.RFC1123Z))
	b.WriteString("MIME-Version: 1.0\r\n")
	b.WriteString("Content-Type: text/plain; charset=utf-8\r\n")
	b.WriteString("Content-Transfer-Encoding: 8bit\r\n\r\n")
	body = strings.ReplaceAll(body, "\r\n", "\n")
	b.WriteString(strings.ReplaceAll(body, "\n", "\r\n"))
	b.WriteString("\r\n")
	return b.Bytes()
}

// smtpError describes a failed step without the server's text.
func smtpError(step string, err error) error {
	if err == nil {
		return nil
	}
	var te *textproto.Error
	if errors.As(err, &te) {
		return fmt.Errorf("mail: SMTP %s step failed with code %d", step, te.Code)
	}
	return fmt.Errorf("mail: SMTP %s step failed", step)
}

func isLoopback(a net.Addr) bool {
	tcp, ok := a.(*net.TCPAddr)
	return ok && tcp.IP.IsLoopback()
}
