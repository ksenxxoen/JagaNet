//go:build !race

package xray

const raceOn = false

func forTraffic(p Params) Params { return p }
