package core

import (
	_ "embed"
	"net/netip"
	"strings"
	"sync"
)

//go:embed ir-ranges.txt
var irRangesData string

// geoBypass matches Iranian destinations: .ir domains and Iranian IP ranges.
type geoBypass struct {
	once     sync.Once
	prefixes []netip.Prefix
}

func newGeoBypass() *geoBypass { return &geoBypass{} }

func (g *geoBypass) load() {
	g.once.Do(func() {
		for _, line := range strings.Split(irRangesData, "\n") {
			line = strings.TrimSpace(line)
			if line == "" || strings.HasPrefix(line, "#") {
				continue
			}
			if p, err := netip.ParsePrefix(line); err == nil {
				g.prefixes = append(g.prefixes, p)
			}
		}
	})
}

// isIranian reports whether host is an Iranian destination: a .ir domain,
// or an IP inside an Iranian range.
func (g *geoBypass) isIranian(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	if h == "ir" || strings.HasSuffix(h, ".ir") {
		return true
	}
	ip, err := netip.ParseAddr(h)
	if err != nil {
		return false
	}
	// (1.0.15): no Is4() gate — the list now carries IPv6 ranges
	// too, and Prefix.Contains matches the address family.
	g.load()
	for _, p := range g.prefixes {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}
