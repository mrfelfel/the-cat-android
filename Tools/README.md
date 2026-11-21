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

### Sizing for the size that arrives

The export is rendered at the width the image will actually be seen at (the
"عرض نهایی" field, default 1280), not larger. Exporting 3000px and letting the
host downscale loses most of every module, which is how a code that looked fine
in the panel arrives unreadable.

The code sits on a flat white card rather than directly on the photo. Over
texture, JPEG's high-frequency loss blurs black modules into the background at
exactly the moment the code needs contrast. Modules are drawn at an integer
pixel count, and the panel picks the tier with the largest modules — for the
published document that is version 29 at 5px per module, which decoded at every
JPEG quality from 92 down to 30.

After building, the panel pushes the image through the same downscale-and-JPEG
path a chat app applies and counts how many modules came out changed. The
second canvas below the button is what the recipient sees; if the code is
legible there, it will be read.

### What actually limits decode reliability

Not ECC level, and not data corruption. Measured with `gozxing` — the decoder
the client uses — a clean 133-module code at 4px modules decoded at only 10 of
21 widths between 1280 and 480px, and the failures alternate rather than
following any size threshold. The same frames decode once edges are restored.
So the client's `decodeQRPayload` retries the frame sharpened and enlarged
after the plain pass; that took the sweep from 10/21 to a majority, and reads
the panel's own output at every width down to 700px.

`qr-encode.js` is a self-contained QR encoder (byte mode, versions 1–40, all
ECC levels). It is deliberately not loaded from a CDN: a tool for building
censorship-resistant carriers should not depend on a reachable third party.
Version selection matches `segno` exactly.

Verified against `gozxing` across all ECC levels, on the published document,
and after a second re-compression of the shipped file.
