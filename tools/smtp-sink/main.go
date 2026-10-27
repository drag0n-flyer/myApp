// Command smtp-sink is a throwaway SMTP server for end-to-end tests. It
// accepts mail without authentication or TLS and writes each message to
// DIR/<n>.eml (n counts up from 1). Never expose it beyond loopback.
//
//	smtp-sink -listen 127.0.0.1:2525 -out DIR
package main

import (
	"bufio"
	"flag"
	"fmt"
	"log"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

type sink struct {
	dir string
	mu  sync.Mutex
	n   int
}

func main() {
	listen := flag.String("listen", "127.0.0.1:2525", "address to listen on")
	out := flag.String("out", "", "directory for the .eml files")
	flag.Parse()
	if *out == "" {
		log.Fatal("smtp-sink: -out is required")
	}
	if err := os.MkdirAll(*out, 0o700); err != nil {
		log.Fatal(err)
	}
	ln, err := net.Listen("tcp", *listen)
	if err != nil {
		log.Fatal(err)
	}
	s := &sink{dir: *out}
	log.Printf("smtp-sink listening on %s, writing to %s", ln.Addr(), *out)
	for {
		c, err := ln.Accept()
		if err != nil {
			log.Fatal(err)
		}
		go s.serve(c)
	}
}

func (s *sink) serve(c net.Conn) {
	defer c.Close()
	r := bufio.NewReader(c)
	say := func(format string, a ...any) { fmt.Fprintf(c, format+"\r\n", a...) }
	say("220 smtp-sink ready")
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return
		}
		cmd := strings.ToUpper(strings.TrimSpace(line))
		switch {
		case strings.HasPrefix(cmd, "EHLO"):
			say("250-smtp-sink")
			say("250 8BITMIME")
		case strings.HasPrefix(cmd, "HELO"), strings.HasPrefix(cmd, "MAIL"),
			strings.HasPrefix(cmd, "RCPT"), cmd == "RSET", cmd == "NOOP":
			say("250 ok")
		case cmd == "DATA":
			say("354 end with <CRLF>.<CRLF>")
			body, err := readData(r)
			if err != nil {
				return
			}
			if err := s.store(body); err != nil {
				say("451 cannot store")
				continue
			}
			say("250 stored")
		case cmd == "QUIT":
			say("221 bye")
			return
		default:
			say("502 not supported")
		}
	}
}

// readData reads up to the lone "." line and undoes dot-stuffing.
func readData(r *bufio.Reader) (string, error) {
	var b strings.Builder
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			return "", err
		}
		if strings.TrimRight(line, "\r\n") == "." {
			return b.String(), nil
		}
		b.WriteString(strings.TrimPrefix(line, "."))
	}
}

// store writes the message under a temporary name first, so a reader never
// sees a half-written .eml.
func (s *sink) store(body string) error {
	s.mu.Lock()
	s.n++
	n := s.n
	s.mu.Unlock()
	final := filepath.Join(s.dir, fmt.Sprintf("%d.eml", n))
	tmp := final + ".tmp"
	if err := os.WriteFile(tmp, []byte(body), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, final)
}
