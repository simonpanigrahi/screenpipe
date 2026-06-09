# Protocol Specification

> **Status: Placeholder — Milestone 3**

This directory is the future home of the binary framing specification for the
screenpipe transport layer.  The Phase-1 scaffold (Milestone 1) ships a raw
Annex-B H.264 byte-stream over a plain TCP socket, split on NAL start codes by
the Android client.  Milestone 3 will replace that with a proper framed
protocol: length-prefixed access units (4-byte big-endian payload length
preceding each AU), a versioned handshake exchanged at connection setup, and a
multiplexed control channel carrying display-configuration commands and — once
Milestone 5 lands — touch/input events back to the host.  Full wire-format
tables, state-machine diagrams, and version-negotiation rules will live here.
