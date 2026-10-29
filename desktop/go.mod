module github.com/ksenxxoen/JagaNet/desktop

go 1.25.0

require (
	github.com/amnezia-vpn/amneziawg-go/v3 v3.1.20260828
	github.com/amnezia-vpn/amneziawg-windows/v3 v3.1.20260814
	github.com/jchv/go-webview2 v0.0.0-20260205173254-56598839c808
	github.com/xtls/reality v0.0.0-20251014195629-e4eec4520535
	github.com/xtls/xray-core v1.251208.0
	golang.org/x/crypto v0.44.0
	golang.org/x/sys v0.38.0
	gvisor.dev/gvisor v0.0.0-20250428193742-2d800c3129d5
)

require (
	github.com/andybalholm/brotli v1.0.6 // indirect
	github.com/cloudflare/circl v1.6.1 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/gorilla/websocket v1.5.3 // indirect
	github.com/jchv/go-winloader v0.0.0-20250406163304-c1995be93bd1 // indirect
	github.com/juju/ratelimit v1.0.2 // indirect
	github.com/klauspost/compress v1.17.4 // indirect
	github.com/klauspost/cpuid/v2 v2.0.12 // indirect
	github.com/miekg/dns v1.1.68 // indirect
	github.com/pires/go-proxyproto v0.8.1 // indirect
	github.com/quic-go/qpack v0.6.0 // indirect
	github.com/quic-go/quic-go v0.57.1 // indirect
	github.com/refraction-networking/utls v1.8.1 // indirect
	github.com/sagernet/sing v0.5.1 // indirect
	golang.org/x/mod v0.29.0 // indirect
	golang.org/x/net v0.47.0 // indirect
	golang.org/x/sync v0.18.0 // indirect
	golang.org/x/text v0.31.0 // indirect
	golang.org/x/time v0.12.0 // indirect
	golang.org/x/tools v0.38.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	google.golang.org/grpc v1.77.0 // indirect
	google.golang.org/protobuf v1.36.10 // indirect
	lukechampine.com/blake3 v1.4.1 // indirect
)

replace github.com/sagernet/sing => ./third_party/sing-nogpl
