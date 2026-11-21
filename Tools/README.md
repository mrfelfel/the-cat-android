# Tools

## StegoCarrier.html

Turns any photo into a carrier for the signed `servers-v2.json`.

Open it in a browser, paste (or fetch) the document, drop a photo, build.

Two modes, and the choice matters more than anything else here:

**نامرئی (LSB)** — the document goes into the least significant bits of the
RGB channels. The photo looks untouched and the output is small. It must stay
**PNG**: any re-encode destroys the bit plane. Measured on a real 750×500
photo, JPEG loses the payload at quality 100, 90 and 75 alike — JPEG's
transform quantises every pixel, so there is no quality setting that preserves
an LSB plane.

**مقاوم (QR)** — the document is zlib-compressed, base64-encoded and drawn as a
QR code on the picture. Visible, but it survives re-compression, which is what
matters if the image will pass through a service that re-encodes it.

`qr-encode.js` is a self-contained QR encoder (byte mode, versions 1–40, all
ECC levels). It is deliberately not loaded from a CDN: a tool for building
censorship-resistant carriers should not depend on a reachable third party.

Verified against a real decoder (`gozxing`) across all four ECC levels and
payloads up to 1500 bytes, and on the actual published document.
