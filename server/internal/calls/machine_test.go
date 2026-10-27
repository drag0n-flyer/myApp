package calls

import (
	"errors"
	"math/rand"
	"reflect"
	"slices"
	"sort"
	"testing"
	"time"
)

var (
	t0      = time.Date(2026, 11, 1, 12, 0, 0, 0, time.UTC)
	callees = []string{"b1", "b2", "b3"}
)

func newCall() *Call {
	return &Call{ID: "c1", CallerAccount: "A", CallerDevice: "a1", CalleeAccount: "B", State: Ringing, Started: t0, Changed: t0}
}

func sorted(s []string) []string { s = slices.Clone(s); sort.Strings(s); return s }

func find(t *testing.T, outs []Output, typ string) Output {
	t.Helper()
	for _, o := range outs {
		if o.Type == typ {
			return o
		}
	}
	t.Fatalf("no %s in %+v", typ, outs)
	return Output{}
}

func data(o Output) map[string]any { return o.Data.(map[string]any) }

func TestAnswer(t *testing.T) {
	c := newCall()
	outs, err := c.Answer("b2", callees, []byte("sdp"), t0.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if c.State != Answered || c.AnsweredBy != "b2" || !c.Changed.Equal(t0.Add(time.Second)) {
		t.Fatalf("%+v", c)
	}
	a := find(t, outs, "call.answered")
	if !reflect.DeepEqual(a.To, []string{"a1"}) || data(a)["deviceID"] != "b2" || !reflect.DeepEqual(data(a)["payload"], []byte("sdp")) || data(a)["callID"] != "c1" {
		t.Fatalf("%+v", a)
	}
	e := find(t, outs, "call.ended")
	if !reflect.DeepEqual(sorted(e.To), []string{"b1", "b3"}) || data(e)["reason"] != "answered_elsewhere" {
		t.Fatalf("%+v", e)
	}
	if len(outs) != 2 {
		t.Fatalf("%+v", outs)
	}
}

func TestAnswerRules(t *testing.T) {
	c := newCall()
	if _, err := c.Answer("a1", callees, nil, t0); !errors.Is(err, ErrNotParticipant) {
		t.Fatalf("caller answer: %v", err)
	}
	if _, err := c.Answer("zz", callees, nil, t0); !errors.Is(err, ErrNotParticipant) {
		t.Fatalf("stranger: %v", err)
	}
	c.Answer("b1", callees, nil, t0)
	if _, err := c.Answer("b2", callees, nil, t0); !errors.Is(err, ErrWrongState) {
		t.Fatalf("second answer: %v", err)
	}
}

func TestDeclineEndsEverywhere(t *testing.T) {
	c := newCall()
	if _, err := c.Decline("a1", callees, t0); !errors.Is(err, ErrNotParticipant) {
		t.Fatalf("caller decline: %v", err)
	}
	outs, err := c.Decline("b3", callees, t0.Add(time.Second))
	if err != nil {
		t.Fatal(err)
	}
	if c.State != Ended || c.Reason != "declined" {
		t.Fatalf("%+v", c)
	}
	e := find(t, outs, "call.ended")
	if !reflect.DeepEqual(sorted(e.To), []string{"a1", "b1", "b2", "b3"}) || data(e)["reason"] != "declined" {
		t.Fatalf("%+v", e)
	}
}

func TestHangupFromEitherSide(t *testing.T) {
	c := newCall()
	outs, err := c.Hangup("a1", callees, t0)
	if err != nil || c.State != Ended || c.Reason != "hangup" {
		t.Fatalf("%v %+v", err, c)
	}
	if got := sorted(find(t, outs, "call.ended").To); !reflect.DeepEqual(got, []string{"a1", "b1", "b2", "b3"}) {
		t.Fatalf("%v", got)
	}
	// callee hangs up while answered: only the two connected devices hear it.
	c = newCall()
	c.Answer("b1", callees, nil, t0)
	outs, err = c.Hangup("b1", callees, t0)
	if err != nil || c.Reason != "hangup" {
		t.Fatal(err)
	}
	if got := sorted(find(t, outs, "call.ended").To); !reflect.DeepEqual(got, []string{"a1", "b1"}) {
		t.Fatalf("%v", got)
	}
	// a ringing-only callee device cannot hang up an answered call.
	c = newCall()
	c.Answer("b1", callees, nil, t0)
	if _, err := c.Hangup("b2", callees, t0); !errors.Is(err, ErrNotParticipant) {
		t.Fatalf("%v", err)
	}
}

func TestSignalRouting(t *testing.T) {
	c := newCall()
	outs, err := c.Signal("a1", callees, []byte("x"))
	if err != nil || len(outs) != 1 || outs[0].Type != "call.signal" || !reflect.DeepEqual(sorted(outs[0].To), callees) {
		t.Fatalf("%v %+v", err, outs)
	}
	outs, err = c.Signal("b2", callees, []byte("y"))
	if err != nil || !reflect.DeepEqual(outs[0].To, []string{"a1"}) {
		t.Fatalf("%v %+v", err, outs)
	}
	if _, err := c.Signal("zz", callees, nil); !errors.Is(err, ErrNotParticipant) {
		t.Fatal(err)
	}
	c.Answer("b2", callees, nil, t0)
	outs, _ = c.Signal("a1", callees, []byte("x"))
	if !reflect.DeepEqual(outs[0].To, []string{"b2"}) {
		t.Fatalf("%+v", outs)
	}
	outs, _ = c.Signal("b2", callees, []byte("x"))
	if !reflect.DeepEqual(outs[0].To, []string{"a1"}) {
		t.Fatalf("%+v", outs)
	}
	if _, err := c.Signal("b1", callees, nil); !errors.Is(err, ErrNotParticipant) {
		t.Fatalf("%v", err)
	}
}

func TestRingTimeout(t *testing.T) {
	c := newCall()
	if outs := c.Tick(callees, t0.Add(44*time.Second)); len(outs) != 0 || c.State != Ringing {
		t.Fatalf("%+v", outs)
	}
	outs := c.Tick(callees, t0.Add(45*time.Second))
	e := find(t, outs, "call.ended")
	if !reflect.DeepEqual(sorted(e.To), []string{"a1", "b1", "b2", "b3"}) || data(e)["reason"] != "missed" || c.State != Ended || c.Reason != "missed" {
		t.Fatalf("%+v %+v", e, c)
	}
	if outs := c.Tick(callees, t0.Add(time.Hour)); len(outs) != 0 {
		t.Fatal("tick on ended call produced output")
	}
	// An answered call never times out.
	c = newCall()
	c.Answer("b1", callees, nil, t0)
	if outs := c.Tick(callees, t0.Add(time.Hour)); len(outs) != 0 || c.State != Answered {
		t.Fatal("answered call ticked")
	}
}

func TestEndedIgnoresEverything(t *testing.T) {
	c := newCall()
	c.Hangup("a1", callees, t0)
	for name, err := range map[string]error{
		"answer":  second(c.Answer("b1", callees, nil, t0)),
		"decline": second(c.Decline("b1", callees, t0)),
		"hangup":  second(c.Hangup("a1", callees, t0)),
		"signal":  second(c.Signal("a1", callees, nil)),
	} {
		if !errors.Is(err, ErrWrongState) {
			t.Errorf("%s: %v", name, err)
		}
	}
	if c.Reason != "hangup" {
		t.Fatal("reason changed")
	}
}

func second(_ []Output, err error) error { return err }

func TestMachineProperties(t *testing.T) {
	for seed := int64(0); seed < 50; seed++ {
		rng := rand.New(rand.NewSource(seed))
		c := newCall()
		participants := append([]string{"a1"}, callees...)
		devs := append(slices.Clone(participants), "stranger")
		now := t0
		var ended bool
		var reason, answeredBy string
		for i := 0; i < 1000; i++ {
			now = now.Add(time.Duration(rng.Intn(20)) * time.Second)
			dev := devs[rng.Intn(len(devs))]
			var outs []Output
			switch rng.Intn(5) {
			case 0:
				outs, _ = c.Answer(dev, callees, []byte("p"), now)
			case 1:
				outs, _ = c.Decline(dev, callees, now)
			case 2:
				outs, _ = c.Hangup(dev, callees, now)
			case 3:
				outs, _ = c.Signal(dev, callees, []byte("p"))
			case 4:
				outs = c.Tick(callees, now)
			}
			for _, o := range outs {
				if len(o.To) == 0 {
					t.Fatalf("seed %d: output with no recipients", seed)
				}
				for _, to := range o.To {
					if !slices.Contains(participants, to) {
						t.Fatalf("seed %d: output to non-participant %q", seed, to)
					}
				}
			}
			if ended && (c.State != Ended || c.Reason != reason || len(outs) != 0) {
				t.Fatalf("seed %d: ended call changed or produced output", seed)
			}
			if answeredBy != "" && c.AnsweredBy != answeredBy {
				t.Fatalf("seed %d: AnsweredBy changed", seed)
			}
			if c.State == Answered && !slices.Contains(callees, c.AnsweredBy) {
				t.Fatalf("seed %d: answered by non-callee", seed)
			}
			if c.State == Ringing && c.AnsweredBy != "" {
				t.Fatalf("seed %d: ringing with AnsweredBy", seed)
			}
			if c.State == Ended && c.Reason == "" {
				t.Fatalf("seed %d: ended without reason", seed)
			}
			if c.State == Ended {
				ended, reason = true, c.Reason
			}
			answeredBy = c.AnsweredBy
		}
	}
}
