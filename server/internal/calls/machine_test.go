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
	wantFrom(t, outs, "a1")
	outs, err = c.Signal("b2", callees, []byte("y"))
	if err != nil || !reflect.DeepEqual(outs[0].To, []string{"a1"}) {
		t.Fatalf("%v %+v", err, outs)
	}
	wantFrom(t, outs, "b2")
	if _, err := c.Signal("zz", callees, nil); !errors.Is(err, ErrNotParticipant) {
		t.Fatal(err)
	}
	c.Answer("b2", callees, nil, t0)
	outs, _ = c.Signal("a1", callees, []byte("x"))
	if !reflect.DeepEqual(outs[0].To, []string{"b2"}) {
		t.Fatalf("%+v", outs)
	}
	wantFrom(t, outs, "a1")
	outs, _ = c.Signal("b2", callees, []byte("x"))
	if !reflect.DeepEqual(outs[0].To, []string{"a1"}) {
		t.Fatalf("%+v", outs)
	}
	wantFrom(t, outs, "b2")
	if _, err := c.Signal("b1", callees, nil); !errors.Is(err, ErrNotParticipant) {
		t.Fatalf("%v", err)
	}
}

func wantFrom(t *testing.T, outs []Output, from string) {
	t.Helper()
	d := data(outs[0])
	if d["fromDeviceID"] != from || d["callID"] != "c1" || d["payload"] == nil {
		t.Fatalf("signal data %+v", d)
	}
}

func TestDeclineAfterAnswerAndStrangerOnEnded(t *testing.T) {
	c := newCall()
	c.Answer("b1", callees, nil, t0)
	if _, err := c.Decline("b2", callees, t0); !errors.Is(err, ErrWrongState) {
		t.Fatalf("decline after answer: %v", err)
	}
	if _, err := c.Decline("b1", callees, t0); !errors.Is(err, ErrWrongState) {
		t.Fatalf("answerer decline after answer: %v", err)
	}
	c.Hangup("a1", callees, t0)
	for name, err := range map[string]error{
		"answer":  second(c.Answer("zz", callees, nil, t0)),
		"decline": second(c.Decline("zz", callees, t0)),
		"hangup":  second(c.Hangup("zz", callees, t0)),
		"signal":  second(c.Signal("zz", callees, nil)),
	} {
		if !errors.Is(err, ErrNotParticipant) {
			t.Errorf("%s: %v", name, err)
		}
	}
}

func TestAnswerDeclineAfterTimeoutRefused(t *testing.T) {
	late := t0.Add(45 * time.Second)
	c := newCall()
	if _, err := c.Answer("b1", callees, nil, late); !errors.Is(err, ErrWrongState) {
		t.Fatalf("answer: %v", err)
	}
	if _, err := c.Decline("b1", callees, late); !errors.Is(err, ErrWrongState) {
		t.Fatalf("decline: %v", err)
	}
	if c.State != Ringing || c.AnsweredBy != "" {
		t.Fatalf("changed: %+v", c)
	}
	if _, err := c.Answer("b1", callees, nil, t0.Add(44*time.Second)); err != nil {
		t.Fatalf("answer at 44 s: %v", err)
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
	participants := append([]string{"a1"}, callees...)
	devs := append(slices.Clone(participants), "stranger")
	var opsIn [3]int // operations seen per starting state
	calls := 0
	for seed := int64(0); seed < 50; seed++ {
		rng := rand.New(rand.NewSource(seed))
		now := t0
		fresh := func() *Call { calls++; c := newCall(); c.Started, c.Changed = now, now; return c }
		c := fresh()
		for i := 0; i < 1000; i++ {
			now = now.Add(time.Duration(rng.Intn(8)) * time.Second)
			if c.State == Ended && rng.Intn(4) != 0 {
				c = fresh() // mostly start a new call so ops spread over states
			}
			before := *c
			opsIn[before.State]++
			dev := devs[rng.Intn(len(devs))]
			op := rng.Intn(5)
			var outs []Output
			var err error
			switch op {
			case 0:
				outs, err = c.Answer(dev, callees, []byte("p"), now)
			case 1:
				outs, err = c.Decline(dev, callees, now)
			case 2:
				outs, err = c.Hangup(dev, callees, now)
			case 3:
				outs, err = c.Signal(dev, callees, []byte("p"))
			case 4:
				outs = c.Tick(callees, now)
			}
			if err != nil && *c != before {
				t.Fatalf("seed %d op %d: error %v changed the call: %+v -> %+v", seed, op, err, before, *c)
			}
			if dev == "stranger" && op != 4 && !errors.Is(err, ErrNotParticipant) {
				t.Fatalf("seed %d op %d: stranger got %v on a %v call", seed, op, err, before.State)
			}
			if before.State == Ended && (len(outs) != 0 || *c != before) {
				t.Fatalf("seed %d op %d: ended call changed or produced output", seed, op)
			}
			if before.AnsweredBy != "" && c.AnsweredBy != before.AnsweredBy {
				t.Fatalf("seed %d op %d: AnsweredBy overwritten", seed, op)
			}
			if before.State == Answered && before.AnsweredBy == "" || c.State == Answered && !slices.Contains(callees, c.AnsweredBy) || c.State == Ringing && c.AnsweredBy != "" {
				t.Fatalf("seed %d op %d: bad AnsweredBy %+v", seed, op, c)
			}
			if c.State == Ended && c.Reason == "" || c.State < before.State {
				t.Fatalf("seed %d op %d: bad transition %v -> %v", seed, op, before.State, c.State)
			}
			for _, o := range outs {
				if len(o.To) == 0 {
					t.Fatalf("seed %d op %d: no recipients", seed, op)
				}
				for _, to := range o.To {
					if !slices.Contains(participants, to) {
						t.Fatalf("seed %d op %d: output to non-participant %q", seed, op, to)
					}
					if op == 3 && to == dev {
						t.Fatalf("seed %d: signal sent back to its sender", seed)
					}
					// Once answered, only the two connected devices hear anything,
					// apart from the answered_elsewhere notice to the others.
					if before.State == Answered && to != before.CallerDevice && to != before.AnsweredBy {
						t.Fatalf("seed %d op %d: answered-call output to %q", seed, op, to)
					}
					if op == 0 && before.State == Ringing && o.Type == "call.answered" && !reflect.DeepEqual(o.To, []string{before.CallerDevice}) {
						t.Fatalf("seed %d: call.answered to %v", seed, o.To)
					}
				}
			}
		}
	}
	if opsIn[Ringing] < 5000 || opsIn[Answered] < 5000 || opsIn[Ended] < 1000 || calls < 500 {
		t.Fatalf("poor coverage: ops per state %v, calls %d", opsIn, calls)
	}
}
