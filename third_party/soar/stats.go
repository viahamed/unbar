package soar

import "time"

// PathStat is a snapshot of one resolver path, for diagnostics: which
// resolvers carry the tunnel on the user's network, and how well.
type PathStat struct {
	Resolver    string        `json:"resolver"`            // host:port of the recursive resolver
	Transport   string        `json:"transport"`           // "udp" or "tcp" (TCP/53, or what Config.DialTCP makes of it)
	Verified    bool          `json:"verified"`            // has carried at least one authenticated answer
	Suspended   bool          `json:"suspended"`           // backed off after a run of losses
	Healthy     bool          `json:"healthy"`             // Go's own verdict: verified, usable now, and not on a run of losses
	Candidate   bool          `json:"candidate,omitempty"` // added by discovery
	RTT         time.Duration `json:"rtt"`
	Loss        float64       `json:"loss"` // fraction lost over the recent outcome window
	Inflight    int           `json:"inflight"`
	Window      float64       `json:"window"`
	NameLen     int           `json:"name_len"`     // current query-name cap
	AnswerLimit int           `json:"answer_limit"` // answer size cap sizing has accepted for this path; 0 until verified
	RecordType  string        `json:"record_type"`
	Successes   int           `json:"successes"`
	LastSuccess time.Time     `json:"last_success,omitzero"`
	Succ        int           `json:"succ"`       // on-time successes (successes minus late arrivals)
	Late        int           `json:"late"`       // answers that arrived after the query was counted lost
	Expired     int           `json:"expired"`    // queries that expired unanswered (polls + data)
	Never       int           `json:"never"`      // expired queries still unanswered after the late grace
	HeldMeanMs  int64         `json:"heldMeanMs"` // mean server hold, milliseconds
	HeldMaxMs   int64         `json:"heldMaxMs"`  // max server hold, milliseconds
	DataAns     int           `json:"dataAns"`    // data-carrying answers, session-wide (same on every path)
	EmptyAns    int           `json:"emptyAns"`   // empty (poll) answers, session-wide (same on every path)
	DownBytes   uint64        `json:"downBytes"`  // payload bytes of data-carrying answers, session-wide
	UpBytes     uint64        `json:"upBytes"`    // payload bytes the server acknowledged, session-wide (1.0.51)
}

// PathStats returns a snapshot of every path, in the order paths were added: configured
// resolvers first, then paths added later (TCP siblings under TCP "auto", and discovered
// resolvers).
func (c *Client) PathStats() []PathStat {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := time.Now()
	out := make([]PathStat, 0, len(c.paths))
	for _, p := range c.paths {
		heldMean := time.Duration(0)
		if p.heldN > 0 {
			heldMean = p.heldSum / time.Duration(p.heldN)
		}
		s := PathStat{
			Resolver:    p.addr.String(),
			Transport:   "udp",
			Verified:    p.verified,
			Suspended:   p.suspended.After(now),
			Healthy:     p.healthy(now),
			Candidate:   p.candidate,
			Loss:        p.lossRate(),
			Inflight:    p.inflight,
			Window:      p.cwnd,
			NameLen:     p.nameLen,
			RecordType:  "TXT",
			Successes:   p.successes,
			LastSuccess: p.lastSuccess,
			Succ:        p.successes - p.lateArrivals,
			Late:        p.lateArrivals,
			Expired:     p.expPoll + p.expData,
			Never:       p.neverArrived,
			HeldMeanMs:  heldMean.Milliseconds(),
			HeldMaxMs:   p.heldMax.Milliseconds(),
			DataAns:     c.ansData,
			EmptyAns:    c.ansEmpty,
			DownBytes:   c.downBytes,
			UpBytes:     c.upBytes.Load(),
		}
		if p.tcp != nil {
			s.Transport = "tcp"
		}
		// The exported RTT is the effective one (see effRTT): a path
		// whose answers mostly arrive late has an on-time srtt that only
		// reflects the fast tail, and a late-only path has no srtt at
		// all — both would be ranked as the fastest (0 sorts first).
		// 0 here means the path never answered at all.
		s.RTT, _ = p.effRTT()
		if p.verified {
			s.AnswerLimit = 512 + 48*p.sizeOK
		}
		if p.qtype == typeAAAA {
			s.RecordType = "AAAA"
		}
		out = append(out, s)
	}
	return out
}

// WireBytes returns cumulative wire payload bytes: upstream bytes the
// server acknowledged (each byte once — retransmissions never count
// twice) and downstream payload bytes of data-carrying answers. Unlike
// the countingConn totals in core (bytes handed to the Soar streams),
// these track what actually crossed the DNS link, so the dashboard
// speeds derived from them are real. (1.0.51)
func (c *Client) WireBytes() (up, down uint64) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.upBytes.Load(), c.downBytes
}

// Established reports whether a session with the server is up.
func (c *Client) Established() bool {
	c.mu.Lock()
	cs := c.sess
	c.mu.Unlock()
	if cs == nil {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
	}
	select {
	case <-cs.ready:
		return cs.session != nil && !cs.isClosed()
	default:
		return false
	}
}
